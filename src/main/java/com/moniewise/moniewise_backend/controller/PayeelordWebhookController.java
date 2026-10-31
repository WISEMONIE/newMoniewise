package com.moniewise.moniewise_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.psp.payeelord.PayeelordGateway;
import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordWebhookPayload;
import com.moniewise.moniewise_backend.service.PayeelordVasService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Payeelord transaction-webhook receiver — lives alongside {@link WebhookController}
 * in the {@code /api/webhooks} family but is split into its own class since it backs
 * a self-contained VAS slice (mirrors how Rubies/Providus/SecureWave share one
 * controller while having very different downstream processing).
 *
 * <h3>This is an audit trail, not a settlement signal</h3>
 * Payeelord's airtime/data purchase endpoints respond <b>synchronously</b> —
 * {@code PayeelordVasService#purchaseAirtime}/{@code purchaseData} already drove the
 * transaction to its terminal (or pending-ambiguous) state from that HTTP response
 * before this webhook could possibly arrive. Payeelord's own docs describe the
 * webhook firing "only after the purchase endpoint returns its JSON response".
 * So this endpoint exists purely to stamp a {@code webhookConfirmedAt} audit marker
 * — see {@link PayeelordVasService#recordWebhookConfirmation}.
 *
 * <h3>Signature header — name UNCONFIRMED, fails open</h3>
 * Payeelord's documentation confirms only that "webhook signatures use your current
 * API key as the HMAC secret" — it does not name the carrier header or hash
 * algorithm. Mirroring the Providus precedent (whose header was equally
 * undocumented at integration time, see {@link WebhookController#handleProvidusWebhook}),
 * this endpoint accepts several plausible candidates and forwards whichever is
 * non-blank to {@link PayeelordGateway#validateWebhookSignature}, which fails
 * closed (rejects) when the signature is missing or does not match.
 *
 * <p>The endpoint always returns {@code 200 OK} once the signature check passes —
 * including when downstream processing throws — so Payeelord does not retry
 * indefinitely; a bad signature returns {@code 401} so Payeelord knows the
 * delivery was received but rejected.
 *
 * <p><b>Give Payeelord this URL:</b>
 * {@code https://your-render-domain.onrender.com/api/webhooks/payeelord}
 */
@RestController
@RequestMapping("/api/webhooks")
public class PayeelordWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(PayeelordWebhookController.class);

    private final PayeelordGateway gateway;
    private final PayeelordVasService vasService;
    private final ObjectMapper objectMapper;

    public PayeelordWebhookController(PayeelordGateway gateway,
                                      PayeelordVasService vasService,
                                      ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.vasService = vasService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/payeelord")
    public ResponseEntity<String> handlePayeelordWebhook(
            @RequestHeader(value = "X-Payeelord-Signature", required = false) String payeelordSig,
            @RequestHeader(value = "X-Signature",           required = false) String xSig,
            @RequestHeader(value = "X-Webhook-Signature",   required = false) String webhookSig,
            @RequestBody String rawPayload) {

        logger.info("[PAYEELORD-WEBHOOK] Received webhook call");

        String signature = firstPresent(payeelordSig, xSig, webhookSig);
        if (signature == null) {
            logger.warn("[PAYEELORD-WEBHOOK] No signature header present (checked X-Payeelord-Signature, " +
                    "X-Signature, X-Webhook-Signature) — it will be rejected " +
                    "(header name unconfirmed, see PayeelordWebhookController Javadoc)");
        }

        if (!gateway.validateWebhookSignature(signature, rawPayload)) {
            logger.warn("[PAYEELORD-WEBHOOK] Rejected — signature mismatch");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        try {
            PayeelordWebhookPayload payload = objectMapper.readValue(rawPayload, PayeelordWebhookPayload.class);
            vasService.recordWebhookConfirmation(payload);
            return ResponseEntity.ok("Webhook received");
        } catch (Exception e) {
            logger.error("[PAYEELORD-WEBHOOK] Processing failed — payload may not match the documented " +
                    "envelope shape; this is audit-trail-only so the purchase itself is unaffected", e);
            // Still 200 — Payeelord shouldn't retry an audit ping forever, and the
            // synchronous purchase response (not this webhook) is the source of truth.
            return ResponseEntity.ok("Webhook received (processing error logged)");
        }
    }

    /** Returns the first non-blank value from the given candidates, or {@code null}. */
    private String firstPresent(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }
}
