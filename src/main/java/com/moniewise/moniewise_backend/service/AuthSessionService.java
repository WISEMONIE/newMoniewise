package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.AuthSession;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.UserDeviceToken;
import com.moniewise.moniewise_backend.repository.AuthSessionRepository;
import com.moniewise.moniewise_backend.repository.UserDeviceTokenRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthSessionService {

    private static final Logger logger = LoggerFactory.getLogger(AuthSessionService.class);

    private static final int DEAD_TOKEN_STRIKE_THRESHOLD = 3;

    private final AuthSessionRepository authSessionRepository;
    private final UserRepository userRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;

    public AuthSessionService(AuthSessionRepository authSessionRepository,
                              UserRepository userRepository,
                              UserDeviceTokenRepository userDeviceTokenRepository) {
        this.authSessionRepository = authSessionRepository;
        this.userRepository = userRepository;
        this.userDeviceTokenRepository = userDeviceTokenRepository;
    }

    @Transactional
    public String createSession(User user) {
        // Serialize session rotation per user so duplicate/retried login requests
        // queue instead of inserting two active rows.
        User lockedUser = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalStateException("User not found while creating session"));

        LocalDateTime now = LocalDateTime.now();
        authSessionRepository.revokeAllSessionsForUser(lockedUser.getId(), now);

        AuthSession session = new AuthSession();
        session.setUser(lockedUser);
        session.setSessionId(UUID.randomUUID().toString());
        session.setCreatedAt(now);
        session.setLastSeenAt(now);
        session.setRevoked(false);
        authSessionRepository.save(session);
        return session.getSessionId();
    }

    public record PushTarget(String token, String devicePlatform) {}

    @Transactional(readOnly = true)
    public boolean isSessionActive(String email, String sessionId) {
        return authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId).isPresent();
    }

    @Transactional
    public void revokeSession(String sessionId) {
        // Session auth and device push reachability are intentionally separate.
        // A timed-out/login-rotated session must not make the device miss financial
        // alerts while the app is closed. Dead tokens are removed only after Firebase
        // rejects them in NotificationService/AuthSessionService.clearDeadFcmToken.
        authSessionRepository.revokeSession(sessionId, LocalDateTime.now());
    }

    @Transactional
    public void attachFcmToken(String email, String sessionId, String token) {
        attachFcmToken(email, sessionId, token, null, null);
    }

    @Transactional
    public void attachFcmToken(String email, String sessionId, String token, String devicePlatform) {
        attachFcmToken(email, sessionId, token, devicePlatform, null);
    }

    @Transactional
    public void attachFcmToken(String email, String sessionId, String token, String devicePlatform, String deviceId) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken == null) {
            throw new IllegalArgumentException("FCM token is required");
        }

        String normalizedPlatform = normalizeDevicePlatform(devicePlatform);
        AuthSession session = authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId)
                .orElse(null);

        if (session != null) {
            authSessionRepository.clearTokenFromOtherSessions(normalizedToken, sessionId);
            session.setFcmToken(normalizedToken);
            session.setDevicePlatform(normalizedPlatform);
            session.setLastSeenAt(LocalDateTime.now());
            authSessionRepository.save(session);
            upsertDeviceToken(session.getUser(), sessionId, normalizedToken, normalizedPlatform, normalizeDeviceId(deviceId));
        } else {
            logger.warn("[FCM] No active session for email={} sessionId={}. Upserting device token via user lookup.", email, sessionId);
            User user = userRepository.findByEmail(email).orElse(null);
            if (user != null) {
                upsertDeviceToken(user, sessionId, normalizedToken, normalizedPlatform, normalizeDeviceId(deviceId));
            } else {
                logger.error("[FCM] Cannot attach token — no user found for email={}", email);
            }
        }
    }

    @Transactional
    public void clearSessionFcmToken(String email, String sessionId) {
        AuthSession session = authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId)
                .orElseThrow(() -> new IllegalStateException("Active session not found"));
        session.setFcmToken(null);
        session.setDevicePlatform(null);
        session.setLastSeenAt(LocalDateTime.now());
        authSessionRepository.save(session);
    }

    @Transactional
    public String clearSessionFcmTokenByValue(String email, String sessionId, String token) {
        AuthSession session = authSessionRepository.findByUserEmailAndSessionIdAndRevokedFalse(email, sessionId)
                .orElseThrow(() -> new IllegalStateException("Active session not found"));

        String requestedToken = normalizeToken(token);
        String currentToken = normalizeToken(session.getFcmToken());
        if (requestedToken == null || requestedToken.equals(currentToken)) {
            session.setFcmToken(null);
            session.setDevicePlatform(null);
            session.setLastSeenAt(LocalDateTime.now());
            authSessionRepository.save(session);
            return requestedToken != null ? requestedToken : currentToken;
        }
        return null;
    }

    @Transactional(readOnly = true)
    public List<String> getActiveFcmTokens(Long userId) {
        return getActivePushTargets(userId).stream()
                .map(PushTarget::token)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PushTarget> getActivePushTargets(Long userId) {
        Map<String, PushTarget> targets = new LinkedHashMap<>();

        for (UserDeviceToken token : userDeviceTokenRepository.findActiveByUserId(userId)) {
            addTarget(targets, token.getFcmToken(), token.getDevicePlatform());
        }
        for (AuthSession session : authSessionRepository.findActivePushSessionsByUserId(userId)) {
            addTarget(targets, session.getFcmToken(), session.getDevicePlatform());
        }

        String fallbackToken = userRepository.findFcmTokenById(userId);
        boolean hadTargetsBefore = !targets.isEmpty();
        addTarget(targets, fallbackToken, null);

        if (!hadTargetsBefore && !targets.isEmpty()) {
            logger.warn("[FCM] User {} reachable only via legacy User.fcmToken — migrate to UserDeviceToken.", userId);
        }

        return List.copyOf(targets.values());
    }

    @Transactional
    public void clearDeadFcmToken(String token) {
        clearDeadFcmToken(token, "UNREGISTERED");
    }

    @Transactional
    public void clearDeadFcmToken(String token, String errorCode) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken == null) {
            return;
        }

        UserDeviceToken deviceToken = userDeviceTokenRepository.findByFcmToken(normalizedToken).orElse(null);
        int failureCount = deviceToken != null ? deviceToken.getPushFailureCount() + 1 : DEAD_TOKEN_STRIKE_THRESHOLD;
        String tokenPrefix = normalizedToken.length() > 12 ? normalizedToken.substring(0, 12) + "..." : normalizedToken;

        if (failureCount < DEAD_TOKEN_STRIKE_THRESHOLD) {
            userDeviceTokenRepository.incrementPushFailureCount(normalizedToken, LocalDateTime.now(), errorCode);
            logger.warn("[FCM] Token {} strike {}/{} (code={}). Keeping active — will deactivate at threshold.",
                    tokenPrefix, failureCount, DEAD_TOKEN_STRIKE_THRESHOLD, errorCode);
            return;
        }

        logger.warn("[FCM] Token {} reached {} strikes (code={}). Deactivating from all sources.",
                tokenPrefix, failureCount, errorCode);
        userDeviceTokenRepository.deactivateByFcmToken(normalizedToken, LocalDateTime.now(), "FIREBASE_DEAD_TOKEN:" + errorCode);
        authSessionRepository.clearFcmTokenByToken(normalizedToken);
        userRepository.clearFcmTokenByToken(normalizedToken);
    }

    @Transactional
    public void recordPushSuccess(String token) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken == null) {
            return;
        }
        userDeviceTokenRepository.resetPushFailureCount(normalizedToken);
    }

    private void upsertDeviceToken(User user, String sessionId, String token, String devicePlatform, String deviceId) {
        LocalDateTime now = LocalDateTime.now();
        UserDeviceToken deviceToken = userDeviceTokenRepository.findByFcmToken(token)
                .orElseGet(() -> {
                    UserDeviceToken created = new UserDeviceToken();
                    created.setFcmToken(token);
                    created.setCreatedAt(now);
                    return created;
                });

        deviceToken.setUser(user);
        deviceToken.setSessionId(sessionId);
        deviceToken.setDevicePlatform(devicePlatform);
        deviceToken.setDeviceId(deviceId);
        deviceToken.setActive(true);
        deviceToken.setLastSeenAt(now);
        deviceToken.setDeactivatedAt(null);
        deviceToken.setDeactivationReason(null);
        deviceToken.setPushFailureCount(0);
        deviceToken.setPushLastFailureAt(null);
        deviceToken.setPushLastFailureCode(null);
        userDeviceTokenRepository.save(deviceToken);
    }

    private void deactivateDeviceToken(Long userId, String token, String reason) {
        userDeviceTokenRepository.deactivateByUserIdAndFcmToken(userId, token, LocalDateTime.now(), reason);
    }

    private void deactivateDeviceTokensForSession(Long userId, String sessionId, String reason) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        userDeviceTokenRepository.deactivateByUserIdAndSessionId(userId, sessionId, LocalDateTime.now(), reason);
    }

    private void addTarget(Map<String, PushTarget> targets, String token, String devicePlatform) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken != null) {
            targets.putIfAbsent(normalizedToken, new PushTarget(normalizedToken, normalizeDevicePlatform(devicePlatform)));
        }
    }

    private String normalizeToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeDeviceId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private String normalizeDevicePlatform(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ANDROID" -> "ANDROID";
            case "IOS", "IPHONE", "IPAD", "IPADOS" -> "IOS";
            default -> null;
        };
    }
}
