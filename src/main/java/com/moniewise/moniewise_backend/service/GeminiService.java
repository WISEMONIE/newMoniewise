package com.moniewise.moniewise_backend.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.moniewise.moniewise_backend.config.GeminiProperties;

@Service
public class GeminiService {
    private static final Logger logger = LoggerFactory.getLogger(GeminiService.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final long QUOTA_COOLDOWN_MS = 60_000;

    private final GeminiProperties geminiProperties;
    private final RestTemplate restTemplate;
    private volatile long quotaCooldownUntilEpochMs = 0L;

    public GeminiService(GeminiProperties geminiProperties) {
        this.geminiProperties = geminiProperties;
        this.restTemplate = new RestTemplate(buildRequestFactory());
    }

    @PostConstruct
    public void logConfigurationStatus() {
        logger.info(
            "Gemini config loaded: keyPresent={}, model={}, url={}, connectTimeoutMs={}, readTimeoutMs={}",
            hasConfiguredApiKey(),
            sanitizeValue(geminiProperties.getModel()),
            sanitizeValue(geminiProperties.getUrl()),
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS
        );
    }

    public String generateText(String prompt) {
        long now = System.currentTimeMillis();
        if (now < quotaCooldownUntilEpochMs) {
            long remainingMs = quotaCooldownUntilEpochMs - now;
            throw new IllegalStateException(
                "Gemini quota cooldown active; retry after about " + Math.max(1, remainingMs / 1000) + "s"
            );
        }

        if (geminiProperties.getApiKey() == null || geminiProperties.getApiKey().isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is missing or blank");
        }
        if (geminiProperties.getModel() == null || geminiProperties.getModel().isBlank()) {
            throw new IllegalStateException("gemini.model is missing or blank");
        }
        if (geminiProperties.getUrl() == null || geminiProperties.getUrl().isBlank()) {
            throw new IllegalStateException("gemini.url is missing or blank");
        }

        String endpoint = String.format(
            "%s/%s:generateContent",
            geminiProperties.getUrl(),
            geminiProperties.getModel()
        );

        Map<String, Object> part = new HashMap<>();
        part.put("text", prompt);

        Map<String, Object> content = new HashMap<>();
        content.put("parts", List.of(part));

        Map<String, Object> body = new HashMap<>();
        body.put("contents", List.of(content));
        body.put("generationConfig", Map.of("responseMimeType", "application/json"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", geminiProperties.getApiKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        long startedAt = System.currentTimeMillis();

        logger.info(
            "Calling Gemini model={} endpoint={} promptChars={}",
            geminiProperties.getModel(),
            endpoint,
            prompt == null ? 0 : prompt.length()
        );

        try {
            ResponseEntity<Map> response =
                restTemplate.exchange(endpoint, HttpMethod.POST, entity, Map.class);

            logger.info(
                "Gemini response received status={} durationMs={}",
                response.getStatusCode(),
                System.currentTimeMillis() - startedAt
            );
            return extractText(response.getBody());
        } catch (HttpStatusCodeException e) {
            logger.warn(
                "Gemini HTTP error status={} model={} durationMs={} body={}",
                e.getStatusCode(),
                geminiProperties.getModel(),
                System.currentTimeMillis() - startedAt,
                e.getResponseBodyAsString()
            );
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                quotaCooldownUntilEpochMs = System.currentTimeMillis() + QUOTA_COOLDOWN_MS;
                logger.warn(
                    "Gemini quota cooldown enabled for {}ms after 429 response",
                    QUOTA_COOLDOWN_MS
                );
            }
            throw new RuntimeException("Gemini HTTP request failed with status " + e.getStatusCode(), e);
        } catch (Exception e) {
            logger.warn(
                "Gemini request failed for model={} durationMs={}: {}",
                geminiProperties.getModel(),
                System.currentTimeMillis() - startedAt,
                e.getMessage()
            );
            throw new RuntimeException("Gemini request failed", e);
        }
    }

    private HttpComponentsClientHttpRequestFactory buildRequestFactory() {
        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(10);
        connManager.setDefaultMaxPerRoute(5);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return factory;
    }

    public Map<String, Object> getConfigurationStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("configured", isConfigured());
        status.put("keyPresent", hasConfiguredApiKey());
        status.put("model", sanitizeValue(geminiProperties.getModel()));
        status.put("url", sanitizeValue(geminiProperties.getUrl()));
        status.put("quotaCooldownActive", System.currentTimeMillis() < quotaCooldownUntilEpochMs);
        status.put("quotaCooldownUntilEpochMs", quotaCooldownUntilEpochMs);
        return status;
    }

    public boolean isConfigured() {
        return hasConfiguredApiKey()
            && geminiProperties.getModel() != null
            && !geminiProperties.getModel().isBlank()
            && geminiProperties.getUrl() != null
            && !geminiProperties.getUrl().isBlank();
    }

    private boolean hasConfiguredApiKey() {
        return geminiProperties.getApiKey() != null && !geminiProperties.getApiKey().isBlank();
    }

    private String sanitizeValue(String value) {
        return value == null || value.isBlank() ? "<missing>" : value;
    }

    private String extractText(Map responseBody) {
        try {
            List candidates = (List) responseBody.get("candidates");
            Map firstCandidate = (Map) candidates.get(0);
            Map content = (Map) firstCandidate.get("content");
            List parts = (List) content.get("parts");
            Map firstPart = (Map) parts.get(0);
            return firstPart.get("text").toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract Gemini response text", e);
        }
    }
}
