package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * A device a user has completed first-login OTP verification on. Presence of a
 * {@code (userId, deviceId)} row means subsequent logins from that device skip
 * the OTP step — i.e. first-login-per-device 2FA. The {@code deviceId} is the
 * stable per-install id the client sends in the {@code X-Device-Id} header.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "trusted_devices",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_trusted_device_user_device",
                columnNames = {"user_id", "device_id"}))
public class TrustedDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}
