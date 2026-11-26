package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.SalaryNudgeWindow;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "salary_nudge_notifications",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_salary_nudges_user_window_period_variation",
                        columnNames = {"user_id", "nudge_window", "period_year", "period_month", "variation_index"}
                ),
                @UniqueConstraint(
                        name = "uk_salary_nudges_user_window_period_date",
                        columnNames = {"user_id", "nudge_window", "period_year", "period_month", "sent_date"}
                )
        },
        indexes = {
                @Index(name = "idx_salary_nudges_user_window_period", columnList = "user_id, nudge_window, period_year, period_month"),
                @Index(name = "idx_salary_nudges_window_sent_at", columnList = "nudge_window, sent_at")
        }
)
public class SalaryNudgeNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "nudge_window", nullable = false, length = 30)
    private SalaryNudgeWindow nudgeWindow;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Column(name = "variation_index", nullable = false)
    private int variationIndex;

    @Column(name = "sent_date", nullable = false)
    private LocalDate sentDate;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
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

    public SalaryNudgeWindow getNudgeWindow() {
        return nudgeWindow;
    }

    public void setNudgeWindow(SalaryNudgeWindow nudgeWindow) {
        this.nudgeWindow = nudgeWindow;
    }

    public int getPeriodYear() {
        return periodYear;
    }

    public void setPeriodYear(int periodYear) {
        this.periodYear = periodYear;
    }

    public int getPeriodMonth() {
        return periodMonth;
    }

    public void setPeriodMonth(int periodMonth) {
        this.periodMonth = periodMonth;
    }

    public int getVariationIndex() {
        return variationIndex;
    }

    public void setVariationIndex(int variationIndex) {
        this.variationIndex = variationIndex;
    }

    public LocalDate getSentDate() {
        return sentDate;
    }

    public void setSentDate(LocalDate sentDate) {
        this.sentDate = sentDate;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
