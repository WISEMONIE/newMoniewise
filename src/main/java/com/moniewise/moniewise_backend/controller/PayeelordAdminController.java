package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.psp.payeelord.PayeelordGateway;
import com.moniewise.moniewise_backend.service.PayeelordCatalogSyncJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Admin-only Payeelord operations that can't be done from the user app.
 *
 * <ul>
 *   <li>{@code POST /admin/payeelord/sync-catalog} — pull the live data-plan
 *       catalog from Payeelord now (instead of waiting for the nightly job),
 *       so {@code GET /vas/data/plans} starts returning real bundles.</li>
 *   <li>{@code GET /admin/payeelord/balance} — read our Payeelord float balance
 *       on demand (the same value the low-balance monitor watches).</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/payeelord")
@PreAuthorize("hasRole('ADMIN')")
public class PayeelordAdminController {

    private static final Logger logger = LoggerFactory.getLogger(PayeelordAdminController.class);

    private final PayeelordCatalogSyncJob catalogSyncJob;
    private final PayeelordGateway gateway;

    public PayeelordAdminController(PayeelordCatalogSyncJob catalogSyncJob, PayeelordGateway gateway) {
        this.catalogSyncJob = catalogSyncJob;
        this.gateway = gateway;
    }

    @PostMapping("/sync-catalog")
    public ResponseEntity<?> syncCatalog() {
        try {
            int count = catalogSyncJob.syncNow();
            logger.info("[Admin] Payeelord catalog sync triggered manually — {} plans processed", count);
            return ResponseEntity.ok(Map.of(
                    "status", true,
                    "plansSynced", count,
                    "message", count > 0
                            ? "Catalog synced — " + count + " plans pulled from Payeelord."
                            : "Sync ran but pulled 0 plans — check Payeelord API key/connectivity."
            ));
        } catch (RuntimeException e) {
            logger.error("[Admin] Payeelord catalog sync failed", e);
            return ResponseEntity.internalServerError().body(Map.of("status", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/balance")
    public ResponseEntity<?> checkBalance() {
        BigDecimal balance = gateway.checkWalletBalance().orElse(null);
        if (balance == null) {
            return ResponseEntity.ok(Map.of(
                    "status", false,
                    "message", "Could not read Payeelord balance — check API key/connectivity."));
        }
        return ResponseEntity.ok(Map.of("status", true, "balance", balance));
    }

    /**
     * Raw diagnostic probe — calls Payeelord's balance endpoint and returns
     * the exact HTTP status and body so you can see what Payeelord is saying.
     * Use this when purchase calls return 401 to find out why.
     *
     * <pre>GET /admin/payeelord/diagnose</pre>
     */
    @GetMapping("/diagnose")
    public ResponseEntity<?> diagnose() {
        logger.info("[Admin] Payeelord diagnostic probe triggered");
        return ResponseEntity.ok(gateway.diagnose());
    }
}
