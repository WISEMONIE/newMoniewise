package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "user_device_tokens",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_device_tokens_fcm_token",
                columnNames = "fcm_token"),
        indexes = {
                @Index(name = "idx_user_device_tokens_user_active", columnList = "user_id, active"),
                @Index(name = "idx_user_device_tokens_session", columnList = "session_id")
        })
public class UserDeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "fcm_token", nullable = false, length = 512)
    private String fcmToken;

    @Column(name = "device_platform", length = 20)
    private String devicePlatform;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    @Column(name = "deactivation_reason", length = 80)
    private String deactivationReason;

    @Column(name = "push_failure_count", nullable = false)
    private int pushFailureCount = 0;

    @Column(name = "push_last_failure_at")
    private LocalDateTime pushLastFailureAt;

    @Column(name = "push_last_failure_code", length = 40)
    private String pushLastFailureCode;
}
