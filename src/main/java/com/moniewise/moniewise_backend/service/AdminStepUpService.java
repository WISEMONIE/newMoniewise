package com.moniewise.moniewise_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * A second, emailed code that an admin must enter before a risky action,
 * on top of being signed in.
 *
 * <p>Each code belongs to one admin, one action and one target (for example
 * "cancel budget 42"), so it cannot approve anything else. It lives in Redis
 * for 10 minutes, is stored only as a hash, works once, and is thrown away
 * after 5 wrong tries. It is separate from sign-in codes, so a sign-in code
 * can never approve an action. If Redis is down, no code is issued and the
 * action is refused.
 */
@Service
public class AdminStepUpService {

    private static final Logger logger = LoggerFactory.getLogger(AdminStepUpService.class);
    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final int MAX_WRONG_TRIES = 5;

    private final StringRedisTemplate redis;
    private final NotificationService notificationService;
    private final SecureRandom random = new SecureRandom();

    public AdminStepUpService(StringRedisTemplate redis, NotificationService notificationService) {
        this.redis = redis;
        this.notificationService = notificationService;
    }

    /** Thrown when a code cannot be issued or checked. The message is safe to show. */
    public static class StepUpException extends RuntimeException {
        public StepUpException(String message) {
            super(message);
        }
    }

    /**
     * Emails a fresh 6-digit code to the admin for this one action. A new
     * request replaces any earlier code for the same action.
     *
     * @param whatItApproves plain words for the email, e.g. "cancel the budget "Rent" (#42) for ada@x.com"
     */
    public void sendCode(String adminEmail, String action, long targetId, String whatItApproves) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        String key = key(adminEmail, action, targetId);
        try {
            redis.opsForValue().set(key, hash(key, code), CODE_TTL);
            redis.delete(triesKey(key));
        } catch (RuntimeException e) {
            logger.error("[STEP-UP] could not store code for {} {}: {}", action, targetId, e.getMessage());
            throw new StepUpException("Codes can't be sent right now. Try again in a minute.");
        }
        notificationService.sendAdminBroadcastEmail(
                adminEmail,
                "there",
                "Your code to " + whatItApproves,
                "Admin approval",
                "Confirm with this code",
                "Your code is " + code + ".\n\nIt lets you " + whatItApproves
                        + ". It works once and ends in " + CODE_TTL.toMinutes() + " minutes.\n\n"
                        + "If you didn't ask for this, someone may be using your admin account. "
                        + "Change your password and tell the team.",
                "This code was requested from the Wisemonie admin website.",
                null,
                null);
        logger.warn("[STEP-UP] code sent to admin={} action={} target={}", adminEmail, action, targetId);
    }

    /**
     * Checks the code and uses it up. Returns normally when it matches;
     * otherwise throws with a message to show.
     */
    public void verifyAndUse(String adminEmail, String action, long targetId, String code) {
        String key = key(adminEmail, action, targetId);
        String clean = code == null ? "" : code.trim();
        String stored;
        try {
            stored = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            throw new StepUpException("Codes can't be checked right now. Try again in a minute.");
        }
        if (stored == null) {
            throw new StepUpException("No code is waiting, or it has ended. Ask for a new code.");
        }
        boolean match = clean.matches("\\d{6}") && MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), hash(key, clean).getBytes(StandardCharsets.UTF_8));
        if (!match) {
            Long tries = redis.opsForValue().increment(triesKey(key));
            redis.expire(triesKey(key), CODE_TTL);
            if (tries != null && tries >= MAX_WRONG_TRIES) {
                redis.delete(key);
                redis.delete(triesKey(key));
                throw new StepUpException("Too many wrong codes. Ask for a new code.");
            }
            throw new StepUpException("That code is wrong. Check the latest email.");
        }
        // Single use: whoever deletes it first is the one allowed through.
        Boolean deleted = redis.delete(key);
        redis.delete(triesKey(key));
        if (!Boolean.TRUE.equals(deleted)) {
            throw new StepUpException("That code was already used. Ask for a new code.");
        }
    }

    static String key(String adminEmail, String action, long targetId) {
        return "admin:step-up:" + action + ":" + targetId + ":" + adminEmail.trim().toLowerCase(Locale.ROOT);
    }

    private static String triesKey(String key) {
        return key + ":tries";
    }

    /** The code is salted with its own key, so a stored hash is useless for any other action. */
    private static String hash(String key, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((key + "|" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
