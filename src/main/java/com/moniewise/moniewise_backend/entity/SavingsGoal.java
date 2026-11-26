package com.moniewise.moniewise_backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.moniewise.moniewise_backend.enums.EventCategory;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "savings_goals")
@DynamicUpdate
@Data
@NoArgsConstructor
public class SavingsGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 🔗 THE RELATIONAL LINK
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore // Prevents infinite loops when sending JSON to the frontend
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal targetAmount;

    @Column(nullable = false)
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal accruedInterest = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal interestRate = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate maturityDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SavingsStatus status = SavingsStatus.ACTIVE;

    /** Set once the last-week email has gone out. The repeating push uses lastPrepReminderDate. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean maturityReminderSent = false;

    /** Set once the "matures tomorrow" reminder (email + push) has gone out — separate flag from the 7-day one. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean maturityEveReminderSent = false;

    /** Wedding, birthday, rent, school, or other. Null on pots created before Events. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private EventCategory category;

    /** Along-the-way reminders: every two weeks, then every week. Not a single 7-day ping. */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean remindWeek = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean remindDayBefore = true;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean remindMorning = false;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean morningReminderSent = false;

    /** Last Lagos day an along-the-way preparation reminder was sent. */
    @Column(name = "last_prep_reminder_date")
    private LocalDate lastPrepReminderDate;

    /** When the person accepted the event lock. Null on older pots. */
    private LocalDateTime termsAcceptedAt;

    /**
     * Last calendar day (Africa/Lagos) the lifecycle job processed this goal.
     * Lets the job run every few hours (self-healing after missed runs / instance
     * sleep) while guaranteeing interest is applied at most once per day.
     */
    @Column(name = "last_processed_date")
    private LocalDate lastProcessedDate;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}