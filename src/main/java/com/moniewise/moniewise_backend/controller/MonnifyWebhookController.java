package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.service.WebhookReplayProtectionService;
import com.moniewise.moniewise_backend.service.WalletService;
import com.moniewise.moniewise_backend.utils.MonnifyUtils;
import com.moniewise.moniewise_backend.utils.WebhookSignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks/monnify")
public class MonnifyWebhookController {

    private final WalletService walletService;
    private final MonnifyUtils monnifyUtils;
    private final WebhookReplayProtectionService replayProtectionService;
    private final Logger logger = LoggerFactory.getLogger(MonnifyWebhookController.class);

    @Value("${monnify.secret-key:}")
    private String monnifySecretKey;

    public MonnifyWebhookController(WalletService walletService, MonnifyUtils monnifyUtils, WebhookReplayProtectionService replayProtectionService) {
        this.walletService = walletService;
        this.monnifyUtils = monnifyUtils;
        this.replayProtectionService = replayProtectionService;
    }

    @PostMapping
    public ResponseEntity<?> handleMonnifyNotification(@RequestBody String payloadJson, @RequestHeader(value = "monnify-signature", required = false) String receivedSignature) {
        // Always enforced, regardless of app.security.dev-mode: this route is public and
        // credits wallets, so an unsigned request must never get through.
        if (monnifySecretKey == null || monnifySecretKey.isBlank()) {
            logger.error("Rejected Monnify webhook — monnify.secret-key is not configured");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        if (receivedSignature == null || receivedSignature.isBlank()) {
            logger.warn("Rejected Monnify webhook with missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String calculatedHash = monnifyUtils.computeHMAC512TransactionHash(payloadJson, monnifySecretKey);
        if (!WebhookSignatures.hexDigestsMatch(calculatedHash, receivedSignature)) {
            logger.warn("Rejected Monnify webhook due to signature mismatch");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!replayProtectionService.registerIfNew("monnify", payloadJson)) {
            logger.warn("Rejected duplicate Monnify webhook event");
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            walletService.fundWalletFromWebhook(payloadJson);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Monnify webhook processing failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}