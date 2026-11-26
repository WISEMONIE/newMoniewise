package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findBySessionIdAndRevokedFalse(String sessionId);

    Optional<AuthSession> findByUserEmailAndSessionIdAndRevokedFalse(String email, String sessionId);

    @Query("SELECT DISTINCT s.fcmToken FROM AuthSession s WHERE s.user.id = :userId AND s.revoked = false AND s.fcmToken IS NOT NULL AND s.fcmToken <> ''")
    List<String> findActiveFcmTokensByUserId(@Param("userId") Long userId);

    @Query("SELECT s FROM AuthSession s WHERE s.user.id = :userId AND s.revoked = false AND s.fcmToken IS NOT NULL AND s.fcmToken <> ''")
    List<AuthSession> findActivePushSessionsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE AuthSession s SET s.fcmToken = NULL, s.devicePlatform = NULL WHERE s.sessionId = :sessionId")
    void clearFcmTokenBySessionId(@Param("sessionId") String sessionId);

    @Modifying
    @Query("UPDATE AuthSession s SET s.fcmToken = NULL, s.devicePlatform = NULL WHERE s.fcmToken = :token AND (:excludeSessionId IS NULL OR s.sessionId <> :excludeSessionId)")
    void clearTokenFromOtherSessions(@Param("token") String token, @Param("excludeSessionId") String excludeSessionId);

    @Modifying
    @Query("UPDATE AuthSession s SET s.fcmToken = NULL, s.devicePlatform = NULL WHERE s.fcmToken = :token")
    void clearFcmTokenByToken(@Param("token") String token);

    @Modifying
    @Query("UPDATE AuthSession s SET s.revoked = true, s.revokedAt = :revokedAt, s.fcmToken = NULL, s.devicePlatform = NULL WHERE s.sessionId = :sessionId AND s.revoked = false")
    int revokeSession(@Param("sessionId") String sessionId, @Param("revokedAt") LocalDateTime revokedAt);

    @Modifying
    @Query("UPDATE AuthSession s SET s.revoked = true, s.revokedAt = :revokedAt, s.fcmToken = NULL, s.devicePlatform = NULL WHERE s.user.id = :userId AND s.revoked = false")
    int revokeAllSessionsForUser(@Param("userId") Long userId, @Param("revokedAt") LocalDateTime revokedAt);
}
