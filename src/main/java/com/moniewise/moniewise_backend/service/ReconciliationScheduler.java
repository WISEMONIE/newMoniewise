package com.moniewise.moniewise_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final RevenueStatementService revenueStatementService;

    @Value("${moniewise.reconciliation.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${moniewise.reconciliation.provider-name:RUBIES}")
    private String providerName;

    public ReconciliationScheduler(ReconciliationService reconciliationService,
                                   RevenueStatementService revenueStatementService) {
        this.reconciliationService = reconciliationService;
        this.revenueStatementService = revenueStatementService;
    }

    @Scheduled(cron = "${moniewise.reconciliation.scheduler.cron:0 0 2,14 * * ?}", zone = "Africa/Lagos")
    public void runDailyReconciliation() {
        if (!schedulerEnabled) {
            return;
        }
        try {
            reconciliationService.runDailyReconciliation(providerName);
        } catch (RuntimeException ex) {
            logger.error("[Recon] Daily reconciliation failed", ex);
        }
        runRevenueStatement();
    }

    /**
     * 06:30 Lagos. Reads the revenue-account statement, stores a report, and
     * books a known line when statement healing is on.
     */
    @Scheduled(cron = "${moniewise.reconciliation.revenue-wallet.cron:0 30 6 * * ?}", zone = "Africa/Lagos")
    public void runRevenueWalletReconciliation() {
        if (!schedulerEnabled) {
            return;
        }
        runRevenueStatement();
    }

    private void runRevenueStatement() {
        try {
            revenueStatementService.runScheduled();
        } catch (RuntimeException ex) {
            logger.error("[Recon] Revenue statement reconciliation failed", ex);
        }
    }
}
