package com.moniewise.moniewise_backend.aml;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A limit on what a customer can send out while money is reviewed.
 *
 * <p>{@link Reason#LARGE_INFLOW} holds stay until someone on the team clears
 * the review. {@link Reason#FIRST_DAY} holds end on their own, 24 hours after
 * the account opened.
 */
@Entity
@Table(name = "aml_holds", indexes = {
        @Index(name = "idx_aml_holds_user_status", columnList = "user_id,status"),
        @Index(name = "idx_aml_holds_status_started", columnList = "status,started_at")
})
@Getter
@Setter
public class InflowHold {

    public enum Reason { LARGE_INFLOW, FIRST_DAY }

    public enum Status {
        /** Outflows are capped. */
        ACTIVE,
        /** Still capped, and the case has gone to compliance and Rubies. */
        ESCALATED,
        /** Cleared by the team. */
        RELEASED,
        /** A first-day hold that ran its 24 hours. */
        EXPIRED;

        public boolean isHolding() {
            return this == ACTIVE || this == ESCALATED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Reason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /** Most the customer can send out, all channels together, while this holds. */
    @Column(name = "outflow_cap", nullable = false, precision = 19, scale = 2)
    private BigDecimal outflowCap;

    /** What has gone out since the hold started, including attempts that failed later. */
    @Column(name = "outflow_used", nullable = false, precision = 19, scale = 2)
    private BigDecimal outflowUsed = BigDecimal.ZERO;

    /** The credit that started it, and the 24-hour total at that moment. */
    @Column(name = "trigger_reference", length = 120)
    private String triggerReference;

    @Column(name = "trigger_amount", precision = 19, scale = 2)
    private BigDecimal triggerAmount;

    @Column(name = "inflow_total_24h", precision = 19, scale = 2)
    private BigDecimal inflowTotal24h;

    @Column(name = "threshold_used", precision = 19, scale = 2)
    private BigDecimal thresholdUsed;

    /** Worst name check among the credits on this hold. */
    @Enumerated(EnumType.STRING)
    @Column(name = "name_check", length = 20)
    private SenderNameMatcher.Result nameCheck;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** Set for first-day holds only. Large-inflow holds never end on their own. */
    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "customer_contacted_at")
    private LocalDateTime customerContactedAt;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "released_by", length = 200)
    private String releasedBy;

    @Column(name = "release_reason", length = 1000)
    private String releaseReason;

    /** Running notes from the team: calls, evidence, decisions. Newest last. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Version
    private Long version;

    public BigDecimal remaining() {
        BigDecimal left = outflowCap.subtract(outflowUsed);
        return left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    public boolean isHoldingAt(LocalDateTime now) {
        return status.isHolding() && (endsAt == null || now.isBefore(endsAt));
    }

    public void addNote(String author, String text, LocalDateTime at) {
        String line = "[" + at.withNano(0) + "] " + author + ": " + text;
        notes = notes == null || notes.isBlank() ? line : notes + "\n" + line;
    }
}
