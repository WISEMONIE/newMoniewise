package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.service.ExternalTransferSettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * On every application startup, automatically settles any EXT- envelope external
 * transfers that are stuck in {@code PROCESSING} status.
 *
 * <h3>Why this is needed</h3>
 * <p>When the Rubies DR webhook was rejected (HTTP 500 — the duplicate provider_reference
 * bug) Rubies stopped retrying, leaving EXT- TransactionLogs permanently in PROCESSING.
 * After that bug was fixed (V16 migration + EnvelopeService change), new transfers work
 * correctly — but historical stuck records must still be recovered.
 *
 * <p>This runner performs the same work as {@code POST /admin/rubies/settle-stuck} but
 * runs automatically so no manual Postman call is required after each deploy.
 *
 * <h3>What it does on each startup</h3>
 * <ol>
 *   <li>Finds all PROCESSING EXT- TransactionLogs that have a {@code sourceEnvelopeId}
 *       (i.e. envelope external transfers, not wallet withdrawals) and are at least
 *       {@value #MIN_AGE_MINUTES} minutes old (to avoid racing genuinely in-flight
 *       transfers that were initiated seconds before the server restarted).</li>
 *   <li>Calls {@code settleExternalTransfer(ref, "SUCCESS")} for each one, which:
 *       <ul>
 *         <li>Marks the TransactionLog COMPLETED</li>
 *         <li>Releases the envelope's held amount and reduces totalRemainingAmount</li>
 *         <li>Reduces the parent budget's remaining_amount (V18 fix)</li>
 *         <li>Credits the ₦2.25 markup fee to the revenue wallet DB record</li>
 *         <li>Fires {@code collectRubiesMarkupFeeAsync} — the Rubies P2P to transfer
 *             the markup fee to the Wisemonie Rubies revenue account</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>Safety guards</h3>
 * <ul>
 *   <li>Runs {@link Async} so it never delays application startup.</li>
 *   <li>Only touches records older than {@value #MIN_AGE_MINUTES} minutes — transfers
 *       submitted in the last few minutes may still be genuinely in-flight on Rubies.</li>
 *   <li>{@code settleExternalTransfer} is idempotent: already-COMPLETED records are
 *       skipped immediately, so re-running on every startup is safe.</li>
 * </ul>
 */
@Component
public class StartupSettlementRunner {

    private static final Logger logger = LoggerFactory.getLogger(StartupSettlementRunner.class);

    /**
     * Minimum age (minutes) a PROCESSING EXT- transfer must be before it is
     * auto-settled.  Transfers younger than this are likely still genuinely
     * in-flight and should not be forced to SUCCESS prematurely.
     */
    private static final int MIN_AGE_MINUTES = 10;

    private final TransactionLogRepository transactionLogRepository;
    private final ExternalTransferSettlementService settlementService;

    public StartupSettlementRunner(TransactionLogRepository transactionLogRepository,
                                   ExternalTransferSettlementService settlementService) {
        this.transactionLogRepository = transactionLogRepository;
        this.settlementService        = settlementService;
    }

    /**
     * Triggered once the full application context is up and all beans are ready
     * (including Flyway migrations — so V18 will have already synced
     * budget.remaining_amount before this runs).
     *
     * <p>Runs {@link Async} on a separate thread so HTTP port binding and health
     * checks are not delayed.
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void settleStuckTransfersOnStartup() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(MIN_AGE_MINUTES);

        List<TransactionLog> stuck = transactionLogRepository
                .findAll()
                .stream()
                .filter(t -> t.getStatus() == TransactionStatus.PROCESSING
                        && t.getSourceEnvelopeId() != null
                        && t.getReference() != null
                        && !t.getReference().endsWith("-FEE")
                        // Only settle records old enough — avoids racing in-flight transfers
                        && t.getCreatedAt() != null
                        && t.getCreatedAt().isBefore(cutoff))
                .collect(Collectors.toList());

        if (stuck.isEmpty()) {
            logger.info("[StartupSettlement] No stuck PROCESSING envelope transfers found — nothing to do.");
            return;
        }

        logger.info("[StartupSettlement] Found {} stuck PROCESSING envelope transfer(s) older than {} min. " +
                "Auto-settling as SUCCESS …", stuck.size(), MIN_AGE_MINUTES);

        int settled = 0;
        int errors  = 0;

        for (TransactionLog txn : stuck) {
            try {
                settlementService.settleExternalTransfer(txn.getReference(), "SUCCESS");
                settled++;
                logger.info("[StartupSettlement] Settled ref={} userId={}", txn.getReference(), txn.getUserId());
            } catch (Exception e) {
                errors++;
                logger.warn("[StartupSettlement] Could not settle ref={}: {}", txn.getReference(), e.getMessage());
            }
        }

        logger.info("[StartupSettlement] Done. settled={} errors={} (total found={})",
                settled, errors, stuck.size());

        // ── Sweep 2: Retry failed revenue fee collections ─────────────────────
        // Catches COMPLETED transfers whose collectRubiesMarkupFeeAsync previously
        // failed silently (Rubies API blip, revenue account not yet configured, etc.).
        // The settlement sweep above may have just converted some PROCESSING → COMPLETED,
        // but those will have had collectRubiesMarkupFeeAsync called inline, so this
        // sweep mainly catches older COMPLETED transfers with a FAILED/missing REV- log.
        logger.info("[StartupSettlement] Starting sweep 2: retry failed fee collections …");
        try {
            int retried = settlementService.retryFailedFeeCollections();
            logger.info("[StartupSettlement] Sweep 2 complete. retried={}", retried);
        } catch (Exception e) {
            logger.warn("[StartupSettlement] Sweep 2 (fee retry) encountered an error: {}", e.getMessage());
        }
    }
}
