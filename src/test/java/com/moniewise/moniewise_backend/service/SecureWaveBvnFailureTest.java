package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.exception.BvnProviderUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecureWaveBvnFailureTest {

    /** What production received on 25 Nov 2026 at 13:00:18. */
    private static final String CONTRADICTORY_400 = "{\"status\":false,\"message\":\"Data Fetched Successfully\","
            + "\"data\":{\"success\":false,\"status\":\"failed\",\"message\":\"Data Fetched Successfully\","
            + "\"reference\":\"01A21293ACDA738DB26BBEAFA391106B\"}}";

    private RestTemplate restTemplate;
    private SecureWavePaymentProvider provider;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        provider = new SecureWavePaymentProvider(restTemplate);
        ReflectionTestUtils.setField(provider, "baseUrl", "https://securewave.test");
        ReflectionTestUtils.setField(provider, "secretKey", "x");
        ReflectionTestUtils.setField(provider, "publicKey", "x");
    }

    private void replyWith(HttpStatus status, String body) {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(),
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    void theContradictory400IsTreatedAsSecureWaveFailingNotAsABadBvn() {
        replyWith(HttpStatus.BAD_REQUEST, CONTRADICTORY_400);

        BvnProviderUnavailableException e = assertThrows(BvnProviderUnavailableException.class,
                () -> provider.verifyBvn("a@b.test", "08030000000", "22222222222"));

        assertTrue(e.getMessage().contains("Data Fetched Successfully"), "the provider's words stay in the logs");
        assertFalse(BvnProviderUnavailableException.CLIENT_MESSAGE.contains("Data Fetched"));
    }

    @Test
    void aClearNotFoundIsStillTheCustomersToFixWithOurWording() {
        replyWith(HttpStatus.BAD_REQUEST, "{\"status\":false,\"message\":\"BVN not found\"}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider.verifyBvn("a@b.test", "08030000000", "22222222222"));

        assertEquals(SecureWavePaymentProvider.BVN_NOT_FOUND_MESSAGE, e.getMessage());
    }

    @Test
    void ourOwnCredentialProblemIsNeverBlamedOnTheCustomer() {
        replyWith(HttpStatus.UNAUTHORIZED, "{\"status\":false,\"message\":\"Invalid API key\"}");

        assertThrows(BvnProviderUnavailableException.class,
                () -> provider.verifyBvn("a@b.test", "08030000000", "22222222222"));
    }

    @Test
    void a200WithStatusFalseAndNoRealReasonAlsoFallsBack() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", false, "message", "Data Fetched Successfully")));

        assertThrows(BvnProviderUnavailableException.class,
                () -> provider.verifyBvn("a@b.test", "08030000000", "22222222222"));
    }

    @Test
    void describeKeepsTheUsefulParts() {
        assertEquals("Data Fetched Successfully (status failed)", SecureWavePaymentProvider.describe(CONTRADICTORY_400));
        assertEquals("not json", SecureWavePaymentProvider.describe("not json"));
        assertEquals("no response body", SecureWavePaymentProvider.describe(""));
    }

    @Test
    void onlyClearWordingCountsAsARejection() {
        assertTrue(SecureWavePaymentProvider.clearlyRejectsBvn("Invalid BVN supplied"));
        assertTrue(SecureWavePaymentProvider.clearlyRejectsBvn("No record found for this BVN"));
        assertFalse(SecureWavePaymentProvider.clearlyRejectsBvn("Data Fetched Successfully (status failed)"));
        assertFalse(SecureWavePaymentProvider.clearlyRejectsBvn(""));
        assertFalse(SecureWavePaymentProvider.clearlyRejectsBvn(null));
    }
}
