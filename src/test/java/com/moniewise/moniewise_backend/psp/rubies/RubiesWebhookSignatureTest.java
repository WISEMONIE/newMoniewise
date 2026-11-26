package com.moniewise.moniewise_backend.psp.rubies;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RubiesWebhookSignatureTest {

    private static final String PAYLOAD = "{\"event\":\"credit\",\"amount\":\"5000.00\"}";
    private static final String SECRET = "test-webhook-secret";

    private RubiesGateway gatewayWithSecret(String secret) {
        RubiesGateway gateway = new RubiesGateway(null, null, null, null);
        ReflectionTestUtils.setField(gateway, "webhookSecret", secret);
        return gateway;
    }

    private static String hmacSha512Hex(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void aWebhookIsRejectedWhenNoSecretIsConfigured() throws Exception {
        assertFalse(gatewayWithSecret("").validateWebhookSignature(hmacSha512Hex(PAYLOAD, SECRET), PAYLOAD));
        assertFalse(gatewayWithSecret(null).validateWebhookSignature(null, PAYLOAD));
    }

    @Test
    void aWebhookWithoutASignatureIsRejected() {
        assertFalse(gatewayWithSecret(SECRET).validateWebhookSignature(null, PAYLOAD));
        assertFalse(gatewayWithSecret(SECRET).validateWebhookSignature("  ", PAYLOAD));
    }

    @Test
    void aCorrectlySignedWebhookIsAcceptedInEitherHexCase() throws Exception {
        String signature = hmacSha512Hex(PAYLOAD, SECRET);
        assertTrue(gatewayWithSecret(SECRET).validateWebhookSignature(signature, PAYLOAD));
        assertTrue(gatewayWithSecret(SECRET).validateWebhookSignature(signature.toUpperCase(), PAYLOAD));
    }

    @Test
    void aTamperedPayloadIsRejected() throws Exception {
        String signature = hmacSha512Hex(PAYLOAD, SECRET);
        String tampered = PAYLOAD.replace("5000.00", "500000.00");
        assertFalse(gatewayWithSecret(SECRET).validateWebhookSignature(signature, tampered));
    }
}
