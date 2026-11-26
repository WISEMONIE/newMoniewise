package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.PayeelordVasTransaction;
import com.moniewise.moniewise_backend.enums.VasTransactionStatus;
import com.moniewise.moniewise_backend.repository.PayeelordVasTransactionRepository;
import com.moniewise.moniewise_backend.utils.ExceptionClassifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled safety net that recovers airtime/data purchases stuck in {@code PENDING}.
 *
 * <h3>Why this is needed</h3>
 * <p>Since {@link PayeelordVasService#purchaseAirtime}/{@code purchaseData} return
 * {@code PENDING} immediately and complete the (slow) Payeelord delivery on a background
 * thread, a purchase can be orphaned if that thread never finishes — e.g. the server is
 * redeployed/restarted mid-delivery. The envelope hold was placed but is never settled or
 * released, leaving the user's money stuck and the row {@code PENDING} forever.
 *
 * <h3>What it does</h3>
 * <p>Every configured recovery tick it finds purchases still {@code PENDING} more than
 * {@value #STALE_AFTER_MINUTES} minutes after creation (far past the ~45s normal path) and
 * hands each to {@link PayeelordVasService#recoverStalePurchase}, which resolves it
 * conservatively: auto-refund only ones Payeelord clearly never registered, escalate the
 * rest for manual reconciliation (Payeelord has no status re-query endpoint, so a delivered
 * purchase can't be auto-confirmed).
 */
@Component
public class VasPurchaseRecoveryScheduler {

    private static final Logger logger =
            LoggerFactory.getLogger(VasPurchaseRecoveryScheduler.class);

    /** Normal completion is ~45s; give a wide margin before treating a PENDING as stuck. */
    static final int STALE_AFTER_MINUTES = 30;

    private static final String RECOVERY_ESCALATED_MARKER = "[RECOVERY_ESCALATED]";

    @Value("${moniewise.vas.recovery.batch-size:25}")
    private int recoveryBatchSize;

    private final PayeelordVasTransactionRepository transactionRepository;
    private final PayeelordVasService vasService;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public VasPurchaseRecoveryScheduler(PayeelordVasTransactionRepository transactionRepository,
                                        PayeelordVasService vasService) {
        this.transactionRepository = transactionRepository;
        this.vasService = vasService;
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        shuttingDown.set(true);
        logger.info("[VasRecovery] Application shutdown detected; stale purchase recovery will stop accepting work.");
    }

    /**
     * {@code fixedDelay} (not {@code fixedRate}) so a slow batch never overlaps itself.
     */
    @Scheduled(fixedDelayString = "${moniewise.vas.recovery.fixed-delay-ms:300000}")
    public void recoverStalePurchases() {
        if (isShutdownInProgress()) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_AFTER_MINUTES);

        List<PayeelordVasTransaction> stale;
        try {
            stale = transactionRepository.findStalePendingForRecovery(
                    VasTransactionStatus.PENDING,
                    cutoff,
                    RECOVERY_ESCALATED_MARKER,
                    PageRequest.of(0, boundedBatchSize(recoveryBatchSize)));
        } catch (Exception e) {
            if (isShutdownRelated(e)) {
                logger.warn("[VasRecovery] Skipping stale purchase recovery during shutdown: {}",
                        rootCauseMessage(e));
                restoreInterruptIfNeeded(e);
                return;
            }
            if (ExceptionClassifier.isDatabasePoolExhausted(e)) {
                logger.warn("[VasRecovery] DB pool busy; skipping stale purchase recovery this round: {}",
                        ExceptionClassifier.rootCauseMessage(e));
                return;
            }
            throw e;
        }

        if (stale.isEmpty()) {
            return; // nothing to do — keep quiet servers quiet
        }

        logger.info("[VasRecovery] {} PENDING VAS purchase(s) older than {} min — attempting recovery",
                stale.size(), STALE_AFTER_MINUTES);

        for (PayeelordVasTransaction txn : stale) {
            if (isShutdownInProgress()) {
                logger.warn("[VasRecovery] Stopped stale purchase recovery because application is shutting down");
                return;
            }

            try {
                vasService.recoverStalePurchase(txn.getId());
            } catch (Exception e) {
                if (isShutdownRelated(e)) {
                    logger.warn("[VasRecovery] Stopped stale purchase recovery during shutdown after ref={}: {}",
                            txn.getReference(), rootCauseMessage(e));
                    restoreInterruptIfNeeded(e);
                    return;
                }
                if (ExceptionClassifier.isDatabasePoolExhausted(e)) {
                    logger.warn("[VasRecovery] DB pool busy while recovering ref={}; stopping this round: {}",
                            txn.getReference(), ExceptionClassifier.rootCauseMessage(e));
                    return;
                }

                // One bad record must not abort the whole batch.
                logger.warn("[VasRecovery] Error recovering ref={}: {}",
                        txn.getReference(), e.getMessage(), e);
            }
        }
    }

    private boolean isShutdownInProgress() {
        return shuttingDown.get() || Thread.currentThread().isInterrupted();
    }

    private int boundedBatchSize(int configured) {
        return Math.max(1, Math.min(configured, 100));
    }

    private boolean isShutdownRelated(Throwable throwable) {
        if (isShutdownInProgress()) {
            return true;
        }

        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("RedisCommandInterruptedException")
                    || className.contains("TaskRejectedException")
                    || containsIgnoreCase(message, "LettuceConnectionFactory was destroyed")
                    || containsIgnoreCase(message, "BeanFactory not initialized or already closed")
                    || containsIgnoreCase(message, "ApplicationContext has been closed")
                    || containsIgnoreCase(message, "ApplicationContext is closed")
                    || containsIgnoreCase(message, "Connection pool shut down")
                    || containsIgnoreCase(message, "Connection is closed")
                    || containsIgnoreCase(message, "This connection has been closed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void restoreInterruptIfNeeded(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException
                    || current.getClass().getName().contains("RedisCommandInterruptedException")) {
                Thread.currentThread().interrupt();
                return;
            }
            current = current.getCause();
        }
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null
                && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root != null && root.getCause() != null) {
            root = root.getCause();
        }
        if (root == null) {
            return "unknown";
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return message;
    }
}
