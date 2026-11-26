package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.WebhookEvent;
import com.moniewise.moniewise_backend.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class WebhookEventService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookEventService.class);

    private final WebhookEventRepository webhookEventRepository;

    public WebhookEventService(WebhookEventRepository webhookEventRepository) {
        this.webhookEventRepository = webhookEventRepository;
    }

    /**
     * Persists a new webhook event record.
     *
     * <p>If a record with the same (providerName, idempotencyKey) already exists
     * it is returned as-is — no duplicate insert.  The unique constraint on the
     * {@code webhook_events} table is the final safety net: if two threads race
     * past the {@code findBy…} check simultaneously, the DB will reject one of
     * the inserts with a constraint violation, which we catch and resolve by
     * re-fetching the winning row.
     */
    @Transactional
    public WebhookEvent saveIfNew(
            String providerName,
            String eventType,
            String externalReference,
            String idempotencyKey,
            String signature,
            String payloadJson,
            String headersJson
    ) {
        Optional<WebhookEvent> existing =
                webhookEventRepository.findByProviderNameAndIdempotencyKey(providerName, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        WebhookEvent event = new WebhookEvent();
        event.setProviderName(providerName);
        event.setEventType(eventType);
        event.setExternalReference(externalReference);
        event.setIdempotencyKey(idempotencyKey);
        event.setSignature(signature);
        event.setPayloadJson(payloadJson);
        event.setHeadersJson(headersJson);
        event.setProcessingStatus("RECEIVED");
        event.setProcessingAttempts(0);
        event.setReceivedAt(LocalDateTime.now());

        try {
            return webhookEventRepository.save(event);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request won the race and already inserted this key.
            // Re-fetch and return the winner's row so the caller has a valid entity.
            logger.warn("[Webhook] Race condition on idempotency key {} for {} — returning existing row",
                    idempotencyKey, providerName);
            return webhookEventRepository
                    .findByProviderNameAndIdempotencyKey(providerName, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Webhook row vanished after constraint violation for key: " + idempotencyKey));
        }
    }

    /**
     * Returns {@code true} only when a previous delivery of this event was
     * <b>successfully processed</b> — status {@code PROCESSED}.
     *
     * <p>Events that are {@code FAILED} or still {@code RECEIVED} are considered
     * eligible for re-processing so that provider retries are honoured and no
     * money is permanently lost due to a transient error.
     */
    public boolean alreadyProcessed(String providerName, String idempotencyKey) {
        return webhookEventRepository
                .findByProviderNameAndIdempotencyKey(providerName, idempotencyKey)
                .filter(e -> "PROCESSED".equals(e.getProcessingStatus()))
                .isPresent();
    }

    @Transactional
    public void markProcessed(Long webhookEventId) {
        WebhookEvent event = webhookEventRepository.findById(webhookEventId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook event not found: " + webhookEventId));
        event.setProcessingStatus("PROCESSED");
        event.setProcessedAt(LocalDateTime.now());
        event.setProcessingAttempts(event.getProcessingAttempts() + 1);
        webhookEventRepository.save(event);
    }

    @Transactional
    public void markFailed(Long webhookEventId, String errorMessage) {
        WebhookEvent event = webhookEventRepository.findById(webhookEventId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook event not found: " + webhookEventId));
        event.setProcessingStatus("FAILED");
        event.setErrorMessage(errorMessage);
        event.setProcessingAttempts(event.getProcessingAttempts() + 1);
        webhookEventRepository.save(event);
    }
}
