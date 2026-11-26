package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.PendingRegistrationData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Stores pending (pre-OTP-verification) registrations in Redis with a TTL.
 *
 * <p>Key format: {@code pending_registration:{email}}
 * <br>Phone lookup key format: {@code pending_registration_phone:{phone}}
 * <br>Default TTL: {@value #TTL_MINUTES} minutes — long enough for a user to receive
 * and submit their OTP, short enough to keep Redis lean.
 *
 * <p>Nothing is written to PostgreSQL until the OTP is successfully verified.
 */
@Service
public class RegistrationCacheService {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationCacheService.class);

    static final String KEY_PREFIX = "pending_registration:";
    static final String PHONE_KEY_PREFIX = "pending_registration_phone:";
    /** How long a pending registration lives in Redis. */
    static final long TTL_MINUTES = 10L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RegistrationCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Persists {@code data} to Redis under the key {@code pending_registration:{email}}
     * with a {@value #TTL_MINUTES}-minute TTL.  Any existing entry for the same email
     * is overwritten (handles "resend OTP" requests).
     *
     * @throws RuntimeException if serialization or Redis write fails
     */
    public void save(PendingRegistrationData data) {
        try {
            findByEmail(data.getEmail())
                    .map(PendingRegistrationData::getPhone)
                    .filter(this::hasText)
                    .filter(existingPhone -> !existingPhone.equals(data.getPhone()))
                    .ifPresent(existingPhone -> redisTemplate.delete(phoneKey(existingPhone)));

            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(
                    emailKey(data.getEmail()),
                    json,
                    TTL_MINUTES,
                    TimeUnit.MINUTES
            );
            if (hasText(data.getPhone())) {
                redisTemplate.opsForValue().set(
                        phoneKey(data.getPhone()),
                        data.getEmail(),
                        TTL_MINUTES,
                        TimeUnit.MINUTES
                );
            }
            logger.debug("Cached pending registration for {}", data.getEmail());
        } catch (Exception e) {
            logger.error("Failed to cache pending registration for {}", data.getEmail(), e);
            throw new RuntimeException("Temporary storage unavailable. Please try again.", e);
        }
    }

    /**
     * Retrieves and deserializes the pending registration for {@code email}.
     *
     * @return an empty Optional if the key has expired or was never created
     */
    public Optional<PendingRegistrationData> findByEmail(String email) {
        try {
            String json = redisTemplate.opsForValue().get(emailKey(email));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, PendingRegistrationData.class));
        } catch (Exception e) {
            logger.error("Failed to retrieve pending registration for {}", email, e);
            return Optional.empty();
        }
    }

    /**
     * Retrieves a pending registration using the phone submitted during signup.
     */
    public Optional<PendingRegistrationData> findByPhone(String phone) {
        try {
            String email = redisTemplate.opsForValue().get(phoneKey(phone));
            if (email == null || email.isBlank()) {
                return Optional.empty();
            }
            return findByEmail(email);
        } catch (Exception e) {
            logger.error("Failed to retrieve pending registration for phone {}", phone, e);
            return Optional.empty();
        }
    }

    /** Removes the pending registration entry. Call this after successful OTP verification. */
    public void delete(String email) {
        findByEmail(email)
                .map(PendingRegistrationData::getPhone)
                .filter(this::hasText)
                .ifPresent(phone -> redisTemplate.delete(phoneKey(phone)));
        redisTemplate.delete(emailKey(email));
    }

    /** Returns {@code true} if a pending registration key exists (has not expired). */
    public boolean exists(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(emailKey(email)));
    }

    private String emailKey(String email) {
        return KEY_PREFIX + email.toLowerCase().trim();
    }

    private String phoneKey(String phone) {
        return PHONE_KEY_PREFIX + phone.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
