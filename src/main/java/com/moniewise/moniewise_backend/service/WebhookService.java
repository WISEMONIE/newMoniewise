package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.WebhookEvent;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.ProvidusExpressGateway;
import com.moniewise.moniewise_backend.psp.SecureWaveGateway;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookEventService webhookEventService;
    private final WalletWebhookService walletWebhookService;
    private final PaymentGatewayResolver paymentGatewayResolver;

    private final ExternalTransferSettlementService externalTransferSettlementService;

    /**
     * Shared-secret HTTP header pair used to authenticate inbound Rubies
     * webhook calls — the "Live Header Key" / "Live Header Value" fields on
     * Rubies' organisation-settings dashboard.
     *
     * <p>Deliberately wired as plain environment variables (not
     * {@code system_config} DB rows) — exactly like {@code RUBIES_WEBHOOK_SECRET}
     * / {@code RUBIES_API_KEY} on {@link RubiesGateway}. Secrets belong in env
     * vars (Render dashboard → Environment), never in a database table that's
     * readable through a generic admin-config endpoint, cached in Redis, or
     * captured in DB backups/dumps. This also matches the workflow you already
     * use for {@code RUBIES_WEBHOOK_SECRET}: generate the value, paste it into
     * Render's environment variables (and identically into Rubies' "Live Header
     * Key"/"Live Header Value" dashboard fields) — no extra admin API call needed.
     *
     * <p>Set {@code RUBIES_WEBHOOK_HEADER_KEY} / {@code RUBIES_WEBHOOK_HEADER_VALUE}
     * env vars (map to {@code rubies.webhook.header.key} / {@code .value}).
     * While either is blank, authentication falls back to the legacy HMAC
     * {@code X-Rubies-Signature} check — see {@link #verifyRubiesWebhookAuthenticity}.
     */
    @Value("${rubies.webhook.header.key:}")
    private String rubiesWebhookHeaderKey;

    /** Expected value for the {@link #rubiesWebhookHeaderKey} header — see its Javadoc. */
    @Value("${rubies.webhook.header.value:}")
    private String rubiesWebhookHeaderValue;

    public WebhookService(
            WebhookEventService webhookEventService,
            WalletWebhookService walletWebhookService,
            PaymentGatewayResolver paymentGatewayResolver,
            ExternalTransferSettlementService externalTransferSettlementService) {
        this.webhookEventService = webhookEventService;
        this.walletWebhookService = walletWebhookService;
        this.paymentGatewayResolver = paymentGatewayResolver;
        this.externalTransferSettlementService = externalTransferSettlementService;
    }

    public void processSecureWaveWebhook(String signatureHeader, String rawPayload) {
        PaymentGateway gateway = paymentGatewayResolver.resolveByProviderName(SecureWaveGateway.PROVIDER_NAME);
        String cleanPayload = normalizePayload(rawPayload);

        if (!gateway.validateWebhookSignature(signatureHeader, cleanPayload)) {
            logger.warn("Rejected SecureWave webhook due to signature mismatch");
            throw new SecurityException("Invalid SecureWave webhook signature");
        }

        String providerName = gateway.getProviderName();
        String eventType = gateway.extractWebhookEventType(cleanPayload);
        String externalReference = gateway.extractWebhookReference(cleanPayload);
        String idempotencyKey = generateIdempotencyKey(providerName, externalReference, cleanPayload);

        if (webhookEventService.alreadyProcessed(providerName, idempotencyKey)) {
            logger.info("Duplicate SecureWave webhook detected, skipping processing");
            return;
        }

        WebhookEvent event = webhookEventService.saveIfNew(
                providerName,
                eventType,
                externalReference,
                idempotencyKey,
                signatureHeader,
                cleanPayload,
                null
        );

        try {
            if (isFundingEvent(eventType)) {
                walletWebhookService.processFundingWebhook(cleanPayload);

            } else if (isSecureWaveTransferSuccessEvent(eventType)) {
                externalTransferSettlementService.settleExternalTransfer(
                        externalReference,
                        "SUCCESS"
                );

            } else if (isSecureWaveTransferFailedEvent(eventType)) {
                externalTransferSettlementService.settleExternalTransfer(
                        externalReference,
                        "FAILED"
                );

            } else {
                logger.info("Ignoring unsupported SecureWave webhook event type {}", eventType);
            }

            webhookEventService.markProcessed(event.getId());
        } catch (Exception e) {
            logger.error("Webhook processing failed for event {}", event.getId(), e);
            webhookEventService.markFailed(event.getId(), e.getMessage());
            throw e;
        }





    }

    private String normalizePayload(String rawPayload) {
        String cleanPayload = rawPayload == null ? "" : rawPayload.trim();
        if (cleanPayload.startsWith("\"") && cleanPayload.endsWith("\"")) {
            cleanPayload = cleanPayload.substring(1, cleanPayload.length() - 1);
        }
        return cleanPayload.replace("\\\"", "\"");
    }

    private String generateIdempotencyKey(String providerName, String externalReference, String payload) {
        if (externalReference != null && !externalReference.isBlank()) {
            return externalReference;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = providerName + payload;
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error generating idempotency key", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Providus webhook processing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Entry point for all Providus webhook events.
     *
     * <p>Flow:
     * <ol>
     *   <li>Log the raw payload immediately (critical for learning Providus's format)</li>
     *   <li>Validate signature (HMAC-SHA256; rejected when no secret is configured)</li>
     *   <li>Extract event type and reference, generate idempotency key</li>
     *   <li>Persist to {@code webhook_events} for auditability</li>
     *   <li>Route to deposit processor, withdrawal-success, or withdrawal-failed handler</li>
     * </ol>
     *
     * @param signatureHeader value of whichever signature header Providus sends
     *                        (accepted from multiple header names in the controller)
     * @param rawPayload      the raw request body exactly as Providus sent it
     */
    public void processProvidusWebhook(String signatureHeader, String rawPayload) {
        String cleanPayload = normalizePayload(rawPayload);

        // ── Log FIRST — before any processing so we capture the real Providus format ──
        logger.info("[PROVIDUS-WEBHOOK] ===== Incoming payload =====\n{}", cleanPayload);

        PaymentGateway gateway = paymentGatewayResolver.resolveByProviderName(ProvidusExpressGateway.PROVIDER_NAME);

        if (!gateway.validateWebhookSignature(signatureHeader, cleanPayload)) {
            logger.warn("[PROVIDUS-WEBHOOK] Rejected — invalid signature");
            throw new SecurityException("Invalid Providus webhook signature");
        }

        String providerName  = gateway.getProviderName();
        String eventType     = gateway.extractWebhookEventType(cleanPayload);
        String externalRef   = gateway.extractWebhookReference(cleanPayload);
        String idempotencyKey = generateIdempotencyKey(providerName, externalRef, cleanPayload);

        logger.info("[PROVIDUS-WEBHOOK] eventType={} reference={} idempotencyKey={}",
                eventType, externalRef, idempotencyKey);

        if (webhookEventService.alreadyProcessed(providerName, idempotencyKey)) {
            logger.info("[PROVIDUS-WEBHOOK] Duplicate detected (key={}) — skipping", idempotencyKey);
            return;
        }

        String headersJson = signatureHeader != null
                ? "{\"X-Signature\":\"" + signatureHeader + "\"}"
                : null;

        WebhookEvent event = webhookEventService.saveIfNew(
                providerName, eventType, externalRef,
                idempotencyKey, signatureHeader, cleanPayload, headersJson);

        try {
            if (isProvidusDepositEvent(eventType)) {
                walletWebhookService.processProvidusDepositWebhook(cleanPayload);

            } else if (isProvidusTransferSuccessEvent(eventType)) {
                walletWebhookService.processProvidusWithdrawalWebhook(cleanPayload, true);

            } else if (isProvidusTransferFailedEvent(eventType)) {
                walletWebhookService.processProvidusWithdrawalWebhook(cleanPayload, false);

            } else {
                // Unknown/unhandled event type — record it but don't crash.
                // Once the first live webhook arrives we can add handling here.
                logger.info("[PROVIDUS-WEBHOOK] Unhandled event type '{}' — stored for review", eventType);
            }

            webhookEventService.markProcessed(event.getId());

        } catch (Exception e) {
            logger.error("[PROVIDUS-WEBHOOK] Processing failed for WebhookEvent id={}", event.getId(), e);
            webhookEventService.markFailed(event.getId(), e.getMessage());
            throw e;
        }
    }

    // ── Providus event-type classifiers ──────────────────────────────────────

    private boolean isProvidusDepositEvent(String eventType) {
        if (eventType == null) return false;
        String u = eventType.toUpperCase();
        return u.contains("DEPOSIT")   || u.contains("CREDIT")
            || u.contains("FUND")      || u.contains("SUCCESSFUL_TRANSACTION")
            || u.contains("PAYMENT_SUCCESSFUL") || u.contains("TRANSACTION_SUCCESSFUL")
            || u.contains("INCOMING")  || u.contains("WALLET_CREDITED")
            || u.contains("WALLET_FUNDED");
    }

    private boolean isProvidusTransferSuccessEvent(String eventType) {
        if (eventType == null) return false;
        String u = eventType.toUpperCase();
        return (u.contains("TRANSFER") && (u.contains("SUCCESS") || u.contains("SUCCESSFUL")))
            || (u.contains("WITHDRAWAL") && (u.contains("SUCCESS") || u.contains("SUCCESSFUL")))
            || (u.contains("DEBIT") && (u.contains("SUCCESS") || u.contains("SUCCESSFUL")));
    }

    private boolean isProvidusTransferFailedEvent(String eventType) {
        if (eventType == null) return false;
        String u = eventType.toUpperCase();
        return (u.contains("TRANSFER") && (u.contains("FAIL") || u.contains("FAILED")))
            || (u.contains("WITHDRAWAL") && (u.contains("FAIL") || u.contains("FAILED")))
            || (u.contains("DEBIT") && (u.contains("FAIL") || u.contains("FAILED")));
    }

    private boolean isFundingEvent(String eventType) {
        return "SUCCESSFUL_TRANSACTION".equalsIgnoreCase(eventType)
                || "payment_successful".equalsIgnoreCase(eventType);
    }

    private boolean isSecureWaveTransferSuccessEvent(String eventType) {
        if (eventType == null) return false;

        String u = eventType.toUpperCase();

        return (u.contains("TRANSFER") && (u.contains("SUCCESS") || u.contains("SUCCESSFUL")))
                || (u.contains("WITHDRAWAL") && (u.contains("SUCCESS") || u.contains("SUCCESSFUL")))
                || (u.contains("DEBIT") && (u.contains("SUCCESS") || u.contains("SUCCESSFUL")));
    }
    private boolean isSecureWaveTransferFailedEvent(String eventType) {
        if (eventType == null) return false;

        String u = eventType.toUpperCase();

        return (u.contains("TRANSFER") && (u.contains("FAIL") || u.contains("FAILED")))
                || (u.contains("WITHDRAWAL") && (u.contains("FAIL") || u.contains("FAILED")))
                || (u.contains("DEBIT") && (u.contains("FAIL") || u.contains("FAILED")))
                || u.contains("REVERSED")
                || u.contains("REVERSAL");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rubies webhook processing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Entry point for all Rubies MFB webhook events.
     *
     * <p>Flow:
     * <ol>
     *   <li>Log raw payload immediately for auditability</li>
     *   <li>Authenticate the call — see {@link #verifyRubiesWebhookAuthenticity}</li>
     *   <li>Extract event type + reference, generate idempotency key</li>
     *   <li>Persist to {@code webhook_events}, route to settlement handler</li>
     * </ol>
     *
     * @param signatureHeader value of the legacy {@code X-Rubies-Signature} header (fallback path only)
     * @param rawPayload      the raw request body from Rubies
     * @param httpRequest     the raw inbound request — needed to look up the
     *                        admin-configured custom auth header by name, since
     *                        its name isn't fixed at compile time
     */
    public void processRubiesWebhook(String signatureHeader, String rawPayload, HttpServletRequest httpRequest) {
        String cleanPayload = normalizePayload(rawPayload);

        logger.info("[RUBIES-WEBHOOK] ===== Incoming payload =====\n{}", cleanPayload);

        PaymentGateway gateway = paymentGatewayResolver.resolveByProviderName(RubiesGateway.PROVIDER_NAME);

        verifyRubiesWebhookAuthenticity(gateway, signatureHeader, cleanPayload, httpRequest);

        String providerName   = gateway.getProviderName();
        String eventType      = gateway.extractWebhookEventType(cleanPayload);
        String externalRef    = gateway.extractWebhookReference(cleanPayload);
        String idempotencyKey = generateIdempotencyKey(providerName, externalRef, cleanPayload);

        logger.info("[RUBIES-WEBHOOK] eventType={} reference={} idempotencyKey={}",
                eventType, externalRef, idempotencyKey);

        if (webhookEventService.alreadyProcessed(providerName, idempotencyKey)) {
            logger.info("[RUBIES-WEBHOOK] Duplicate detected (key={}) — skipping", idempotencyKey);
            return;
        }

        String headersJson = signatureHeader != null
                ? "{\"X-Rubies-Signature\":\"" + signatureHeader + "\"}"
                : null;

        WebhookEvent event = webhookEventService.saveIfNew(
                providerName, eventType, externalRef,
                idempotencyKey, signatureHeader, cleanPayload, headersJson);

        try {
            if (isRubiesTransferSuccessEvent(eventType)) {
                // Outbound transfer confirmed — settle wallet withdrawal (WD- reference)
                walletWebhookService.processRubiesWithdrawalWebhook(cleanPayload, true);
                // Also settle envelope external transfer if this reference belongs to one (EXT- prefix).
                // settleExternalTransferIfExists() returns false silently if the reference is not an
                // envelope transfer — both handlers coexist without interfering.
                externalTransferSettlementService.settleExternalTransferIfExists(externalRef, "SUCCESS");

            } else if (isRubiesTransferFailedEvent(eventType)) {
                // Outbound transfer failed — settle wallet withdrawal (WD- reference)
                walletWebhookService.processRubiesWithdrawalWebhook(cleanPayload, false);
                // Also settle (mark failed) envelope external transfer if applicable (EXT- prefix)
                externalTransferSettlementService.settleExternalTransferIfExists(externalRef, "FAILED");

            } else if (isRubiesDepositEvent(eventType)) {
                // Inbound NIP credit to a Rubies wallet — credit internal balance, notify user
                walletWebhookService.processRubiesDepositWebhook(cleanPayload);

            } else {
                logger.info("[RUBIES-WEBHOOK] Unhandled event type '{}' — stored for review", eventType);
            }

            webhookEventService.markProcessed(event.getId());

        } catch (Exception e) {
            logger.error("[RUBIES-WEBHOOK] Processing failed for WebhookEvent id={}", event.getId(), e);
            webhookEventService.markFailed(event.getId(), e.getMessage());
            throw e;
        }
    }

    // ── Rubies webhook authenticity check ────────────────────────────────────

    /**
     * Authenticates an inbound Rubies webhook call using whichever mechanism
     * is actually configured — see {@link #rubiesWebhookHeaderKey} for the
     * full rationale. In short: Rubies' own webhook documentation describes
     * no payload-signing scheme at all (just "configure your callback URL"),
     * so the legacy HMAC {@code X-Rubies-Signature} check will very likely
     * reject every real webhook — with or without a secret configured. Set the
     * header key/value pair below in production.
     *
     * <p>Precedence:
     * <ol>
     *   <li><b>Custom header key/value</b> ({@code RUBIES_WEBHOOK_HEADER_KEY} /
     *       {@code _VALUE} env vars, mirroring "Live Header Key"/"Live Header
     *       Value" on Rubies' dashboard) — when BOTH are configured, this is
     *       the <em>only</em> check performed: the inbound request must carry
     *       a header with that exact name and value (constant-time compared),
     *       or the call is rejected outright.</li>
     *   <li><b>Legacy HMAC signature</b> — only consulted when the header pair
     *       above is left unconfigured, so deployments that haven't set the
     *       new env vars yet keep working exactly as before.</li>
     * </ol>
     *
     * @throws SecurityException if neither configured mechanism accepts the request
     */
    private void verifyRubiesWebhookAuthenticity(PaymentGateway gateway,
                                                  String signatureHeader,
                                                  String cleanPayload,
                                                  HttpServletRequest httpRequest) {
        String configuredHeaderKey   = rubiesWebhookHeaderKey   == null ? "" : rubiesWebhookHeaderKey.trim();
        String configuredHeaderValue = rubiesWebhookHeaderValue == null ? "" : rubiesWebhookHeaderValue.trim();

        if (!configuredHeaderKey.isBlank() && !configuredHeaderValue.isBlank()) {
            String actualValue = httpRequest.getHeader(configuredHeaderKey);
            if (actualValue == null || !constantTimeEquals(actualValue.trim(), configuredHeaderValue)) {
                logger.warn("[RUBIES-WEBHOOK][SECURITY] Custom auth header check failed — " +
                                "configured header name='{}', present in request={}",
                        configuredHeaderKey, actualValue != null);
                throw new SecurityException("Invalid Rubies webhook authentication header");
            }
            logger.debug("[RUBIES-WEBHOOK] Custom auth header '{}' verified", configuredHeaderKey);
            return;
        }

        // ── Fallback: legacy HMAC signature path (pre-existing behaviour) ──
        // Only reached while RUBIES_WEBHOOK_HEADER_KEY/_VALUE are unset —
        // preserves backward compatibility for any deployment that already had
        // RUBIES_WEBHOOK_SECRET working some other way (e.g. a sandbox that
        // genuinely does sign payloads), without permanently locking out real
        // production webhooks the moment a secret happens to be set.
        if (!gateway.validateWebhookSignature(signatureHeader, cleanPayload)) {
            logger.warn("[RUBIES-WEBHOOK] Rejected — invalid signature, and no " +
                    "RUBIES_WEBHOOK_HEADER_KEY/_VALUE configured as an alternative. " +
                    "If Rubies' real webhooks don't send X-Rubies-Signature (their docs " +
                    "don't describe any signing scheme), set those two env vars instead — " +
                    "see WebhookService.rubiesWebhookHeaderKey.");
            throw new SecurityException("Invalid Rubies webhook signature");
        }
    }

    /** Timing-attack-resistant string comparison for shared-secret header values. */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    // ── Rubies event-type classifiers ─────────────────────────────────────────

    private boolean isRubiesTransferSuccessEvent(String eventType) {
        if (eventType == null) return false;
        String u = eventType.toUpperCase();
        return u.contains("TRANSFER.SUCCESS")
            || (u.contains("TRANSFER") && u.contains("SUCCESS"))
            || (u.contains("DEBIT")    && u.contains("SUCCESS"));
    }

    private boolean isRubiesTransferFailedEvent(String eventType) {
        if (eventType == null) return false;
        String u = eventType.toUpperCase();
        return u.contains("TRANSFER.FAILED")
            || u.contains("TRANSFER.FAIL")
            || (u.contains("TRANSFER") && (u.contains("FAIL") || u.contains("FAILED")))
            || (u.contains("DEBIT")    && (u.contains("FAIL") || u.contains("FAILED")));
    }

    private boolean isRubiesDepositEvent(String eventType) {
        if (eventType == null) return false;
        String u = eventType.toUpperCase();
        return u.contains("CREDIT") || u.contains("DEPOSIT")
            || u.contains("FUND")   || u.contains("INCOMING");
    }
}
