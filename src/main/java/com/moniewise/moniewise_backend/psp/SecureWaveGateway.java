package com.moniewise.moniewise_backend.psp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.service.SecureWavePaymentProvider;
import com.moniewise.moniewise_backend.utils.WebhookSignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class SecureWaveGateway implements PaymentGateway {

    public static final String PROVIDER_NAME = "SECUREWAVE";

    private static final Logger logger = LoggerFactory.getLogger(SecureWaveGateway.class);

    private final SecureWavePaymentProvider delegate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${securewave.secret-key:}")
    private String secureWaveSecretKey;

    public SecureWaveGateway(SecureWavePaymentProvider delegate) {
        this.delegate = delegate;
    }

    // ── Startup validation ────────────────────────────────────────────────────

    /**
     * Fails loudly at startup if the SecureWave secret key is missing.
     *
     * <p>{@link #validateWebhookSignature} returns {@code false} (rejects ALL webhooks)
     * when {@code secureWaveSecretKey} is blank — there is no catch-all mode here.
     * A missing key therefore silently drops every SecureWave webhook, so we surface
     * the misconfiguration as an ERROR at boot time rather than discovering it in prod.
     */
    @PostConstruct
    public void validateConfiguration() {
        if (secureWaveSecretKey == null || secureWaveSecretKey.isBlank()) {
            logger.error("[SecureWave] SECUREWAVE_SECRET_KEY (securewave.secret-key) is not set — " +
                    "validateWebhookSignature() will reject ALL incoming webhooks until this is configured.");
        }
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public Map<String, String> createVirtualAccount(User user) {
        return delegate.createVirtualAccount(user);
    }

    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
        return delegate.resolveAccount(bankCode, accountNumber);
    }

    @Override
    public List<Map<String, Object>> getSupportedBanks() {
        return delegate.getSupportedBanks();
    }

    @Override
    public String initiateTransfer(String bankCode, String accountNumber, String accountName, BigDecimal amount, String uniqueReference, String narration) {
        return delegate.initiateTransfer(bankCode, accountNumber, accountName, amount, uniqueReference, narration);
    }

    @Override
    public boolean updateWithdrawalBankInfo(String email, String bankName, String accountName, String bankCode, String accountNumber) {
        return delegate.updateWithdrawalBankInfo(email, bankName, accountName, bankCode, accountNumber);
    }

    @Override
    public Map<String, Object> getWithdrawalBankInfo(String email) {
        return delegate.getWithdrawalBankInfo(email);
    }

    @Override
    public String initiateWithdrawal(String email, BigDecimal amount, String narration) {
        return delegate.initiateWithdrawal(email, amount, narration);
    }

    @Override
    public String extractWebhookEventType(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(normalizePayload(rawPayload));
            String eventType = root.path("eventType").asText(null);
            if (eventType != null && !eventType.isBlank()) {
                return eventType;
            }
            String notificationStatus = root.path("notification_status").asText(null);
            if (notificationStatus != null && !notificationStatus.isBlank()) {
                return notificationStatus;
            }
        } catch (Exception ignored) {
            // Fallback to UNKNOWN below.
        }
        return "UNKNOWN";
    }

    @Override
    public String extractWebhookReference(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(normalizePayload(rawPayload));
            String transactionId = root.path("transaction_id").asText(null);
            if (transactionId != null && !transactionId.isBlank()) {
                return transactionId;
            }
            String nestedReference = root.path("eventData").path("transactionReference").asText(null);
            if (nestedReference != null && !nestedReference.isBlank()) {
                return nestedReference;
            }
        } catch (Exception ignored) {
            // Fallback to null below.
        }
        return null;
    }

    @Override
    public boolean validateWebhookSignature(String signature, String rawPayload) {
        if (secureWaveSecretKey == null || secureWaveSecretKey.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        String calculatedHash = calculateHmacSha256(normalizePayload(rawPayload), secureWaveSecretKey);
        return WebhookSignatures.hexDigestsMatch(calculatedHash, signature);
    }

    private String normalizePayload(String rawPayload) {
        String cleanPayload = rawPayload == null ? "" : rawPayload.trim();
        if (cleanPayload.startsWith("\"") && cleanPayload.endsWith("\"")) {
            cleanPayload = cleanPayload.substring(1, cleanPayload.length() - 1);
        }
        return cleanPayload.replace("\\\"", "\"");
    }

    private String calculateHmacSha256(String payload, String secret) {
        try {
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);
            byte[] hash = sha256Hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
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
            throw new RuntimeException("Error calculating HMAC for webhook", e);
        }
    }
}
