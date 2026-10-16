package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.service.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin endpoints for on-demand wallet↔BaaS reconciliation.
 *
 * <p>The scheduler runs daily against every wallet; these endpoints let ops target a
 * single user immediately (e.g. after a support ticket where the user's BaaS balance
 * doesn't match wallet + envelopes + savings). All endpoints require {@code ROLE_ADMIN}.
 *
 * <h3>Recommended flow</h3>
 * <ol>
 *   <li>{@code GET  /admin/reconciliation/status/{userId}} — see the gap without side effects</li>
 *   <li>{@code POST /admin/reconciliation/dry-run/{userId}} — preview which missed credits would be applied</li>
 *   <li>{@code POST /admin/reconciliation/heal-user/{userId}} — actually apply them (idempotent by paymentReference)</li>
 *   <li>{@code GET  /admin/reconciliation/revenue} — revenue, PayeeLord cost still in the account, and the Rubies balance</li>
 * </ol>
 */
@RestController
@RequestMapping("/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
public class ReconciliationAdminController {

    private static final Logger logger = LoggerFactory.getLogger(ReconciliationAdminController.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationAdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * GET /admin/reconciliation/status/{userId}
     *
     * <p>Read-only — computes wallet + envelopes + savings vs. BaaS balance and reports
     * the gap without applying any credits or recording a reconciliation run.
     */
    @GetMapping("/status/{userId}")
    public ResponseEntity<?> status(@PathVariable Long userId,
                                    @RequestParam(required = false) Integer lookbackDays) {
        Map<String, Object> report = reconciliationService.runOnDemandForUser(
                userId, lookbackDays, true, RubiesGateway.PROVIDER_NAME);
        return ResponseEntity.ok(report);
    }

    /**
     * POST /admin/reconciliation/dry-run/{userId}
     *
     * <p>Same fetch as heal-user but does NOT credit the wallet or persist a heal.
     * Use to preview exactly which Rubies paymentReferences would be applied.
     *
     * <p>Optional query param: {@code lookbackDays} — override the default (30) when the
     * gap is older, e.g. {@code ?lookbackDays=90}.
     */
    @PostMapping("/dry-run/{userId}")
    public ResponseEntity<?> dryRun(@PathVariable Long userId,
                                    @RequestParam(required = false) Integer lookbackDays,
                                    Authentication auth) {
        logger.info("[Admin][Recon] Dry-run for userId={} lookback={} by admin={}",
                userId, lookbackDays, auth != null ? auth.getName() : "unknown");
        Map<String, Object> report = reconciliationService.runOnDemandForUser(
                userId, lookbackDays, true, RubiesGateway.PROVIDER_NAME);
        return ResponseEntity.ok(report);
    }

    /**
     * POST /admin/reconciliation/heal-user/{userId}
     *
     * <p>Runs the full reconciliation for a single user and credits the wallet for any
     * missed Rubies inbound credits whose {@code paymentReference} is not already in
     * {@code transaction_logs}. Idempotent — safe to retry.
     *
     * <p>Optional query params:
     * <ul>
     *   <li>{@code lookbackDays} — how far back to search Rubies (default 30)</li>
     * </ul>
     *
     * <p>Response is a JSON diagnostic report including internal breakdown, BaaS balance,
     * the gap, all unprocessed credits found, and the paymentReferences that were credited.
     */
    @PostMapping("/heal-user/{userId}")
    public ResponseEntity<?> healUser(@PathVariable Long userId,
                                      @RequestParam(required = false) Integer lookbackDays,
                                      Authentication auth) {
        logger.warn("[Admin][Recon] HEAL for userId={} lookback={} by admin={}",
                userId, lookbackDays, auth != null ? auth.getName() : "unknown");
        Map<String, Object> report = reconciliationService.runOnDemandForUser(
                userId, lookbackDays, false, RubiesGateway.PROVIDER_NAME);
        return ResponseEntity.ok(report);
    }

    /**
     * POST /admin/reconciliation/run-daily
     *
     * <p>Manually triggers the full daily reconciliation (all wallets) — the same code
     * path the scheduler runs at 2 AM Lagos. Useful for testing configuration changes
     * without waiting for the next cron cycle.
     */
    /**
     * GET /admin/reconciliation/revenue
     *
     * <p>Read-only split of the revenue account: database revenue, PayeeLord cost
     * still sitting in the account (the amount available to send back), markup
     * not yet in the revenue balance, missing wallet-opening fees, expected
     * Rubies, actual Rubies, and the unexplained remainder. Does not move money.
     */
    @GetMapping("/revenue")
    public ResponseEntity<?> revenuePosition() {
        return ResponseEntity.ok(reconciliationService.reconcileRevenueWallet());
    }

    @PostMapping("/run-daily")
    public ResponseEntity<?> runDaily(@RequestParam(required = false, defaultValue = RubiesGateway.PROVIDER_NAME) String providerName,
                                      Authentication auth) {
        logger.warn("[Admin][Recon] Manual daily-recon triggered provider={} by admin={}",
                providerName, auth != null ? auth.getName() : "unknown");
        reconciliationService.runDailyReconciliation(providerName);
        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "message", "Daily reconciliation started for provider " + providerName
                        + ". Check reconciliation_runs / reconciliation_items for results."
        ));
    }
}
