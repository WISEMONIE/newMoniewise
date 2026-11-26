package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.BudgetEngagementNudgeType;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "budget_engagement_nudges",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_budget_engagement_nudges_user_type",
                columnNames = {"user_id", "nudge_type"}
        ),
        indexes = {
                @Index(name = "idx_budget_engagement_nudges_user", columnList = "user_id"),
                @Index(name = "idx_budget_engagement_nudges_type_last_sent", columnList = "nudge_type, last_sent_at")
        }
)
public class BudgetEngagementNudge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "nudge_type", nullable = false, length = 40)
    private BudgetEngagementNudgeType nudgeType;

    @Column(name = "first_sent_at", nullable = false)
    private LocalDateTime firstSentAt;

    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt;

    @Column(name = "send_count", nullable = false)
    private int sendCount;

    @Column(name = "last_eligible_at")
    private LocalDateTime lastEligibleAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BudgetEngagementNudgeType getNudgeType() {
        return nudgeType;
    }

    public void setNudgeType(BudgetEngagementNudgeType nudgeType) {
        this.nudgeType = nudgeType;
    }

    public LocalDateTime getFirstSentAt() {
        return firstSentAt;
    }

    public void setFirstSentAt(LocalDateTime firstSentAt) {
        this.firstSentAt = firstSentAt;
    }

    public LocalDateTime getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(LocalDateTime lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    public int getSendCount() {
        return sendCount;
    }

    public void setSendCount(int sendCount) {
        this.sendCount = sendCount;
    }

    public LocalDateTime getLastEligibleAt() {
        return lastEligibleAt;
    }

    public void setLastEligibleAt(LocalDateTime lastEligibleAt) {
        this.lastEligibleAt = lastEligibleAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
