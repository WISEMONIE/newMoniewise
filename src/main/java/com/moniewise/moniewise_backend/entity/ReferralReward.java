package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "referral_rewards")
@Getter
@Setter
@NoArgsConstructor
public class ReferralReward {

    public static final String PENDING = "PENDING";
    public static final String EARNED = "EARNED";
    public static final String USED = "USED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "referrer_user_id", nullable = false)
    private Long referrerUserId;

    @Column(name = "referred_user_id", nullable = false, unique = true)
    private Long referredUserId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "earned_at")
    private LocalDateTime earnedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "used_budget_id")
    private Long usedBudgetId;

    @Column(name = "amount_waived", precision = 19, scale = 2)
    private BigDecimal amountWaived;
}
