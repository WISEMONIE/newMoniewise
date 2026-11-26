package com.moniewise.moniewise_backend.entity;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "engagement_special_occasions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_engagement_special_occasions_key_date",
                columnNames = {"occasion_key", "occasion_date"}
        ),
        indexes = {
                @Index(name = "idx_engagement_special_occasions_date_active", columnList = "occasion_date, active")
        }
)
public class EngagementSpecialOccasion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occasion_key", nullable = false, length = 100)
    private String occasionKey;

    @Column(name = "display_name", nullable = false, length = 140)
    private String displayName;

    @Column(name = "occasion_date", nullable = false)
    private LocalDate occasionDate;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "priority", nullable = false)
    private int priority = 50;

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

    public String getOccasionKey() {
        return occasionKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public LocalDate getOccasionDate() {
        return occasionDate;
    }

    public boolean isActive() {
        return active;
    }

    public int getPriority() {
        return priority;
    }
}
