package com.moniewise.moniewise_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WalletWebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WalletWebhookService.class);
    private final WalletService walletService;

    public WalletWebhookService(WalletService walletService) {
        this.walletService = walletService;
    }

    public void processFundingWebhook(String payloadJson) {
        try {
            logger.info("Delegating validated funding webhook to WalletService");
            walletService.fundWalletFromWebhook(payloadJson);
        } catch (Exception e) {
            logger.error("Wallet webhook processing failed", e);
            throw new RuntimeException("Webhook processing failed", e);
        }
    }

    /** Handles a Providus deposit / credit event. */
    public void processProvidusDepositWebhook(String payloadJson) {
        try {
            logger.info("[PROVIDUS-WEBHOOK] Delegating deposit event to WalletService");
            walletService.fundWalletFromProvidusWebhook(payloadJson);
        } catch (Exception e) {
            logger.error("[PROVIDUS-WEBHOOK] Deposit processing failed", e);
            throw new RuntimeException("Providus deposit webhook processing failed", e);
        }
    }

    /**
     * Handles a Providus transfer-success or transfer-failed event.
     *
     * @param isSuccess {@code true} → mark withdrawal COMPLETED,
     *                  {@code false} → mark withdrawal FAILED and reverse balance
     */
    public void processProvidusWithdrawalWebhook(String payloadJson, boolean isSuccess) {
        try {
            logger.info("[PROVIDUS-WEBHOOK] Delegating withdrawal confirmation (success={}) to WalletService", isSuccess);
            walletService.processProvidusWithdrawalConfirmation(payloadJson, isSuccess);
        } catch (Exception e) {
            logger.error("[PROVIDUS-WEBHOOK] Withdrawal confirmation processing failed", e);
            throw new RuntimeException("Providus withdrawal webhook processing failed", e);
        }
    }

    // ── Rubies ────────────────────────────────────────────────────────────────

    /**
     * Handles a Rubies inbound credit (someone sent money to the user's Rubies account).
     */
    public void processRubiesDepositWebhook(String payloadJson) {
        try {
            logger.info("[RUBIES-WEBHOOK] Delegating deposit event to WalletService");
            walletService.processRubiesDepositWebhook(payloadJson);
        } catch (Exception e) {
            logger.error("[RUBIES-WEBHOOK] Deposit processing failed", e);
            throw new RuntimeException("Rubies deposit webhook processing failed", e);
        }
    }

    /**
     * Handles a Rubies outbound transfer confirmation (success or failure).
     *
     * @param isSuccess {@code true} → mark withdrawal COMPLETED,
     *                  {@code false} → mark withdrawal FAILED and reverse balance
     */
    public void processRubiesWithdrawalWebhook(String payloadJson, boolean isSuccess) {
        try {
            logger.info("[RUBIES-WEBHOOK] Delegating withdrawal confirmation (success={}) to WalletService", isSuccess);
            walletService.processRubiesWithdrawalConfirmation(payloadJson, isSuccess);
        } catch (Exception e) {
            logger.error("[RUBIES-WEBHOOK] Withdrawal confirmation processing failed", e);
            throw new RuntimeException("Rubies withdrawal webhook processing failed", e);
        }
    }
}
