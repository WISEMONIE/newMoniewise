package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.SubscriptionBillingCycle;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Defines a subscription tier (e.g. "FREE", "PREMIUM").
 *
 * <p>{@code features} is a comma-separated list of {@link com.moniewise.moniewise_backend.enums.PremiumFeature}
 * values — e.g. {@code "UNLIMITED_TRANSFERS,AI_INSIGHTS"}.
 * Kept as plain text so new features can be added without a schema change.
 */
@Entity
@Table(name = "subscription_plans")
@Getter
@Setter
@NoArgsConstructor
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_name", nullable = false, unique = true, length = 50)
    private String planName;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 20)
    private SubscriptionBillingCycle billingCycle = SubscriptionBillingCycle.MONTHLY;

    /**
     * Comma-separated PremiumFeature values.
     * Example: "UNLIMITED_TRANSFERS,AI_INSIGHTS"
     */
    @Column(name = "features", nullable = false, columnDefinition = "TEXT")
    private String features = "";

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
