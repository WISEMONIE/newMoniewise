package com.moniewise.moniewise_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    @Value("${moniewise.reconciliation.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${moniewise.reconciliation.provider-name:RUBIES}")
    private String providerName;

    public ReconciliationScheduler(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${moniewise.reconciliation.scheduler.cron:0 0 2,14 * * ?}", zone = "Africa/Lagos")
    public void runDailyReconciliation() {
        if (!schedulerEnabled) {
            return;
        }
        reconciliationService.runDailyReconciliation(providerName);
    }

    @Scheduled(cron = "${moniewise.reconciliation.revenue-wallet.cron:0 30 6 * * ?}", zone = "Africa/Lagos")
    public void runRevenueWalletReconciliation() {
        if (!schedulerEnabled) {
            return;
        }
        reconciliationService.reconcileRevenueWallet();
    }
}
