package com.moniewise.moniewise_backend.entity;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "outbox_events")
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "budget_id")
    private Long budgetId;

    @Column(name = "envelope_id")
    private Long envelopeId;

    @Type(type = "jsonb")
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    @Convert(disableConversion = true) // 🔥 THIS IS THE FIX
    private Map<String, Object> payload;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * How long (seconds) this event remains relevant after it was created.
     * The outbox worker will skip (mark STALE) any event where
     * {@code createdAt + ttlSeconds < now}.
     * <p>
     * Typical values:
     * <ul>
     *   <li>PRE_DISBURSEMENT / DISBURSEMENT_REMINDER → 2,700 s (45 min)</li>
     *   <li>DISBURSEMENT_SUCCESS / wallet credits     → 259,200 s (72 h)</li>
     *   <li>BUDGET_END_SOON / warnings                → 86,400 s (24 h)</li>
     * </ul>
     * NULL means "no expiry" (always deliver).
     */
    @Column(name = "ttl_seconds")
    private Long ttlSeconds;

    /** True once the in-app inbox row has been written for this event — makes a
     *  retry skip re-inserting it (idempotent inbox). */
    @Column(name = "inbox_saved", nullable = false)
    private boolean inboxSaved = false;

    /** Device FCM tokens already pushed for this event ("||"-joined) so a retry
     *  never re-pushes to a device that already got it (idempotent push). */
    @Column(name = "delivered_tokens", columnDefinition = "TEXT")
    private String deliveredTokens;

    // getters and setters
}
