package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.AirtimePurchaseRequest;
import com.moniewise.moniewise_backend.dto.request.DataPurchaseRequest;
import com.moniewise.moniewise_backend.entity.PayeelordDataPlan;
import com.moniewise.moniewise_backend.entity.PayeelordVasTransaction;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.PayeelordCatalogSyncJob;
import com.moniewise.moniewise_backend.service.PayeelordVasService;
import com.moniewise.moniewise_backend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Value-Added-Services endpoints — Payeelord-backed airtime & data purchases.
 *
 * <p>Mirrors {@code WalletController#withdrawFunds}'s shape exactly: PIN-gated,
 * throttled via {@link AbuseProtectionService}, errors sanitised into
 * {@code {"status": false, "error": "..."}} JSON rather than stack traces.
 *
 * <p>Scope note: deliberately limited to airtime & data — Payeelord's broader
 * catalog (electricity, cable, e-pins, bulk SMS) is out of scope per product decision.
 */
@RestController
@Slf4j
@RequestMapping("/vas")
public class VasController {

    private final PayeelordVasService vasService;
    private final UserService userService;
    private final AbuseProtectionService abuseProtectionService;
    private final PayeelordCatalogSyncJob catalogSyncJob;

    // Prevents concurrent auto-syncs when multiple users hit /data/plans simultaneously
    private final java.util.concurrent.atomic.AtomicBoolean syncInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public VasController(PayeelordVasService vasService,
                          UserService userService,
                          AbuseProtectionService abuseProtectionService,
                          PayeelordCatalogSyncJob catalogSyncJob) {
        this.vasService = vasService;
        this.userService = userService;
        this.abuseProtectionService = abuseProtectionService;
        this.catalogSyncJob = catalogSyncJob;
    }

    // ── Airtime ───────────────────────────────────────────────────────────────

    @PostMapping("/airtime/purchase")
    public ResponseEntity<?> purchaseAirtime(@Valid @RequestBody AirtimePurchaseRequest request,
                                             Principal principal,
                                             HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(principal.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.VAS_AIRTIME_PURCHASE, throttleKey);
        try {
            User user = userService.findByEmail(principal.getName());
            PayeelordVasTransaction txn = vasService.purchaseAirtime(user.getId(), request);
            abuseProtectionService.recordSuccess(AbuseProtectionService.VAS_AIRTIME_PURCHASE, throttleKey);
            return ResponseEntity.ok(Map.of(
                    "status", true,
                    "message", purchaseStatusMessage(txn.getStatus().name()),
                    "data", toTransactionMap(txn)
            ));
        } catch (com.moniewise.moniewise_backend.exception.InsufficientFundsException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.VAS_AIRTIME_PURCHASE, throttleKey);
            return ResponseEntity.status(422).body(Map.of("status", false, "error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.VAS_AIRTIME_PURCHASE, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("status", false, "error", e.getMessage()));
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.VAS_AIRTIME_PURCHASE, throttleKey);
            return ResponseEntity.internalServerError().body(Map.of("status", false, "error", e.getMessage()));
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    @PostMapping("/data/purchase")
    public ResponseEntity<?> purchaseData(@Valid @RequestBody DataPurchaseRequest request,
                                          Principal principal,
                                          HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(principal.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.VAS_DATA_PURCHASE, throttleKey);
        try {
            User user = userService.findByEmail(principal.getName());
            PayeelordVasTransaction txn = vasService.purchaseData(user.getId(), request);
            abuseProtectionService.recordSuccess(AbuseProtectionService.VAS_DATA_PURCHASE, throttleKey);
            return ResponseEntity.ok(Map.of(
                    "status", true,
                    "message", purchaseStatusMessage(txn.getStatus().name()),
                    "data", toTransactionMap(txn)
            ));
        } catch (com.moniewise.moniewise_backend.exception.InsufficientFundsException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.VAS_DATA_PURCHASE, throttleKey);
            return ResponseEntity.status(422).body(Map.of("status", false, "error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.VAS_DATA_PURCHASE, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("status", false, "error", e.getMessage()));
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.VAS_DATA_PURCHASE, throttleKey);
            return ResponseEntity.internalServerError().body(Map.of("status", false, "error", e.getMessage()));
        }
    }

    /** Plan picker — only ever shows ACTIVE plans, sorted for a sane UI grouping (network, then cheapest first). */
    @GetMapping("/data/plans")
    public ResponseEntity<?> getDataPlans(@RequestParam(value = "networkId", required = false) String networkId) {
        List<PayeelordDataPlan> plans = (networkId != null && !networkId.isBlank())
                ? vasService.getActiveDataPlansForNetwork(networkId.trim())
                : vasService.getActiveDataPlans();

        // Auto-heal: catalog table is empty — kick a background sync so the NEXT request returns plans.
        // syncInProgress guard prevents duplicate concurrent syncs.
        if (plans.isEmpty() && syncInProgress.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    int count = catalogSyncJob.syncNow();
                    log.info("[VAS] Auto-sync (empty catalog) — {} plans loaded", count);
                } catch (Exception e) {
                    log.warn("[VAS] Auto-sync (empty catalog) failed: {}", e.getMessage());
                } finally {
                    syncInProgress.set(false);
                }
            }, "vas-catalog-autosync").start();
        }

        List<Map<String, Object>> data = plans.stream().map(this::toPlanMap).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("status", true, "data", data));
    }

    // ── History ───────────────────────────────────────────────────────────────

    @GetMapping("/transactions")
    public ResponseEntity<?> getRecentTransactions(Principal principal) {
        User user = userService.findByEmail(principal.getName());
        List<PayeelordVasTransaction> txns = vasService.getRecentTransactions(user.getId());
        List<Map<String, Object>> data = txns.stream().map(this::toTransactionMap).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("status", true, "data", data));
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private String purchaseStatusMessage(String status) {
        switch (status) {
            case "SUCCESSFUL": return "Purchase completed successfully.";
            case "PENDING":    return "Purchase is being confirmed by the provider — you'll be notified shortly.";
            case "REVERSED":   return "Purchase could not be completed — your envelope has been refunded.";
            case "MANUAL_REVIEW": return "Purchase needs provider confirmation. Our team is reviewing it.";
            default:           return "Purchase submitted.";
        }
    }

    private Map<String, Object> toTransactionMap(PayeelordVasTransaction txn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            txn.getId());
        m.put("reference",     txn.getReference());
        m.put("type",          txn.getType().name());
        m.put("status",        txn.getStatus().name());
        m.put("network",       txn.getNetwork());
        m.put("mobileNumber",  txn.getMobileNumber());
        m.put("planName",      txn.getDataPlan() != null ? txn.getDataPlan().getDisplayLabel() : null);
        m.put("faceAmount",    txn.getFaceAmount());
        m.put("amountCharged", txn.getSellingAmount());
        m.put("failureReason", txn.getFailureReason());
        m.put("createdAt",     txn.getCreatedAt());
        return m;
    }

    private Map<String, Object> toPlanMap(PayeelordDataPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataId",         plan.getDataId());
        m.put("networkId",      plan.getNetworkId());
        m.put("network",        plan.getNetworkName());
        m.put("planType",       plan.getPlanType());
        m.put("planName",       plan.getDisplayLabel());
        m.put("size",           plan.getSizeLabel());
        m.put("validity",       plan.getValidityDisplay());
        m.put("price",          plan.getSellingPrice());
        return m;
    }
}
