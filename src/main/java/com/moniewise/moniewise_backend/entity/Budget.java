//package com.moniewise.moniewise_backend.entity;
//
//import com.moniewise.moniewise_backend.enums.BudgetStatus;
//import lombok.*;
//
//import javax.persistence.*;
//import java.math.BigDecimal;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.List;
//
//@Entity
//@Setter(onMethod_ = @Deprecated) // Optional: warn if used
//@Data
//@Getter
//@NoArgsConstructor
//@AllArgsConstructor
//
//@Table(name = "budgets")
//public class Budget {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "user_id", nullable = false)
//    private User user; // Changed to User entity from Long
//
//    @Column(nullable = false)
//    private String name;
//
//    @Column(name = "original_amount", nullable = false)
//    private BigDecimal originalAmount; // New: User-specified amount (e.g., ₦500,000)
//
//    @Column(name = "fee_amount", nullable = false)
//    private BigDecimal feeAmount; // New: Fee deducted (e.g., ₦100)
//
//    @Column(name = "total_amount", nullable = false)
//    private BigDecimal totalAmount; // Post-fee amount (e.g., ₦499,900)
//
//    @Column(name = "allocated_amount", nullable = false)
//    private BigDecimal allocatedAmount;
//
//    @Column(name = "remaining_amount", nullable = false)
//    private BigDecimal remainingAmount;
//
//    @Column(name = "duration_days", nullable = false)
//    private Integer durationDays;
//
//    @Column(name = "start_date", nullable = false)
//    private LocalDate startDate;
//
//    @Column(name = "end_date", nullable = false)
//    private LocalDate endDate;
//
//    @Column(nullable = false)
//    @Enumerated(EnumType.STRING)
//    private BudgetStatus status = BudgetStatus.DRAFT;
//
//    @Column(name = "created_at")
//    private LocalDateTime createdAt = LocalDateTime.now();
//
//    @Column(name = "last_topup_time")
//    private LocalDateTime lastTopupTime;
//
//    @OneToMany(mappedBy = "budget", cascade = CascadeType.ALL, orphanRemoval = true)
//    private List<Envelope> envelopes = new ArrayList<>();
//
//    // ========== SAFE COLLECTION METHODS ==========
//    public void clearEnvelopes() {
//        this.envelopes.clear();
//    }
//
//    public void addEnvelope(Envelope envelope) {
//        this.envelopes.add(envelope);
//        envelope.setBudget(this);
//    }
//
//    public void addAllEnvelopes(List<Envelope> envelopes) {
//        envelopes.forEach(this::addEnvelope);
//    }
//    // =============================================
//
//}

package com.moniewise.moniewise_backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
    name = "budgets",
    indexes = {
        // Covers every query that filters by user_id (dashboard summary, active budget
        // lookup, budget list). Without this index Postgres/MySQL does a full table scan
        // for every user — gets noticeably slow once the budgets table grows past ~10k rows.
        @Index(name = "idx_budgets_user_id", columnList = "user_id"),
        // Covers the most common compound filter: user's budgets in a given status.
        // Used by findByUserIdAndStatus, findTopByUserIdAndStatusOrderByCreatedAtDesc.
        @Index(name = "idx_budgets_user_status", columnList = "user_id, status")
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @Setter
    private User user;

    @Column(nullable = false)
    @Setter
    private String name;

    @Column(name = "original_amount", nullable = false)
    @Setter
    private BigDecimal originalAmount;

    @Column(name = "fee_amount", nullable = false)
    @Setter
    private BigDecimal feeAmount;

    @Column(name = "total_amount", nullable = false)
    @Setter
    private BigDecimal totalAmount;

    @Column(name = "allocated_amount", nullable = false)
    @Setter
    private BigDecimal allocatedAmount;

    @Column(name = "remaining_amount", nullable = false)
    @Setter
    private BigDecimal remainingAmount;

    @Column(name = "duration_days", nullable = false)
    @Setter
    private Integer durationDays;

    @Column(name = "start_date", nullable = false)
    @Setter
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    @Setter
    private LocalDate endDate;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Setter
    private BudgetStatus status = BudgetStatus.DRAFT;

    @Column(name = "created_at")
    @Setter
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_topup_time")
    @Setter
    private LocalDateTime lastTopupTime;

    @OneToMany(mappedBy = "budget", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore // 👈 ADD THIS. Prevents loading envelopes when just listing budgets.
    private List<Envelope> envelopes = new ArrayList<>();

    // SAFE METHODS ONLY
    public void clearEnvelopes() {
        this.envelopes.clear();
    }

    public void addEnvelope(Envelope envelope) {
        if (envelope != null) {
            this.envelopes.add(envelope);
            envelope.setBudget(this);
        }
    }

    public void addAllEnvelopes(List<Envelope> envelopes) {
        if (envelopes != null) {
            envelopes.forEach(this::addEnvelope);
        }
    }
}