package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Audit record of a budget an admin cancelled on a customer's behalf. */
@Entity
@Table(name = "admin_budget_cancellations",
        uniqueConstraints = @UniqueConstraint(name = "ux_admin_budget_cancellations_budget", columnNames = "budget_id"))
@Getter
@Setter
public class AdminBudgetCancellation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "budget_id", nullable = false)
    private Long budgetId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "budget_name")
    private String budgetName;

    @Column(name = "admin_email", nullable = false, length = 200)
    private String adminEmail;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
