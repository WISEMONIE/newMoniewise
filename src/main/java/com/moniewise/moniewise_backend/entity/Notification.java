package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notifications")
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String message;

    /** Lock-screen title. Null on older rows; redelivery then uses the type title. */
    @Column(length = 255)
    private String title;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private NotificationType type;

    @Column(name = "budget_id")
    private Long budgetId;

    @Column(name = "envelope_id")
    private Long envelopeId;

    @Column(name = "action_type")
    private String actionType; // e.g., "VIEW_ENVELOPE", "CLAIM_DISBURSEMENT", "VIEW_BUDGET"

    @Column(name = "redirect_url")
    private String redirectUrl; // e.g., "/budgets/60/envelopes/543", "/disbursements/34164"

    /**
     * True once Firebase accepts a push for at least one active FCM token.
     * False means the notification was saved to the inbox but no push went out,
     * so it can be swept by {@code NotificationService#redeliverMissedPushes}
     * after the user registers a fresh FCM token.
     */
    @Column(name = "push_sent", nullable = false, columnDefinition = "boolean default false")
    private boolean pushSent = false;
}
