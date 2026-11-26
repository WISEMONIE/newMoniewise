package com.moniewise.moniewise_backend.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tier_profiles")
public class TierProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tier_code", nullable = false, unique = true)
    private String tierCode;

    @Column(name = "daily_limit", nullable = false)
    private BigDecimal dailyLimit;

    @Column(name = "monthly_limit", nullable = false)
    private BigDecimal monthlyLimit;

    @Column(name = "max_transaction_amount", nullable = false)
    private BigDecimal maxTransactionAmount;

    @Column(name = "can_withdraw", nullable = false)
    private boolean canWithdraw = true;

    @Column(nullable = false)
    private boolean active = true;
}