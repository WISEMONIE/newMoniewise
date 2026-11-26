package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.UserDeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, Long> {

    Optional<UserDeviceToken> findByFcmToken(String fcmToken);

    @Query("""
            SELECT DISTINCT t.fcmToken
            FROM UserDeviceToken t
            WHERE t.user.id = :userId
              AND t.active = true
              AND t.fcmToken IS NOT NULL
              AND t.fcmToken <> ''
            """)
    List<String> findActiveFcmTokensByUserId(@Param("userId") Long userId);

    @Query("""
            SELECT t
            FROM UserDeviceToken t
            WHERE t.user.id = :userId
              AND t.active = true
              AND t.fcmToken IS NOT NULL
              AND t.fcmToken <> ''
            ORDER BY t.lastSeenAt DESC
            """)
    List<UserDeviceToken> findActiveByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("""
            UPDATE UserDeviceToken t
            SET t.active = false,
                t.deactivatedAt = :deactivatedAt,
                t.deactivationReason = :reason
            WHERE t.fcmToken = :token
            """)
    int deactivateByFcmToken(@Param("token") String token,
                             @Param("deactivatedAt") LocalDateTime deactivatedAt,
                             @Param("reason") String reason);

    @Modifying
    @Query("""
            UPDATE UserDeviceToken t
            SET t.active = false,
                t.deactivatedAt = :deactivatedAt,
                t.deactivationReason = :reason
            WHERE t.user.id = :userId
              AND t.fcmToken = :token
            """)
    int deactivateByUserIdAndFcmToken(@Param("userId") Long userId,
                                      @Param("token") String token,
                                      @Param("deactivatedAt") LocalDateTime deactivatedAt,
                                      @Param("reason") String reason);

    @Modifying
    @Query("""
            UPDATE UserDeviceToken t
            SET t.active = false,
                t.deactivatedAt = :deactivatedAt,
                t.deactivationReason = :reason
            WHERE t.user.id = :userId
              AND t.sessionId = :sessionId
            """)
    int deactivateByUserIdAndSessionId(@Param("userId") Long userId,
                                       @Param("sessionId") String sessionId,
                                       @Param("deactivatedAt") LocalDateTime deactivatedAt,
                                       @Param("reason") String reason);

    @Modifying
    @Query("""
            UPDATE UserDeviceToken t
            SET t.pushFailureCount = t.pushFailureCount + 1,
                t.pushLastFailureAt = :failedAt,
                t.pushLastFailureCode = :errorCode
            WHERE t.fcmToken = :token
              AND t.active = true
            """)
    int incrementPushFailureCount(@Param("token") String token,
                                  @Param("failedAt") LocalDateTime failedAt,
                                  @Param("errorCode") String errorCode);

    @Modifying
    @Query("""
            UPDATE UserDeviceToken t
            SET t.pushFailureCount = 0,
                t.pushLastFailureAt = NULL,
                t.pushLastFailureCode = NULL
            WHERE t.fcmToken = :token
            """)
    int resetPushFailureCount(@Param("token") String token);
}
