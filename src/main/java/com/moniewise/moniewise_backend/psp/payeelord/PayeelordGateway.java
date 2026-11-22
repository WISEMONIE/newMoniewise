package com.moniewise.moniewise_backend.psp.payeelord;

import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordAirtimePurchaseRequest;
import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordAirtimePurchaseResponse;
import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordDataPurchaseRequest;
import com.moniewise.moniewise_backend.psp.payeelord.dto.PayeelordDataPurchaseResponse;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import com.moniewise.moniewise_backend.utils.WebhookSignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Thin HTTP client for Payeelord's Airtime/Data VAS API.
 *
 * <p>Payeelord is a VAS/biller reseller, NOT a transfer PSP — it intentionally does
 * NOT implement {@code PaymentGateway}. {@link #purchaseAirtime} and
 * {@link #purchaseData} return Payeelord's own response DTOs (not booleans/strings),
 * because the caller ({@code PayeelordVasService}) needs the full synchronous
 * response — {@code transaction_id}, {@code balance_before/after}, the exact
 * {@code status} — to record an accurate audit trail and decide whether to reverse
 * the user's wallet debit.
 *
 * <h3>This integration is SYNCHRONOUS</h3>
 * Per Payeelord's own integration notes: <em>"A transaction webhook fires only
 * after the purchase endpoint returns its JSON response."</em> The HTTP response
 * from {@code /buy/airtime} / {@code /buy/data} is the source of truth; the webhook
 * (see {@link #validateWebhookSignature}) is a secondary audit confirmation only.
 *
 * <h3>Configuration — set these in the Render dashboard, no Postman needed</h3>
 * <ul>
 *   <li>{@code PAYEELORD_API_KEY} — your Payeelord API key. Sent as {@code token: <key>}
 *       header. Env var always wins over any DB system_config value.</li>
 *   <li>{@code PAYEELORD_BASE_URL} — optional override (default
 *       {@code https://api.payeelord.com/api}). {@code /api} is auto-appended if missing.</li>
 * </ul>
 */
@Component
public class PayeelordGateway {

    public static final String PROVIDER_NAME = "PAYEELORD";

    private static final Logger logger = LoggerFactory.getLogger(PayeelordGateway.class);

    private final RestTemplate restTemplate;
    private final SystemConfigService systemConfig;

    @Value("${payeelord.api.key:}")
    private String apiKey;

    @Value("${payeelord.base.url:https://api.payeelord.com/api}")
    private String defaultBaseUrl;

    /**
     * Optional explicit webhook secret override. Per Payeelord's docs, webhook
     * signatures normally use the CURRENT API KEY itself as the HMAC secret
     * ("regenerating the API key also rotates webhook signing automatically"),
     * so {@link #validateWebhookSignature} falls back to {@link #apiKey} when
     * this is blank. Only set {@code PAYEELORD_WEBHOOK_SECRET} if Payeelord ever
     * issues you a dedicated signing secret distinct from the API key.
     */
    @Value("${payeelord.webhook.secret:}")
    private String webhookSecretOverride;

    public PayeelordGateway(RestTemplate restTemplate, SystemConfigService systemConfig) {
        this.restTemplate = restTemplate;
        this.systemConfig = systemConfig;
    }

    // ── Startup validation ────────────────────────────────────────────────────

    @PostConstruct
    public void validateConfiguration() {
        String key = resolveApiKey();
        if (key == null || key.isBlank()) {
            logger.error("[Payeelord] API key not configured — add PAYEELORD_API_KEY to your Render " +
                    "environment variables and redeploy. All purchase calls will return 401 until this is set.");
        } else {
            String source = (apiKey != null && !apiKey.isBlank()) ? "env-var" : "system_config";
            logger.info("[Payeelord] Gateway initialised. key={}*** base_url={} auth_header=Authorization source={}",
                    key.substring(0, Math.min(6, key.length())), baseUrl(), source);
        }
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    public String getProviderName() {
        return PROVIDER_NAME;
    }

    // ── Airtime purchase ──────────────────────────────────────────────────────

    /**
     * Calls {@code POST /buy/airtime}. SYNCHRONOUS — the returned response's
     * {@code status} ("successful" | "failed" | "processing") is authoritative.
     *
     * <p>Never throws on a well-formed "failed" response — the caller needs that
     * response body to reverse the wallet debit and record the failure reason.
     * Only throws on transport-level failures (timeouts, malformed/empty bodies,
     * non-2xx HTTP statuses) where we genuinely don't know what happened — those
     * are the dangerous "ambiguous" cases the caller must treat specially
     * (the float may have been debited even though we got no usable response).
     *
     * @param network      MTN | GLO | AIRTEL | 9MOBILE
     * @param mobileNumber recipient line, 11–20 digits
     * @param amount       Naira amount, 10–5000 inclusive
     */
    public PayeelordAirtimePurchaseResponse purchaseAirtime(String network, String mobileNumber, BigDecimal amount) {
        String url = baseUrl() + "/buy/airtime";
        PayeelordAirtimePurchaseRequest req = new PayeelordAirtimePurchaseRequest(network, mobileNumber, amount);

        logger.info("[Payeelord] Buying airtime: network={} mobile={} amount={}", network, mobileNumber, amount);

        try {
            ResponseEntity<PayeelordAirtimePurchaseResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders(true)),
                            PayeelordAirtimePurchaseResponse.class);

            PayeelordAirtimePurchaseResponse body = response.getBody();
            if (body == null || body.getStatus() == null) {
                throw new PayeelordAmbiguousResponseException(
                        "Payeelord airtime purchase returned an empty/unreadable body — " +
                                "outcome is AMBIGUOUS, the float may have been debited. " +
                                "Reconcile via GET /data-transactions before reversing the user's wallet.");
            }

            logger.info("[Payeelord] Airtime purchase result: mobile={} status={} txnId={}",
                    mobileNumber, body.getStatus(), body.getTransactionId());
            return body;

        } catch (PayeelordAmbiguousResponseException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            // Payeelord may put a structured failure body even on non-2xx — try to read it as a failure,
            // but if we can't parse it, treat it as ambiguous (don't blindly assume failure).
            PayeelordAirtimePurchaseResponse parsed = tryParseFailureBody(e.getResponseBodyAsString(),
                    PayeelordAirtimePurchaseResponse.class, e);
            if (parsed != null) return parsed;
            if (isDefiniteRejection(e)) {
                logger.error("[Payeelord] Airtime purchase REJECTED (HTTP {}) mobile={} payeelord_body={}",
                        e.getStatusCode(), mobileNumber, e.getResponseBodyAsString());
                PayeelordAirtimePurchaseResponse failed = new PayeelordAirtimePurchaseResponse();
                failed.setStatus("failed");
                String providerMsg = extractProviderMessage(e.getResponseBodyAsString());
                String msg = providerMsg != null ? providerMsg
                        : "Airtime purchase failed (HTTP " + e.getStatusCode().value() + "). Please try again shortly.";
                failed.setMessage(msg);
                return failed;
            }
            logger.error("[Payeelord] Airtime purchase HTTP error (ambiguous outcome): mobile={} status={} body={}",
                    mobileNumber, e.getStatusCode(), e.getResponseBodyAsString());
            throw new PayeelordAmbiguousResponseException(
                    "Payeelord airtime purchase returned HTTP " + e.getStatusCode() +
                            " with an unparseable body — outcome is AMBIGUOUS.", e);
        } catch (ResourceAccessException e) {
            // Timeout / connection failure — we genuinely don't know if Payeelord processed the debit.
            logger.error("[Payeelord] Airtime purchase network error (ambiguous outcome): mobile={} error={}",
                    mobileNumber, e.getMessage());
            throw new PayeelordAmbiguousResponseException(
                    "Payeelord airtime purchase timed out / connection failed — outcome is AMBIGUOUS. " +
                            "Do NOT assume failure; reconcile via GET /data-transactions before reversing.", e);
        }
    }

    // ── Data purchase ─────────────────────────────────────────────────────────

    /**
     * Calls {@code POST /buy/data}. SYNCHRONOUS — same authoritative-response and
     * ambiguous-outcome semantics as {@link #purchaseAirtime}.
     *
     * @param networkId Payeelord's numeric network id from the catalog (1=MTN, 2=GLO, 3=9MOBILE, 4=AIRTEL)
     * @param dataId    Payeelord's plan id from the catalog
     * @param mobileNumber recipient line
     */
    public PayeelordDataPurchaseResponse purchaseData(String networkId, String dataId, String dataType, String mobileNumber) {
        String url = baseUrl() + "/buy/data";

        PayeelordDataPurchaseRequest req = new PayeelordDataPurchaseRequest(networkId, dataId, dataType, mobileNumber);

        logger.info("[Payeelord] Buying data: networkId={} dataId={} dataType={} mobile={}",
                networkId, dataId, dataType, mobileNumber);

        try {
            ResponseEntity<PayeelordDataPurchaseResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders(false)),
                            PayeelordDataPurchaseResponse.class);

            PayeelordDataPurchaseResponse body = response.getBody();
            if (body == null || body.getStatus() == null) {
                throw new PayeelordAmbiguousResponseException(
                        "Payeelord data purchase returned an empty/unreadable body.");
            }

            logger.info("[Payeelord] Data purchase SUCCESS: mobile={} status={} txnId={}",
                    mobileNumber, body.getStatus(), body.getTransactionId());
            return body;

        } catch (PayeelordAmbiguousResponseException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            PayeelordDataPurchaseResponse parsed = tryParseFailureBody(e.getResponseBodyAsString(),
                    PayeelordDataPurchaseResponse.class, e);
            if (parsed != null) return parsed;

            if (isDefiniteRejection(e)) {
                logger.error("[Payeelord] Data purchase REJECTED (HTTP {}) mobile={} body={}",
                        e.getStatusCode(), mobileNumber, e.getResponseBodyAsString());

                PayeelordDataPurchaseResponse failed = new PayeelordDataPurchaseResponse();
                failed.setStatus("failed");
                String msg = extractProviderMessage(e.getResponseBodyAsString());
                failed.setMessage(msg != null ? msg : "Data purchase failed (HTTP " + e.getStatusCode().value() + ")");
                return failed;
            }

            throw new PayeelordAmbiguousResponseException("HTTP " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new PayeelordAmbiguousResponseException("Connection failed", e);
        }
    }

    // ── Webhook helpers (audit-trail confirmation only — see class Javadoc) ──

    /**
     * Best-effort signature check for Payeelord's webhook callbacks.
     *
     * <p><strong>STATUS: header name and exact signing scheme are UNDOCUMENTED.</strong>
     * Payeelord's docs state only that <em>"Webhook signatures use your current API
     * key as the HMAC secret, so regenerating the API key also rotates webhook
     * signing automatically"</em> — confirming HMAC + API-key-as-secret, but not
     * which header carries the digest or which hash algorithm is used. We mirror
     * Rubies' HMAC-SHA512 hex-digest convention as the most likely candidate.
     *
     * <p>Fails closed: a missing secret, a missing signature header, or an error while
     * computing the digest all reject the request. The webhook is audit-only (the
     * synchronous purchase response is the source of truth), so a rejected delivery
     * loses nothing but the {@code webhookConfirmedAt} stamp.
     *
     * @param signature  raw signature header value, or {@code null} if the caller found none
     * @param rawPayload the exact raw request body bytes as a string (must match what was signed)
     */
    public boolean validateWebhookSignature(String signature, String rawPayload) {
        String secret = (webhookSecretOverride != null && !webhookSecretOverride.isBlank())
                ? webhookSecretOverride
                : resolveApiKey();

        if (secret == null || secret.isBlank()) {
            logger.error("[Payeelord][SECURITY] Webhook rejected — no webhook secret/API key configured.");
            return false;
        }
        if (signature == null || signature.isBlank()) {
            logger.warn("[Payeelord][WEBHOOK] Webhook rejected — no signature header found " +
                    "(the carrier header name is still unconfirmed, see PayeelordGateway Javadoc).");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hashBytes = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            boolean valid = WebhookSignatures.hexDigestsMatch(hex.toString(), signature);
            if (!valid) {
                // Never log the expected digest — it is a valid signature for this payload.
                logger.warn("[Payeelord][WEBHOOK] Signature mismatch (scheme unverified — see Javadoc).");
            }
            return valid;
        } catch (Exception e) {
            logger.error("[Payeelord][WEBHOOK] HMAC validation threw an exception", e);
            return false;
        }
    }

    // ── Wallet balance (admin float monitor) ──────────────────────────────────

    /**
     * Calls {@code GET /check/balance} to read our Payeelord float balance.
     *
     * <p>Response shape: {@code {"status":"successful","wallet_balance":"N34874.00"}}.
     * Returns {@link java.util.Optional#empty()} on any transport/parse failure —
     * the only caller is a best-effort low-balance monitor, so a transient outage
     * must never page anyone or crash a scheduled run.
     */
    public java.util.Optional<BigDecimal> checkWalletBalance() {
        String url = baseUrl() + "/check/balance";
        try {
            ResponseEntity<java.util.Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(authHeaders(false)), java.util.Map.class);
            java.util.Map<?, ?> body = response.getBody();
            if (body == null) return java.util.Optional.empty();
            Object raw = body.get("wallet_balance");
            if (raw == null) return java.util.Optional.empty();
            // Strip currency noise: "N34874.00" / "₦ 34,874.00" → 34874.00
            String cleaned = raw.toString().replaceAll("[^0-9.]", "");
            if (cleaned.isBlank()) return java.util.Optional.empty();
            return java.util.Optional.of(new BigDecimal(cleaned));
        } catch (Exception e) {
            logger.warn("[Payeelord] check/balance failed: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    // ── Catalog reads (used by PayeelordCatalogSyncJob) ───────────────────────

    /**
     * Diagnostic probe — makes a real call to Payeelord's balance endpoint and returns
     * the raw HTTP status + body so the admin can see exactly what Payeelord says without
     * wading through Spring's exception wrapping. Never throws; returns a simple string.
     */
    public java.util.Map<String, Object> diagnose() {
        String url = baseUrl() + "/check/balance";
        String keyUsed = resolveApiKey();
        String keyPrefix = (keyUsed == null || keyUsed.isBlank()) ? "(NONE)" : keyUsed.substring(0, Math.min(8, keyUsed.length())) + "...";
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("url", url);
        result.put("keyConfigured", !keyPrefix.equals("(NONE)"));
        result.put("keyPrefix", keyPrefix);
        result.put("authHeader", "token: <key>  (custom Payeelord header, not Authorization)");
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(authHeaders(false)), String.class);
            result.put("httpStatus", resp.getStatusCode().value());
            result.put("body", resp.getBody());
        } catch (HttpStatusCodeException e) {
            result.put("httpStatus", e.getStatusCode().value());
            result.put("body", e.getResponseBodyAsString());
            result.put("responseHeaders", e.getResponseHeaders() != null ? e.getResponseHeaders().toSingleValueMap() : null);
        } catch (Exception e) {
            result.put("httpStatus", "ERROR");
            result.put("body", e.getMessage());
        }
        return result;
    }

    /** {@code GET /datatypes} → list of {@code {type, network_id}}. Empty on failure. */
    public java.util.List<java.util.Map<String, Object>> getDataTypes() {
        return getListData(baseUrl() + "/datatypes", null);
    }

    /** {@code GET /all-network} → list of {@code {network_id, network_name}}. Empty on failure. */
    public java.util.List<java.util.Map<String, Object>> getNetworks() {
        return getListData(baseUrl() + "/all-network", null);
    }

    /**
     * {@code GET /data-plan?dataType=SME&networkId=1} →
     * list of {@code {id, dataId, dataName, description, amount, networkId}}.
     *
     * <p>Payeelord ignores GET request bodies (returns 422 "field required" even when
     * the body is present). Params must be query-string, not JSON body.
     */
    public java.util.List<java.util.Map<String, Object>> getDataPlans(String dataType, String networkId) {
        String encodedType;
        try {
            encodedType = java.net.URLEncoder.encode(dataType, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            encodedType = dataType;
        }
        String url = baseUrl() + "/data-plan?dataType=" + encodedType + "&networkId=" + networkId;
        return getListData(url, null); // no body — params are in the URL
    }

    /**
     * Shared GET helper that unwraps Payeelord's {@code {"data": [...]}} envelope.
     * Sends an optional JSON body (Payeelord's {@code /data-plan} is a GET-with-body).
     * Logs the raw response body at INFO level so catalog issues are visible in Render logs.
     */
    @SuppressWarnings("unchecked")
    private java.util.List<java.util.Map<String, Object>> getListData(String url, Object body) {
        try {
            HttpHeaders catalogHeaders = new HttpHeaders();
            catalogHeaders.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            if (body != null) catalogHeaders.setContentType(MediaType.APPLICATION_JSON);
            String key = resolveApiKey();
            if (key != null && !key.isBlank()) catalogHeaders.set("Authorization", "Token " + key);
            HttpEntity<?> entity = new HttpEntity<>(body, catalogHeaders);

            // Deserialize to String first — gives us the raw body for logging and lets us
            // handle both {"data":[...]} and top-level [...] response shapes defensively.
            ResponseEntity<String> rawResp =
                    restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String rawBody = rawResp.getBody();
            logger.info("[Payeelord] Catalog {} → HTTP {} body={}",
                    url,
                    rawResp.getStatusCode().value(),
                    rawBody != null && rawBody.length() > 300 ? rawBody.substring(0, 300) + "…" : rawBody);

            if (rawBody == null || rawBody.isBlank()) return java.util.List.of();

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Object parsed = mapper.readValue(rawBody, Object.class);

            java.util.List<?> list = null;
            if (parsed instanceof java.util.Map<?, ?> m) {
                Object data = m.get("data");
                if (data instanceof java.util.List<?> l) list = l;
                else logger.warn("[Payeelord] Catalog {} — 'data' field missing or not a list (keys={})", url, ((java.util.Map<?,?>) m).keySet());
            } else if (parsed instanceof java.util.List<?> l) {
                list = l; // top-level array — some Payeelord endpoints skip the wrapper
            }

            if (list == null) return java.util.List.of();

            java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof java.util.Map<?, ?> row) {
                    out.add((java.util.Map<String, Object>) row);
                }
            }
            logger.info("[Payeelord] Catalog {} → {} items", url, out.size());
            return out;
        } catch (HttpStatusCodeException e) {
            logger.warn("[Payeelord] Catalog {} failed HTTP {} body={}",
                    url, e.getStatusCode().value(), e.getResponseBodyAsString());
            return java.util.List.of();
        } catch (Exception e) {
            logger.warn("[Payeelord] Catalog {} failed: {}", url, e.getMessage());
            return java.util.List.of();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the Payeelord API key.
     *
     * <p>Priority: env var ({@code PAYEELORD_API_KEY} set in Render dashboard) → system_config DB.
     * Env var wins so the key can be rotated purely from the Render dashboard without any
     * Postman/admin-panel calls.  DB fallback is kept for local dev convenience only.
     */
    private String resolveApiKey() {
        // Env var always wins — set PAYEELORD_API_KEY in the Render dashboard to rotate without redeploy
        if (apiKey != null && !apiKey.isBlank()) return apiKey;
        return systemConfig.getString(SystemConfigService.PAYEELORD_API_KEY, null);
    }

    /**
     * Resolves the active base URL. Checks system_config in priority order:
     * <ol>
     *   <li>{@code payeelord.api.base_url} — the dotted-style key</li>
     *   <li>{@code PAYEELORD_BASE_URL} — the admin-panel SCREAMING_SNAKE_CASE key</li>
     *   <li>{@code @Value("${payeelord.base.url:...}")} — env var / property fallback</li>
     * </ol>
     *
     * <p>Regardless of source, {@code /api} is appended automatically when absent, so both
     * {@code https://api.payeelord.com} and {@code https://api.payeelord.com/api} are valid.
     */
    private String baseUrl() {
        // Env var (PAYEELORD_BASE_URL → payeelord.base.url → defaultBaseUrl) wins over DB
        String url = defaultBaseUrl;
        if (url == null || url.isBlank()) {
            url = systemConfig.getString(SystemConfigService.PAYEELORD_API_BASE_URL, null);
        }
        if (url == null || url.isBlank()) {
            url = systemConfig.getString(SystemConfigService.PAYEELORD_BASE_URL, "https://api.payeelord.com/api");
        }
        // Normalize: strip trailing slash, then ensure /api suffix
        url = url.replaceAll("/+$", "");
        if (!url.endsWith("/api")) {
            url = url + "/api";
        }
        return url;
    }

    /**
     * True for HTTP statuses that mean Payeelord rejected the request at the gate
     * (bad/inactive API key, wrong auth scheme, or a validation error) — the
     * purchase definitely did NOT execute, so the caller can treat it as a clean
     * failure and refund, rather than an ambiguous "maybe the float was charged".
     */
    private boolean isDefiniteRejection(HttpStatusCodeException e) {
        int code = e.getStatusCode().value();
        // 422 = Payeelord float balance insufficient — request was received and rejected cleanly,
        // the purchase definitely did NOT execute, safe to refund.
        // 404 = the address does not exist, so the purchase handler never ran.
        return code == 400 || code == 401 || code == 403 || code == 404 || code == 422;
    }

    /**
     * Builds auth headers for Payeelord purchase/balance endpoints.
     *
     * <p>Payeelord uses Postman's "API Key" auth type with key name {@code Authorization}
     * — confirmed from their official Postman collection. That translates to the raw header:
     * {@code Authorization: <api_key>} (no "Bearer" prefix, no "token" prefix).
     *
     * <p>History of attempts:
     * <ul>
     *   <li>{@code Authorization: Bearer <key>} → 401 (Bearer not supported)</li>
     *   <li>{@code token: <key>} → 401 (wrong header name)</li>
     *   <li>{@code Authorization: <key>} ← current, matches Postman API Key auth format</li>
     * </ul>
     */
    private HttpHeaders authHeaders(boolean useBearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        // Payeelord uses "Token <api_key>" — confirmed from Postman: API Key auth,
        // key=Authorization, value="Token py..."
        headers.set("Authorization", "Token " + resolveApiKey());
        return headers;
    }

    /**
     * Extracts the human-readable error message from a Payeelord error body,
     * regardless of exact JSON shape. Tries common keys: message, error, description.
     * Returns null if the body is blank, not JSON, or has none of these keys.
     */
    private String extractProviderMessage(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<?, ?> map = mapper.readValue(rawBody, java.util.Map.class);
            for (String key : new String[]{"message", "error", "description", "msg", "detail"}) {
                Object val = map.get(key);
                if (val instanceof String s && !s.isBlank()) return s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Payeelord may return failure bodies with a non-2xx HTTP status (their docs
     * show {@code {"status": "failed", "message": "..."}} shapes). If the error
     * body parses cleanly into a response DTO with a "failed" status, treat it as
     * a definitive failure (safe to reverse); otherwise return {@code null} so the
     * caller treats it as ambiguous instead.
     */
    private <T> T tryParseFailureBody(String rawBody, Class<T> type, Exception cause) {
        if (rawBody == null || rawBody.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            T parsed = mapper.readValue(rawBody, type);
            if (parsed instanceof PayeelordAirtimePurchaseResponse r && r.getStatus() != null) {
                return type.cast(r);
            }
            if (parsed instanceof PayeelordDataPurchaseResponse r && r.getStatus() != null) {
                return type.cast(r);
            }
            return null;
        } catch (Exception parseEx) {
            logger.debug("[Payeelord] Could not parse error body as a structured response: {}", parseEx.getMessage());
            return null;
        }
    }

    /**
     * Thrown when we genuinely cannot determine whether a Payeelord purchase
     * succeeded or failed (timeouts, malformed bodies, unparseable error responses).
     *
     * <p>This is the critical "danger zone" of a synchronous reseller integration:
     * the float may have already been debited even though we have no confirmation.
     * {@code PayeelordVasService} MUST catch this distinctly from a clean "failed"
     * response — it should leave the transaction {@code PENDING} (NOT auto-reverse
     * the user's wallet) and flag it for reconciliation via
     * {@code GET /data-transactions}, exactly as Payeelord's own docs recommend.
     */
    public static class PayeelordAmbiguousResponseException extends RuntimeException {
        public PayeelordAmbiguousResponseException(String message) {
            super(message);
        }
        public PayeelordAmbiguousResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
