package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Scheduled fallback that recovers envelope external transfers stuck in PROCESSING.
 *
 * <h3>Why this is needed</h3>
 * <p>When a Rubies NIP transfer is initiated the flow is:
 * <pre>
 *   initiateTransferWithContext() → PROCESSING
 *       → Rubies DR webhook → settleExternalTransferIfExists() → COMPLETED / FAILED
 * </pre>
 * <p>If the webhook is delayed, lost, or rejected (e.g. auth header mismatch, network blip,
 * Rubies stops retrying after a few 401s), the TransactionLog stays PROCESSING forever — the
 * envelope balance is held but never released, and the user sees a stuck transfer.
 *
 * <h3>Recovery logic</h3>
 * <p>Every {@value #POLL_INTERVAL_MS} ms this job finds all PROCESSING
 * {@code ENVELOPE_TO_EXTERNAL} logs whose {@code created_at} is older than
 * {@value #STALE_AFTER_MINUTES} minutes (allowing the normal webhook path to win first).
 * For each, it issues a Rubies TSQ (Transaction Status Query) against the stored
 * {@code providerReference} (NIP session ID) and delegates settlement to
 * {@link ExternalTransferSettlementService#settleExternalTransfer}, which is idempotent.
 */
@Component
public class ProcessingTransferRecoveryScheduler {

    private static final Logger logger =
            LoggerFactory.getLogger(ProcessingTransferRecoveryScheduler.class);

    /** Give the webhook this long to arrive before polling TSQ (milliseconds → minutes). */
    static final int STALE_AFTER_MINUTES = 3;

    /** How often the job runs, in milliseconds. */
    static final long POLL_INTERVAL_MS = 3L * 60 * 1000; // 3 minutes

    private final TransactionLogRepository transactionLogRepository;
    private final ExternalTransferSettlementService externalTransferSettlementService;
    private final PaymentGatewayResolver paymentGatewayResolver;

    public ProcessingTransferRecoveryScheduler(
            TransactionLogRepository transactionLogRepository,
            ExternalTransferSettlementService externalTransferSettlementService,
            PaymentGatewayResolver paymentGatewayResolver
    ) {
        this.transactionLogRepository       = transactionLogRepository;
        this.externalTransferSettlementService = externalTransferSettlementService;
        this.paymentGatewayResolver         = paymentGatewayResolver;
    }

    // ── Scheduled entry point ─────────────────────────────────────────────────

    /**
     * Runs every {@value #POLL_INTERVAL_MS} ms after the previous execution completes.
     * {@code fixedDelay} (not {@code fixedRate}) is used intentionally so that slow TSQ
     * responses don't cause concurrent executions if the job takes longer than the interval.
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void recoverStuckTransfers() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_AFTER_MINUTES);

        List<TransactionLog> stuck =
                transactionLogRepository.findProcessingEnvelopeExternalTransfers(cutoff);

        if (stuck.isEmpty()) {
            return; // nothing to do — avoid noisy logs on quiet servers
        }

        logger.info("[TransferRecovery] {} PROCESSING envelope transfer(s) older than {} min — polling Rubies TSQ",
                stuck.size(), STALE_AFTER_MINUTES);

        PaymentGateway gateway;
        try {
            gateway = paymentGatewayResolver.resolveByProviderName(RubiesGateway.PROVIDER_NAME);
        } catch (Exception e) {
            logger.error("[TransferRecovery] Cannot resolve Rubies gateway — aborting recovery run: {}",
                    e.getMessage());
            return;
        }

        for (TransactionLog txn : stuck) {
            try {
                recoverSingle(gateway, txn);
            } catch (Exception e) {
                // Log and continue — one bad record must not abort the whole batch.
                logger.warn("[TransferRecovery] Error recovering ref={}: {}",
                        txn.getReference(), e.getMessage(), e);
            }
        }
    }

    // ── Immediate async poll (called right after transfer initiation) ─────────

    /**
     * Polls Rubies TSQ a few times immediately after a transfer is submitted so
     * that the status resolves to COMPLETED / FAILED within seconds rather than
     * waiting for the next 3-minute scheduler cycle.
     *
     * <p>Rubies does NOT send DR webhooks for outbound NIP transfers — TSQ is the
     * only settlement path.  We retry with increasing delays:
     * <ol>
     *   <li>5 s  — most Rubies NIP transfers resolve this quickly</li>
     *   <li>10 s — covers slightly slower NIP routes</li>
     *   <li>20 s — last fast attempt before handing off to the 3-min scheduler</li>
     * </ol>
     *
     * <p>This method is {@code @Async} so it runs in the Spring task-executor
     * thread pool and never blocks the HTTP response to the user.
     *
     * @param reference       our EXT- reference (used to call settleExternalTransfer)
     * @param providerReference Rubies NIP session ID returned at initiation (used for TSQ)
     */
    @Async
    public void scheduleImmediateRecovery(String reference, String providerReference) {
        int[] delaysMs = {5_000, 10_000, 20_000};

        PaymentGateway gateway;
        try {
            gateway = paymentGatewayResolver.resolveByProviderName(RubiesGateway.PROVIDER_NAME);
        } catch (Exception e) {
            logger.warn("[TransferRecovery] Cannot resolve Rubies gateway for immediate check ref={}: {}",
                    reference, e.getMessage());
            return;
        }

        for (int delay : delaysMs) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }

            Optional<String> statusOpt = gateway.fetchTransactionStatus(providerReference);
            if (statusOpt.isEmpty()) {
                logger.debug("[TransferRecovery] TSQ empty (immediate) ref={} — retrying", reference);
                continue;
            }

            String s = statusOpt.get();
            logger.info("[TransferRecovery] TSQ immediate ref={} → {}", reference, s);

            if ("SUCCESS".equalsIgnoreCase(s) || "FAILED".equalsIgnoreCase(s)) {
                try {
                    externalTransferSettlementService.settleExternalTransfer(reference, s);
                    logger.info("[TransferRecovery] Immediate settlement complete ref={} status={}", reference, s);
                } catch (Exception e) {
                    logger.warn("[TransferRecovery] Immediate settlement error ref={}: {}", reference, e.getMessage(), e);
                }
                return; // done — no need for scheduler to retry
            }
            // PENDING — try again after next delay
        }

        logger.info("[TransferRecovery] ref={} still PENDING after immediate retries — 3-min scheduler will handle it",
                reference);
    }

    // ── Per-record recovery ───────────────────────────────────────────────────

    private void recoverSingle(PaymentGateway gateway, TransactionLog txn) {
        String ref         = txn.getReference();
        String providerRef = txn.getProviderReference();

        logger.debug("[TransferRecovery] TSQ ref={} providerRef={}", ref, providerRef);

        Optional<String> tsqResult = gateway.fetchTransactionStatus(providerRef);

        if (tsqResult.isEmpty()) {
            logger.warn("[TransferRecovery] TSQ returned empty for ref={} provRef={} — will retry next cycle",
                    ref, providerRef);
            return;
        }

        String status = tsqResult.get();
        logger.info("[TransferRecovery] TSQ ref={} → {}", ref, status);

        if ("SUCCESS".equalsIgnoreCase(status)) {
            externalTransferSettlementService.settleExternalTransfer(ref, "SUCCESS");
            logger.info("[TransferRecovery] Settled SUCCESS: ref={}", ref);

        } else if ("FAILED".equalsIgnoreCase(status)) {
            externalTransferSettlementService.settleExternalTransfer(ref, "FAILED");
            logger.info("[TransferRecovery] Settled FAILED: ref={}", ref);

        } else {
            // PENDING — Rubies hasn't finalised the NIP yet; leave PROCESSING and retry.
            logger.info("[TransferRecovery] TSQ status={} for ref={} — still pending, will retry next cycle",
                    status, ref);
        }
    }
}
