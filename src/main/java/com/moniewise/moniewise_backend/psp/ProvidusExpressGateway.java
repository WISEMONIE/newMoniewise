package com.moniewise.moniewise_backend.psp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.utils.WebhookSignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class ProvidusExpressGateway implements PaymentGateway {

    public static final String PROVIDER_NAME = "PROVIDUS";

    private static final Logger logger = LoggerFactory.getLogger(ProvidusExpressGateway.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${providus.base-url:https://payment.xpress-wallet.com}")
    private String baseUrl;

    @Value("${providus.merchant-api-key:}")
    private String merchantApiKey;

    @Value("${providus.account-prefix:}")
    private String accountPrefix;

    @Value("${providus.access-token:}")
    private String accessToken;

    @Value("${providus.refresh-token:}")
    private String refreshToken;

    @Value("${moniewise.psp.providus-integration-enabled:false}")
    private boolean providusIntegrationEnabled;

    /**
     * Optional HMAC-SHA256 secret Providus uses to sign webhook calls.
     * Set PROVIDUS_WEBHOOK_SECRET once Providus confirms their signing mechanism.
     * While it is blank every Providus webhook is rejected.
     */
    @Value("${providus.webhook-secret:}")
    private String webhookSecret;

    // ── Startup validation ────────────────────────────────────────────────────

    @PostConstruct
    public void validateConfiguration() {
        if (providusIntegrationEnabled) {
            boolean hasAccessToken  = accessToken  != null && !accessToken.isBlank();
            boolean hasMerchantKey  = merchantApiKey != null && !merchantApiKey.isBlank();
            if (!hasAccessToken && !hasMerchantKey) {
                logger.error("[Providus] Integration is ENABLED but neither PROVIDUS_ACCESS_TOKEN " +
                        "(providus.access-token) nor PROVIDUS_MERCHANT_API_KEY " +
                        "(providus.merchant-api-key) is set — all Providus API calls will fail with 401.");
            }
            if (webhookSecret == null || webhookSecret.isBlank()) {
                logger.warn("[Providus][SECURITY] PROVIDUS_WEBHOOK_SECRET (providus.webhook-secret) is not set — " +
                        "all Providus webhooks will be REJECTED until it is set.");
            }
        }
    }

    public boolean isEnabled() {
        return providusIntegrationEnabled;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    // ─────────────────────────────────────────────────────────────
    // Create Customer Wallet
    // POST /api/v1/wallet
    // ─────────────────────────────────────────────────────────────
    @Override
    public Map<String, String> createVirtualAccount(User user) {
        requireEnabled();
        String url = baseUrl + "/api/v1/wallet";

        Map<String, Object> profile = user.getProfileData() != null
                ? user.getProfileData()
                : Collections.emptyMap();

        String firstName  = safeString(profile.get("firstName"), "Wisemonie");
        String lastName   = safeString(profile.get("lastName"), "User");
        String dob        = safeString(profile.get("dateOfBirth"), safeString(profile.get("dob"), ""));
        String address    = safeString(profile.get("address"), "");
        String phone      = normalizePhone(user.getPhone());
        String bvn        = user.getBvn() != null ? user.getBvn() : "";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bvn",           bvn);
        body.put("firstName",     firstName);
        body.put("lastName",      lastName);
        body.put("accountPrefix", accountPrefix);
        body.put("dateOfBirth",   dob);
        body.put("phoneNumber",   phone);
        body.put("email",         user.getEmail());
        body.put("address",       address);
        body.put("metadata",      Map.of("platform", "moniewise"));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, buildHeaders());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());

            if (!root.path("status").asBoolean(false)) {
                String msg = root.path("message").asText("Providus wallet creation failed");
                logger.error("Providus rejected wallet creation for {}: {}", user.getEmail(), msg);
                throw new RuntimeException("Providus: " + msg);
            }

            JsonNode wallet   = root.path("wallet");
            JsonNode customer = root.path("customer");

            Map<String, String> result = new HashMap<>();
            result.put("accountNumber",      wallet.path("accountNumber").asText());
            result.put("bank",               wallet.path("bankName").asText("Xpress Wallet"));
            result.put("accountName",        wallet.path("accountName").asText());
            result.put("providerCustomerRef", customer.path("id").asText());
            result.put("providerWalletRef",  wallet.path("id").asText());
            result.put("masterWalletRef",    wallet.path("accountReference").asText());

            logger.info("Providus wallet created for {} — account: {}",
                    user.getEmail(), result.get("accountNumber"));
            return result;

        } catch (HttpStatusCodeException e) {
            logger.error("Providus HTTP {} for {}: {}",
                    e.getStatusCode(), user.getEmail(), e.getResponseBodyAsString());
            throw new RuntimeException("Providus wallet creation failed: " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Providus wallet creation error for {}: {}", user.getEmail(), e.getMessage());
            throw new RuntimeException("Providus wallet creation failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Bank lookup and account resolution
    // ─────────────────────────────────────────────────────────────

    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
        requireEnabled();
        String url = baseUrl + "/api/v1/transfer/account/details"
                + "?sortCode=" + bankCode
                + "&accountNumber=" + accountNumber;

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.path("status").asBoolean(false)) {
                String msg = root.path("message").asText("Providus account resolution failed");
                throw new RuntimeException("Providus: " + msg);
            }

            String accountName = root.path("account").path("accountName").asText("");
            if (accountName.isBlank()) {
                throw new RuntimeException("Providus did not return an account name");
            }
            return accountName;

        } catch (HttpStatusCodeException e) {
            logger.error("Providus account resolution HTTP {} for {}/{}: {}",
                    e.getStatusCode(), bankCode, accountNumber, e.getResponseBodyAsString());
            throw new RuntimeException("Providus account resolution failed: " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Providus account resolution error for {}/{}: {}",
                    bankCode, accountNumber, e.getMessage());
            throw new RuntimeException("Providus account resolution failed", e);
        }
    }

    // @Cacheable removed — same reason as SecureWavePaymentProvider: Spring caches
    // empty lists on upstream failure, locking users out of the bank list for hours.
    // Stale-on-error is handled by WalletService._lastKnownBanks instead.
    @Override
    public List<Map<String, Object>> getSupportedBanks() {
        requireEnabled();
        String url = baseUrl + "/api/v1/transfer/banks";

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.path("status").asBoolean(false)) {
                logger.error("Providus bank list failed: {}",
                        root.path("message").asText("unknown provider error"));
                return Collections.emptyList();
            }

            JsonNode banks = root.path("banks");
            if (!banks.isArray()) {
                logger.error("Providus bank list response did not contain a banks array");
                return Collections.emptyList();
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode bank : banks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", bank.path("code").asText());
                item.put("name", bank.path("name").asText());
                result.add(item);
            }
            return result;

        } catch (HttpStatusCodeException e) {
            logger.error("Providus bank list HTTP {}: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("Providus bank list error: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    @Override
    public String initiateTransfer(String bankCode, String accountNumber, String accountName,
                                   BigDecimal amount, String uniqueReference, String narration) {
        requireEnabled();
        throw new UnsupportedOperationException("Providus transfer not yet implemented");
    }

    public String initiateCustomerBankTransfer(String customerId,
                                               String bankCode,
                                               String accountNumber,
                                               String accountName,
                                               String senderName,
                                               BigDecimal amount,
                                               String reference,
                                               String narration) {
        requireEnabled();
        String resolvedCustomerId = fetchCustomerId(customerId);
        String url = baseUrl + "/api/v1/transfer/bank/customer";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("reference", reference);
        body.put("sortCode", bankCode);
        body.put("narration", narration);
        body.put("accountNumber", accountNumber);
        body.put("accountName", accountName);
        body.put("senderName", senderName);
        body.put("customerId", resolvedCustomerId);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.path("status").asBoolean(false)) {
                String msg = root.path("message").asText("Providus customer bank transfer failed");
                throw new RuntimeException("Providus: " + msg);
            }

            String providerReference = root.path("transfer").path("reference").asText("");
            if (providerReference.isBlank()) {
                providerReference = root.path("transfer").path("transactionReference").asText("");
            }
            if (providerReference.isBlank()) {
                throw new RuntimeException("Providus customer bank transfer completed without a reference");
            }
            return providerReference;

        } catch (HttpStatusCodeException e) {
            logger.error("Providus customer bank transfer HTTP {} from customer {} to {}/{}: {}",
                    e.getStatusCode(), customerId, accountNumber, bankCode, e.getResponseBodyAsString());
            throw new RuntimeException("Providus customer bank transfer failed: " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Providus customer bank transfer error from customer {} to {}/{}: {}",
                    customerId, accountNumber, bankCode, e.getMessage());
            throw new RuntimeException("Providus customer bank transfer failed", e);
        }
    }

    public String initiateWalletTransfer(String fromCustomerId, String toAccountNumber, BigDecimal amount) {
        requireEnabled();
        String resolvedFromCustomerId = fetchCustomerId(fromCustomerId);
        String toCustomerId = findCustomerIdByWalletAccountNumber(toAccountNumber);
        String url = baseUrl + "/api/v1/transfer/wallet";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("fromCustomerId", resolvedFromCustomerId);
        body.put("toCustomerId", toCustomerId);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.path("status").asBoolean(false)) {
                String msg = root.path("message").asText("Providus wallet transfer failed");
                throw new RuntimeException("Providus: " + msg);
            }

            String reference = root.path("data").path("reference").asText("");
            if (reference.isBlank()) {
                throw new RuntimeException("Providus wallet transfer completed without a reference");
            }
            return reference;

        } catch (HttpStatusCodeException e) {
            logger.error("Providus wallet transfer HTTP {} from {} to account {}: {}",
                    e.getStatusCode(), fromCustomerId, toAccountNumber, e.getResponseBodyAsString());
            throw new RuntimeException("Providus wallet transfer failed: " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Providus wallet transfer error from {} to account {}: {}",
                    fromCustomerId, toAccountNumber, e.getMessage());
            throw new RuntimeException("Providus wallet transfer failed", e);
        }
    }

    public String fetchCustomerId(String customerId) {
        requireEnabled();
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Providus customer id is required");
        }

        String url = baseUrl + "/api/v1/customer/" + customerId.trim();

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.path("status").asBoolean(false)) {
                String msg = root.path("message").asText("Providus customer lookup failed");
                throw new RuntimeException("Providus: " + msg);
            }

            String resolvedCustomerId = root.path("customer").path("id").asText("");
            if (resolvedCustomerId.isBlank()) {
                throw new RuntimeException("Providus customer lookup did not return an id");
            }
            return resolvedCustomerId;

        } catch (HttpStatusCodeException e) {
            logger.error("Providus customer lookup HTTP {} for {}: {}",
                    e.getStatusCode(), customerId, e.getResponseBodyAsString());
            throw new RuntimeException("Providus customer lookup failed: " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Providus customer lookup error for {}: {}", customerId, e.getMessage());
            throw new RuntimeException("Providus customer lookup failed", e);
        }
    }

    public void updateCustomerProfile(String customerId, Map<String, Object> updates) {
        requireEnabled();
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Providus customer id is required");
        }
        if (updates == null || updates.isEmpty()) {
            return;
        }

        String url = baseUrl + "/api/v1/customer/" + customerId.trim();

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.PUT,
                    new HttpEntity<>(updates, buildHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.path("status").asBoolean(false)) {
                String msg = root.path("message").asText("Providus customer profile update failed");
                throw new RuntimeException("Providus: " + msg);
            }

        } catch (HttpStatusCodeException e) {
            logger.error("Providus customer profile update HTTP {} for {}: {}",
                    e.getStatusCode(), customerId, e.getResponseBodyAsString());
            throw new RuntimeException("Providus customer profile update failed: " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Providus customer profile update error for {}: {}", customerId, e.getMessage());
            throw new RuntimeException("Providus customer profile update failed", e);
        }
    }

    public Map<String, Object> getCustomerTransactions(String customerId, int page, int perPage) {
        requireEnabled();
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Providus customer id is required");
        }

        int boundedPage = Math.max(page, 1);
        int boundedPerPage = Math.max(1, Math.min(perPage, 100));
        String url = baseUrl + "/api/v1/transaction/customer"
                + "?customerId=" + encode(customerId.trim())
                + "&page=" + boundedPage
                + "&perPage=" + boundedPerPage;

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class
            );

            Map<String, Object> body = objectMapper.readValue(
                    response.getBody(),
                    new TypeReference<Map<String, Object>>() {}
            );
            if (!Boolean.TRUE.equals(body.get("status"))) {
                throw new RuntimeException("Providus customer transaction list failed");
            }
            return body;

        } catch (HttpStatusCodeException e) {
            logger.error("Providus customer transactions HTTP {} for {}: {}",
                    e.getStatusCode(), customerId, e.getResponseBodyAsString());
            throw new RuntimeException("Providus customer transactions failed: " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Providus customer transactions error for {}: {}", customerId, e.getMessage());
            throw new RuntimeException("Providus customer transactions failed", e);
        }
    }

    public Map<String, Object> getTransactionDetails(String transactionReference) {
        requireEnabled();
        if (transactionReference == null || transactionReference.isBlank()) {
            throw new IllegalArgumentException("Transaction reference is required");
        }

        String url = baseUrl + "/api/v1/merchant/transaction/" + encode(transactionReference.trim());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class
            );

            Map<String, Object> body = objectMapper.readValue(
                    response.getBody(),
                    new TypeReference<Map<String, Object>>() {}
            );
            if (!Boolean.TRUE.equals(body.get("status"))) {
                throw new RuntimeException("Providus transaction details lookup failed");
            }
            return body;

        } catch (HttpStatusCodeException e) {
            logger.error("Providus transaction details HTTP {} for {}: {}",
                    e.getStatusCode(), transactionReference, e.getResponseBodyAsString());
            throw new RuntimeException("Providus transaction details lookup failed: " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Providus transaction details error for {}: {}", transactionReference, e.getMessage());
            throw new RuntimeException("Providus transaction details lookup failed", e);
        }
    }

    public String findCustomerIdByWalletAccountNumber(String accountNumber) {
        requireEnabled();
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("Recipient wallet account number is required");
        }

        int page = 1;
        int totalPages = 1;
        String normalizedAccountNumber = accountNumber.trim();

        do {
            String url = baseUrl + "/api/v1/wallet?page=" + page + "&perPage=20";

            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        new HttpEntity<>(buildHeaders()),
                        String.class
                );

                JsonNode root = objectMapper.readTree(response.getBody());
                if (!root.path("status").asBoolean(false)) {
                    String msg = root.path("message").asText("Providus wallet list lookup failed");
                    throw new RuntimeException("Providus: " + msg);
                }

                JsonNode wallets = root.path("wallets");
                if (wallets.isArray()) {
                    for (JsonNode wallet : wallets) {
                        if (normalizedAccountNumber.equals(wallet.path("accountNumber").asText())) {
                            String customerId = wallet.path("customerId").asText("");
                            if (customerId.isBlank()) {
                                throw new RuntimeException("Providus wallet did not include a customer id");
                            }
                            return customerId;
                        }
                    }
                }

                totalPages = root.path("metadata").path("totalPages").asInt(page);
                page++;

            } catch (HttpStatusCodeException e) {
                logger.error("Providus wallet lookup HTTP {} for account {}: {}",
                        e.getStatusCode(), accountNumber, e.getResponseBodyAsString());
                throw new RuntimeException("Providus wallet lookup failed: " + e.getStatusCode(), e);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Providus wallet lookup error for account {}: {}", accountNumber, e.getMessage());
                throw new RuntimeException("Providus wallet lookup failed", e);
            }
        } while (page <= totalPages);

        throw new RuntimeException("Providus wallet not found for account number: " + accountNumber);
    }

    @Override
    public boolean updateWithdrawalBankInfo(String email, String bankName, String accountName,
                                            String bankCode, String accountNumber) {
        requireEnabled();
        throw new UnsupportedOperationException("Providus withdrawal bank info update not yet implemented");
    }

    @Override
    public Map<String, Object> getWithdrawalBankInfo(String email) {
        requireEnabled();
        throw new UnsupportedOperationException("Providus withdrawal bank info fetch not yet implemented");
    }

    @Override
    public String initiateWithdrawal(String email, BigDecimal amount, String narration) {
        requireEnabled();
        throw new UnsupportedOperationException("Providus withdrawal not yet implemented");
    }

    // ─────────────────────────────────────────────────────────────
    // Webhook interface methods
    // ─────────────────────────────────────────────────────────────

    /**
     * Validates the Providus webhook signature.
     *
     * <p>Since Providus has not yet documented their signing mechanism, this method
     * operates in two modes:
     * <ul>
     *   <li><b>Secret configured</b> (PROVIDUS_WEBHOOK_SECRET set): validates the
     *       incoming signature against an HMAC-SHA256 of the raw payload.</li>
     *   <li><b>No secret</b>: rejects. The webhook route is public, so an unverified
     *       request could forge a wallet credit.</li>
     * </ul>
     */
    @Override
    public boolean validateWebhookSignature(String signature, String rawPayload) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            logger.error("[PROVIDUS-WEBHOOK][SECURITY] Webhook rejected — PROVIDUS_WEBHOOK_SECRET " +
                    "is not configured, so the request cannot be verified.");
            return false;
        }
        if (signature == null || signature.isBlank()) {
            logger.warn("[PROVIDUS-WEBHOOK] Request received without a signature header — rejected.");
            return false;
        }
        try {
            boolean valid = WebhookSignatures.hexDigestsMatch(
                    computeHmacSha256(rawPayload, webhookSecret), signature);
            if (!valid) {
                // Never log the computed digest — it is a valid signature for this payload.
                logger.warn("[PROVIDUS-WEBHOOK] Signature mismatch — request rejected.");
            }
            return valid;
        } catch (Exception e) {
            logger.error("[PROVIDUS-WEBHOOK] Signature validation threw an exception", e);
            return false;
        }
    }

    /**
     * Extracts the event type from a Providus webhook payload.
     *
     * <p>Tries multiple common field names in priority order since Providus has not
     * yet documented their exact payload structure. The raw payload is intentionally
     * logged by {@code WebhookService} before this is called so the real field name
     * can be confirmed from the first live webhook.
     */
    @Override
    public String extractWebhookEventType(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);

            // Root-level candidates — most PSPs use one of these
            for (String field : new String[]{"eventType", "event", "type", "event_type",
                    "notification_type", "notificationType", "status"}) {
                String val = root.path(field).asText(null);
                if (val != null && !val.isBlank()) {
                    return val.toUpperCase(java.util.Locale.ROOT);
                }
            }
            // Nested inside "data" node
            JsonNode data = root.path("data");
            if (!data.isMissingNode() && !data.isNull()) {
                for (String field : new String[]{"status", "type", "eventType"}) {
                    String val = data.path(field).asText(null);
                    if (val != null && !val.isBlank()) {
                        return val.toUpperCase(java.util.Locale.ROOT);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[PROVIDUS-WEBHOOK] Failed to extract event type: {}", e.getMessage());
        }
        return "UNKNOWN";
    }

    /**
     * Extracts the transaction reference from a Providus webhook payload.
     *
     * <p>Tries multiple common field names and nesting patterns across root,
     * {@code data}, and {@code eventData} nodes.
     */
    @Override
    public String extractWebhookReference(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);

            // Root-level reference candidates
            for (String field : new String[]{"reference", "transactionReference",
                    "transaction_reference", "ref", "transactionId", "transaction_id",
                    "txRef", "tx_ref"}) {
                String val = root.path(field).asText(null);
                if (val != null && !val.isBlank()) return val;
            }
            // Nested inside "data"
            JsonNode data = root.path("data");
            if (!data.isMissingNode() && !data.isNull()) {
                for (String field : new String[]{"reference", "transactionReference",
                        "transaction_reference", "ref", "tx_ref"}) {
                    String val = data.path(field).asText(null);
                    if (val != null && !val.isBlank()) return val;
                }
            }
            // Nested inside "eventData"
            JsonNode eventData = root.path("eventData");
            if (!eventData.isMissingNode() && !eventData.isNull()) {
                String val = eventData.path("transactionReference").asText(null);
                if (val != null && !val.isBlank()) return val;
            }
        } catch (Exception e) {
            logger.warn("[PROVIDUS-WEBHOOK] Failed to extract reference: {}", e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (accessToken != null && !accessToken.isBlank()) {
            headers.set("X-Access-Token", accessToken);
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            headers.set("X-Refresh-Token", refreshToken);
        }
        if ((accessToken == null || accessToken.isBlank()) && merchantApiKey != null && !merchantApiKey.isBlank()) {
            headers.setBearerAuth(merchantApiKey);
        }
        return headers;
    }

    private void requireEnabled() {
        if (!providusIntegrationEnabled) {
            throw new IllegalStateException("Providus integration is disabled. Set MONIEWISE_PSP_PROVIDUS_INTEGRATION_ENABLED=true to enable it.");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String safeString(Object value, String fallback) {
        if (value == null || value.toString().isBlank()) return fallback;
        return value.toString().trim();
    }

    /**
     * Providus expects the international format without the leading '+'.
     * e.g. "08020245369" → "2348020245369"
     */
    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return "";
        String cleaned = phone.replaceAll("[^0-9]", "");
        if (cleaned.startsWith("0")) {
            return "234" + cleaned.substring(1);
        }
        return cleaned;
    }

    private String computeHmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256 for Providus webhook", e);
        }
    }
}
