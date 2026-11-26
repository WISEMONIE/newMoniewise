package com.moniewise.moniewise_backend.psp.payeelord.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Best-effort envelope for Payeelord's transaction webhook payload.
 *
 * <h3>Why this is deliberately loose</h3>
 * Payeelord's webhook documentation only sketches an envelope shape —
 * {@code {id, event, occurred_at, request: {...}, response: {...}}} — without
 * pinning down the exact field names/casing inside {@code response} (and we've
 * already seen the synchronous purchase endpoints disagree with each other on
 * casing — {@code mobile_number} vs {@code mobileNumber}, duplicate {@code status}/
 * {@code Status} keys, etc.). Rather than guess at a rigid nested DTO that a real
 * delivery might not match, {@link #response} is captured as a raw {@code Map} and
 * the accessor methods below probe it defensively for the handful of keys we
 * actually need (the provider's transaction id and status), trying multiple
 * casing/naming candidates.
 *
 * <p>This is intentionally an <b>audit-trail parser</b>, not a source of truth —
 * see {@code PayeelordVasService#recordWebhookConfirmation} and the class Javadoc
 * on {@code PayeelordGateway} for why the synchronous purchase response (not the
 * webhook) is what drives transaction state.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PayeelordWebhookPayload {

    @JsonProperty("id")
    private String id;

    @JsonProperty("event")
    private String event;

    @JsonProperty("occurred_at")
    private String occurredAt;

    /** Captured loosely — see class Javadoc on why a rigid nested DTO would be brittle here. */
    @JsonProperty("response")
    private Map<String, Object> response;

    /** Some deliveries may echo the request instead of (or alongside) the response — also captured loosely. */
    @JsonProperty("request")
    private Map<String, Object> request;

    public String getId()                              { return id; }
    public void setId(String id)                       { this.id = id; }

    public String getEvent()                           { return event; }
    public void setEvent(String event)                 { this.event = event; }

    public String getOccurredAt()                      { return occurredAt; }
    public void setOccurredAt(String occurredAt)       { this.occurredAt = occurredAt; }

    public Map<String, Object> getResponse()           { return response; }
    public void setResponse(Map<String, Object> response) { this.response = response; }

    public Map<String, Object> getRequest()            { return request; }
    public void setRequest(Map<String, Object> request) { this.request = request; }

    // ── Defensive probes ──────────────────────────────────────────────────────

    /**
     * Pulls Payeelord's own transaction id out of {@code response} (or, failing
     * that, {@code request.payload}), trying every casing we've seen across their
     * synchronous purchase responses ({@code transaction_id}, {@code transactionId},
     * {@code TransactionId}).
     */
    public String extractProviderTransactionId() {
        String fromResponse = firstString(response, "transaction_id", "transactionId", "TransactionId", "id");
        if (fromResponse != null) return fromResponse;

        Object nestedPayload = request != null ? request.get("payload") : null;
        if (nestedPayload instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) map;
            return firstString(payload, "transaction_id", "transactionId", "TransactionId", "id");
        }
        return null;
    }

    /** Same defensive probing for the provider-reported status string (audit display only). */
    public String extractProviderStatus() {
        return firstString(response, "status", "Status", "api_response");
    }

    private String firstString(Map<String, Object> source, String... keys) {
        if (source == null) return null;
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null) {
                String s = value.toString().trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }
}
