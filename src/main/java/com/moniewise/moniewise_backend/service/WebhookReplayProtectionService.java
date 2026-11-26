package com.moniewise.moniewise_backend.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WebhookReplayProtectionService {

    private final Map<String, LocalDateTime> seenEvents = new ConcurrentHashMap<>();
    private static final Duration TTL = Duration.ofHours(24);

    public boolean registerIfNew(String provider, String payload) {
        purgeExpired();
        String fingerprint = provider + ":" + sha256(payload == null ? "" : payload);
        LocalDateTime expiresAt = LocalDateTime.now().plus(TTL);
        return seenEvents.putIfAbsent(fingerprint, expiresAt) == null;
    }

    private void purgeExpired() {
        LocalDateTime now = LocalDateTime.now();
        Iterator<Map.Entry<String, LocalDateTime>> iterator = seenEvents.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LocalDateTime> entry = iterator.next();
            if (entry.getValue().isBefore(now)) {
                iterator.remove();
            }
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : encoded) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate webhook fingerprint", e);
        }
    }
}