package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.BudgetFeeBackfillService;
import com.moniewise.moniewise_backend.service.ExternalTransferSettlementService;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Admin-only endpoints for Rubies MFB operations that cannot be done via the Rubies dashboard.
 *
 * <p>All endpoints require {@code ROLE_ADMIN}.
 *
 * <h3>One-time setup sequence</h3>
 * <ol>
 *   <li>{@code GET  /admin/rubies/revenue-wallet}       — check if already configured</li>
 *   <li>{@code POST /admin/rubies/setup-revenue-wallet} — create the wallet on Rubies</li>
 *   <li>Done — all subsequent markup fees route to this wallet automatically</li>
 * </ol>
 */
@RestController
@RequestMapping("/admin/rubies")
@PreAuthorize("hasRole('ADMIN')")
public class RubiesAdminController {

    private static final Logger logger = LoggerFactory.getLogger(RubiesAdminController.class);

    private final RubiesGateway rubiesGateway;
    private final SystemConfigService systemConfig;
    private final WalletRepository walletRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final ExternalTransferSettlementService settlementService;
    private final BudgetFeeBackfillService budgetFeeBackfillService;

    public RubiesAdminController(RubiesGateway rubiesGateway,
                                 SystemConfigService systemConfig,
                                 WalletRepository walletRepository,
                                 TransactionLogRepository transactionLogRepository,
                                 ExternalTransferSettlementService settlementService,
                                 BudgetFeeBackfillService budgetFeeBackfillService) {
        this.rubiesGateway            = rubiesGateway;
        this.systemConfig             = systemConfig;
        this.walletRepository         = walletRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.settlementService        = settlementService;
        this.budgetFeeBackfillService = budgetFeeBackfillService;
    }

    /**
     * GET /admin/rubies/revenue-wallet
     *
     * <p>Returns the currently configured Moniewise Rubies revenue wallet, or a
     * NOT_CONFIGURED status if {@code setup-revenue-wallet} has not been called yet.
     *
     * <p>Postman: no body needed.
     */
    @GetMapping("/revenue-wallet")
    public ResponseEntity<?> getRevenueWallet() {
        String accountNumber = systemConfig.getString(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER);
        String accountName   = systemConfig.getString(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME);

        if (accountNumber == null || accountNumber.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "status",  false,
                    "message", "Rubies revenue wallet not yet configured. " +
                               "Call POST /admin/rubies/setup-revenue-wallet to create it."
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status",        true,
                "accountNumber", accountNumber,
                "accountName",   accountName != null ? accountName : "",
                "bank",          "Rubies MFB",
                "bankCode",      "090175",
                "message",       "Revenue wallet is active. All markup fees are routed here."
        ));
    }

    /**
     * POST /admin/rubies/setup-revenue-wallet
     *
     * <p>Calls the Rubies BaaS API to create Moniewise's own revenue wallet, then
     * saves the returned account number into {@code system_config} so the fee-routing
     * logic picks it up immediately (no restart needed).
     *
     * <p>This is idempotent — if a wallet is already configured it returns the existing
     * details without creating a new one.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "bvn":         "12345678901",       ← company director / authorised rep BVN
     *   "firstName":   "Moniewise",
     *   "lastName":    "Technologies",
     *   "email":       "revenue@moniewise.com",
     *   "phone":       "08012345678",
     *   "dateOfBirth": "1990-01-01",         ← YYYY-MM-DD, must match BVN record
     *   "displayName": "MONIEWISE TECHNOLOGIES"  ← optional, shown in transfer narrations
     * }
     * </pre>
     *
     * <p>Response (success):
     * <pre>
     * {
     *   "status":        true,
     *   "accountNumber": "7012345678",
     *   "accountName":   "MONIEWISE TECHNOLOGIES",
     *   "bank":          "Rubies MFB",
     *   "message":       "Revenue wallet created and configured successfully."
     * }
     * </pre>
     */
    @PostMapping("/setup-revenue-wallet")
    public synchronized ResponseEntity<?> setupRevenueWallet(@RequestBody Map<String, String> body) {

        // ── Guard: already configured? (re-check inside synchronized to prevent TOCTOU race) ──
        // Without synchronized, two concurrent admin requests could both pass the null check,
        // both call createMerchantWallet, and create two orphaned wallets on Rubies.
        String existing = systemConfig.getString(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER);
        if (existing != null && !existing.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "status",        true,
                    "accountNumber", existing,
                    "accountName",   systemConfig.getString(
                            SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME, ""),
                    "bank",          "Rubies MFB",
                    "message",       "Revenue wallet already configured — no new wallet created."
            ));
        }

        // ── Validate required fields ──────────────────────────────────────────
        String bvn         = body.get("bvn");
        String firstName   = body.get("firstName");
        String lastName    = body.get("lastName");
        String email       = body.get("email");
        String phone       = body.get("phone");
        String dob         = body.get("dateOfBirth");
        String displayName = body.getOrDefault("displayName", "MONIEWISE TECHNOLOGIES");

        if (isBlank(bvn) || isBlank(firstName) || isBlank(lastName)
                || isBlank(email) || isBlank(phone) || isBlank(dob)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", false,
                    "error",  "bvn, firstName, lastName, email, phone, and dateOfBirth are all required."
            ));
        }

        // ── Call Rubies API ───────────────────────────────────────────────────
        try {
            Map<String, String> result = rubiesGateway.createMerchantWallet(
                    bvn, firstName, lastName, email, phone, dob);

            String accountNumber = result.get("accountNumber");
            String accountName   = result.getOrDefault("accountName", displayName);

            // Guard: Rubies returned success but no account number — do NOT save blank
            // to system_config or the idempotency guard will think it's already configured
            // and block every future attempt.
            if (isBlank(accountNumber)) {
                logger.error("[Admin] Rubies createMerchantWallet returned success but accountNumber is blank — not saving");
                return ResponseEntity.internalServerError().body(Map.of(
                        "status", false,
                        "error",  "Rubies returned a success response but did not include an account number. " +
                                  "Check the Rubies API response format and try again."
                ));
            }

            // Persist to system_config — takes effect immediately (Redis cache evicted by set())
            systemConfig.set(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER,
                    accountNumber,
                    "Moniewise Rubies revenue wallet — auto-created via admin API"
            );
            String savedName = accountName != null ? accountName : displayName;
            systemConfig.set(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME,
                    savedName,
                    "Display name for Moniewise Rubies revenue wallet"
            );

            // Also link the DB revenue wallet record so it appears in wallet queries.
            linkRevenueWalletRecord(accountNumber, savedName);

            logger.info("[Admin] Rubies revenue wallet created and saved: acct={} name={}",
                    accountNumber, accountName);

            return ResponseEntity.ok(Map.of(
                    "status",        true,
                    "accountNumber", accountNumber,
                    "accountName",   accountName != null ? accountName : displayName,
                    "customerId",    result.getOrDefault("customerId", ""),
                    "bank",          "Rubies MFB",
                    "bankCode",      "090175",
                    "message",       "Revenue wallet created and configured successfully. " +
                                     "All future markup fees will route here automatically."
            ));

        } catch (RuntimeException e) {
            logger.error("[Admin] Failed to create Rubies revenue wallet: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", false,
                    "error",  e.getMessage()
            ));
        }
    }

    /**
     * POST /admin/rubies/register-revenue-wallet
     *
     * <p>Links Moniewise's <em>existing</em> Rubies MFB business account (the one
     * created when you signed up on the Rubies dashboard) to this server's DB and
     * system_config — <strong>no new wallet is created on Rubies</strong>.
     *
     * <p>Use this instead of {@code setup-revenue-wallet} when you already have a
     * Rubies account and just need to tell the backend where to route the markup fee.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "accountNumber": "7012345678",              ← your existing Rubies account number
     *   "accountName":   "MONIEWISE TECHNOLOGIES"   ← optional display name
     * }
     * </pre>
     *
     * <p>Effect:
     * <ol>
     *   <li>Saves the account number to {@code system_config} — the fee-routing logic
     *       picks this up within 5 seconds (Redis TTL).</li>
     *   <li>Sets {@code providerWalletRef} on the internal revenue-wallet DB record
     *       so the wallet is visible in admin queries and reconciliation runs.</li>
     * </ol>
     *
     * <p>This endpoint is idempotent — calling it again with the same number is safe.
     */
    @PostMapping("/register-revenue-wallet")
    @Transactional
    public synchronized ResponseEntity<?> registerRevenueWallet(@RequestBody Map<String, String> body) {

        String accountNumber = body.get("accountNumber");
        String accountName   = body.getOrDefault("accountName", "MONIEWISE TECHNOLOGIES");

        if (isBlank(accountNumber)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", false,
                    "error",  "accountNumber is required."
            ));
        }

        // Persist to system_config (evicts Redis cache immediately)
        systemConfig.set(
                SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER,
                accountNumber,
                "Moniewise Rubies revenue wallet — registered via admin API (existing account)"
        );
        systemConfig.set(
                SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME,
                accountName,
                "Display name for Moniewise Rubies revenue wallet"
        );

        // Link the DB revenue wallet record
        boolean dbLinked = linkRevenueWalletRecord(accountNumber, accountName);

        logger.info("[Admin] Existing Rubies revenue wallet registered: acct={} name={} dbLinked={}",
                accountNumber, accountName, dbLinked);

        return ResponseEntity.ok(Map.of(
                "status",        true,
                "accountNumber", accountNumber,
                "accountName",   accountName,
                "bank",          "Rubies MFB",
                "bankCode",      "090175",
                "dbLinked",      dbLinked,
                "message",       "Revenue wallet registered successfully. " +
                                 "All future markup fees will route here. " +
                                 (dbLinked
                                  ? "DB revenue wallet record updated."
                                  : "Note: no DB revenue wallet record found — system_config updated only.")
        ));
    }

    /**
     * DELETE /admin/rubies/revenue-wallet
     *
     * <p>Clears the configured revenue wallet from system_config. Use this if you need
     * to re-create the wallet with different details. Markup fees will fall back to
     * internal DB tracking only until a new wallet is configured.
     */
    @DeleteMapping("/revenue-wallet")
    public ResponseEntity<?> clearRevenueWallet(Authentication authentication) {
        String adminEmail = authentication != null ? authentication.getName() : "unknown";
        String auditNote  = "Cleared by admin: " + adminEmail;

        systemConfig.set(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER, "", auditNote);
        systemConfig.set(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME,   "", auditNote);

        // Warn loudly — this stops all live fee routing until re-configured
        logger.warn("[Admin] ⚠️  Rubies revenue wallet configuration CLEARED by admin={}. " +
                "Markup fees will only be tracked internally until a new wallet is set up.", adminEmail);

        return ResponseEntity.ok(Map.of(
                "status",  true,
                "message", "Revenue wallet cleared. Fees will track internally until re-configured."
        ));
    }

    /**
     * Finds the internal revenue-wallet DB record (flagged {@code is_revenue_wallet = true})
     * and sets its {@code providerWalletRef} and {@code accountNumber} to the given
     * Rubies account number so it appears in admin wallet queries and reconciliation.
     *
     * <p>This does NOT create a new wallet — it only updates the existing revenue-wallet
     * record that was seeded at platform setup time.
     *
     * @return {@code true} if the record was found and updated; {@code false} if no
     *         revenue-wallet record exists yet (system_config still gets updated).
     */
    private boolean linkRevenueWalletRecord(String accountNumber, String accountName) {
        return walletRepository.findByRevenueWalletTrue()
                .map(revenueWallet -> {
                    revenueWallet.setProviderWalletRef(accountNumber);
                    revenueWallet.setAccountNumber(accountNumber);
                    revenueWallet.setProviderName(RubiesGateway.PROVIDER_NAME);
                    revenueWallet.setBankName("Rubies MFB");
                    revenueWallet.setUpdatedAt(LocalDateTime.now());
                    walletRepository.save(revenueWallet);
                    logger.info("[Admin] Revenue wallet DB record linked: walletId={} acct={}",
                            revenueWallet.getId(), accountNumber);
                    return true;
                })
                .orElseGet(() -> {
                    logger.warn("[Admin] findByRevenueWalletTrue() returned empty — " +
                            "system_config updated but DB wallet record not linked. " +
                            "Ensure a wallet row exists with is_revenue_wallet=true.");
                    return false;
                });
    }

    // ── API key management ────────────────────────────────────────────────────

    /**
     * POST /admin/rubies/update-api-key
     *
     * <p>Hot-reloads the Rubies JWT without restarting the server.  Use this when the
     * token expires (Rubies error code 22) and you have a fresh JWT from the Rubies
     * dashboard.  The new key takes effect immediately for all subsequent API calls.
     *
     * <p>Request body:
     * <pre>{ "apiKey": "eyJhbGci..." }</pre>
     *
     * <p>For permanent fix also update the {@code RUBIES_API_KEY} env var on Render
     * so the new token survives a redeploy.
     */
    @PostMapping("/update-api-key")
    public ResponseEntity<?> updateApiKey(@RequestBody Map<String, String> body) {
        String newKey = body.get("apiKey");
        if (newKey == null || newKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", false,
                    "error",  "apiKey is required in request body."
            ));
        }
        rubiesGateway.updateApiKey(newKey);
        logger.info("[Admin] Rubies API key hot-reloaded via admin endpoint.");
        return ResponseEntity.ok(Map.of(
                "status",  true,
                "message", "Rubies API key updated in memory. All subsequent calls will use the new token. " +
                           "Also update RUBIES_API_KEY on Render to persist across redeploys."
        ));
    }

    // ── Manual settlement endpoints ────────────────────────────────────────────

    /**
     * POST /admin/rubies/settle/{reference}
     *
     * <p>Manually settles a single envelope external transfer that is stuck in
     * {@code PROCESSING} status — typically because the Rubies DR webhook was
     * rejected (HTTP 500) due to the duplicate {@code provider_reference} bug
     * and Rubies stopped retrying.
     *
     * <p>Safe to call multiple times — settlement is idempotent (already-COMPLETED
     * or already-FAILED records are skipped).
     *
     * <p>Example: {@code POST /admin/rubies/settle/EXT-414693cd-5d01-44b9-b4eb-86c5acf23bc8}
     */
    @PostMapping("/settle/{reference}")
    public ResponseEntity<?> settleTransfer(@PathVariable String reference) {
        if (isBlank(reference)) {
            return ResponseEntity.badRequest().body(Map.of("error", "reference is required"));
        }

        TransactionLog txn = transactionLogRepository.findByReference(reference).orElse(null);
        if (txn == null) {
            return ResponseEntity.notFound().build();
        }

        if (txn.getSourceEnvelopeId() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Transaction is not an envelope external transfer (sourceEnvelopeId is null)"
            ));
        }

        String previousStatus = txn.getStatus().name();
        settlementService.settleExternalTransfer(reference, "SUCCESS");

        // Re-load to get updated status
        TransactionLog updated = transactionLogRepository.findByReference(reference).orElse(txn);
        logger.info("[Admin] Manual settlement: ref={} {} → {}", reference, previousStatus, updated.getStatus());

        return ResponseEntity.ok(Map.of(
                "reference",     reference,
                "previousStatus", previousStatus,
                "newStatus",     updated.getStatus().name(),
                "message",       previousStatus.equals(updated.getStatus().name())
                                 ? "Already settled — no change."
                                 : "Settled successfully."
        ));
    }

    /**
     * POST /admin/rubies/settle-stuck
     *
     * <p>Batch-settles ALL envelope external transfers that are stuck in
     * {@code PROCESSING} status.  Calls {@code settleExternalTransfer(ref, "SUCCESS")}
     * for each one.
     *
     * <p>Use this after deploying the provider_reference duplicate-fix to clean up
     * all the EXT- records that were left stuck because the DR webhook kept returning
     * HTTP 500 (IncorrectResultSizeDataAccessException on the FEE companion log) and
     * Rubies eventually stopped retrying.
     *
     * <p>Only settles transfers confirmed on the Rubies dashboard (i.e. call this
     * after you have visually confirmed the transfers succeeded on Rubies' side).
     */
    @PostMapping("/settle-stuck")
    public ResponseEntity<?> settleStuckTransfers() {
        // Find all PROCESSING envelope external transfers
        java.util.List<TransactionLog> stuck = transactionLogRepository
                .findAll()
                .stream()
                .filter(t -> t.getStatus() == TransactionStatus.PROCESSING
                        && t.getSourceEnvelopeId() != null
                        && t.getReference() != null
                        && !t.getReference().endsWith("-FEE"))
                .collect(java.util.stream.Collectors.toList());

        int settled = 0;
        int skipped = 0;
        java.util.List<String> settledRefs = new java.util.ArrayList<>();
        java.util.List<String> errorRefs   = new java.util.ArrayList<>();

        for (TransactionLog txn : stuck) {
            try {
                settlementService.settleExternalTransfer(txn.getReference(), "SUCCESS");
                settledRefs.add(txn.getReference());
                settled++;
                logger.info("[Admin] Batch-settled stuck transfer: ref={}", txn.getReference());
            } catch (Exception e) {
                errorRefs.add(txn.getReference() + " (" + e.getMessage() + ")");
                skipped++;
                logger.warn("[Admin] Could not settle ref={}: {}", txn.getReference(), e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of(
                "totalFound",  stuck.size(),
                "settled",     settled,
                "errors",      skipped,
                "settledRefs", settledRefs,
                "errorRefs",   errorRefs,
                "message",     settled + " transfer(s) settled, " + skipped + " error(s)."
        ));
    }

    /**
     * POST /admin/rubies/backfill-budget-fees
     *
     * <p>Backfills historical budget creation fees that were deducted internally
     * but never physically swept from users' Rubies wallets into the Moniewise
     * Rubies revenue wallet.
     *
     * <p>Dry-run is the default. Call with {@code dryRun=false} only after checking
     * the preview response.
     *
     * <p>Examples:
     * <pre>
     * POST /admin/rubies/backfill-budget-fees
     * POST /admin/rubies/backfill-budget-fees?dryRun=false&limit=100
     * POST /admin/rubies/backfill-budget-fees?dryRun=false&budgetId=42
     * POST /admin/rubies/backfill-budget-fees?dryRun=false&userId=102
     * </pre>
     */
    @PostMapping("/backfill-budget-fees")
    public ResponseEntity<?> backfillBudgetFees(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long budgetId,
            @RequestParam(defaultValue = "true") boolean retryFailed) {

        return ResponseEntity.ok(budgetFeeBackfillService.backfillMissingRubiesBudgetFees(
                dryRun,
                limit,
                userId,
                budgetId,
                retryFailed));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
