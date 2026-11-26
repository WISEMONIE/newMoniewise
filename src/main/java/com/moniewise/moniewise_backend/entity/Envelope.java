//package com.moniewise.moniewise_backend.entity;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
//import lombok.Getter;
//import lombok.NoArgsConstructor;
//import lombok.Setter;
//import org.hibernate.annotations.Type;
//import org.hibernate.annotations.TypeDef;
//
//import javax.persistence.*;
//import java.math.BigDecimal;
//import java.time.LocalDateTime;
//import java.util.Map;
//
//@Entity
//@Getter
//@Setter
//@NoArgsConstructor
//@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
//@Table(name = "envelopes")
//public class Envelope {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "budget_id", nullable = false)
//    private Budget budget;
//
//    @Column(nullable = false)
//    private String name;
//
//    @Column(nullable = false)
//    private BigDecimal amount;
//
//    @Column(name = "remaining_amount", nullable = false)
//    private BigDecimal remainingAmount; // Tracks current period's remaining limit
//
//    @Column(name = "total_remaining_amount", nullable = false)
//    private BigDecimal totalRemainingAmount; // Tracks total unspent balance
//
//    @Convert(disableConversion = true) // Disable auto-converter
//    @Type(type = "jsonb")
//    @Column(columnDefinition = "jsonb")
//    private Map<String, Object> conditions;
//
//    @Column(name = "created_at")
//    private LocalDateTime createdAt;
//
//    @Column(name = "last_accessed")
//    private LocalDateTime lastAccessed;
//
//    @Column(name = "last_disbursed_at")
//    private LocalDateTime lastDisbursedAt;
//
//    @Column(name = "next_disbursement_at")
//    private LocalDateTime nextDisbursementAt;
//
//    @Column(name = "matured_at")
//    private LocalDateTime maturedAt;
//
//    @Column(name = "has_matured")
//    private Boolean hasMatured;
//
//    @Column(name = "deleted_at")
//    private LocalDateTime deletedAt;
//
//    @Column(name = "initial_amount", nullable = false)
//    private BigDecimal initialAmount;
//
//    // If you are not using Lombok (@Data), you must also add:
//    public void setInitialAmount(BigDecimal initialAmount) {
//        this.initialAmount = initialAmount;
//    }
//
//    public BigDecimal getInitialAmount() {
//        return this.initialAmount;
//    }
//    public boolean isDeleted() {
//        return deletedAt != null;
//    }
//
//    public void markAsDeleted() {
//        this.deletedAt = LocalDateTime.now();
//    }
//
//    private static final ObjectMapper mapper = new ObjectMapper();
//
//    public Envelope(Budget budget, String name, BigDecimal amount, Map<String, Object> conditions) {
//        this.budget = budget;
//        this.name = name;
//        this.amount = amount;
//        this.initialAmount = amount;
//        this.totalRemainingAmount = amount;
//        this.remainingAmount = getPeriodLimit(conditions);
//        this.conditions = conditions;
//        this.createdAt = LocalDateTime.now();
//        this.hasMatured = false;
//    }
//
//    private BigDecimal getPeriodLimit(Map<String, Object> conditions) {
//        if (conditions != null && conditions.containsKey("limit") && conditions.get("limit") instanceof Number) {
//            return new BigDecimal(((Number) conditions.get("limit")).doubleValue());
//        }
//        return BigDecimal.ZERO;
//    }
//}

package com.moniewise.moniewise_backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Getter
@Setter
@NoArgsConstructor
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
@Table(name = "envelopes")
public class Envelope {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id", nullable = false)
    @JsonIgnore // 👈 ADD THIS. Prevents infinite loop (Envelope -> Budget -> Envelope...)
    private Budget budget;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal amount;          // The Current Vault Balance

    @Column(name = "remaining_amount", nullable = false)
    private BigDecimal remainingAmount; // The Spendable Wallet (Starts at 0.00)

    @Column(name = "total_remaining_amount", nullable = false)
    private BigDecimal totalRemainingAmount; // The Total Unspent (Vault)

    @Column(name = "initial_amount", nullable = false)
    private BigDecimal initialAmount;   // The Original Goal (For Progress Bars)

    @Convert(disableConversion = true)
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> conditions;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_accessed")
    private LocalDateTime lastAccessed;

    @Column(name = "last_disbursed_at")
    private LocalDateTime lastDisbursedAt;

    @Column(name = "next_disbursement_at")
    private LocalDateTime nextDisbursementAt;

    @Column(name = "matured_at")
    private LocalDateTime maturedAt;

    @Column(name = "has_matured")
    private Boolean hasMatured;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private BigDecimal heldAmount = BigDecimal.ZERO;

    @Column(name = "is_automated", nullable = false)
    private Boolean isAutomated = false;

    // Soft Delete Helper
    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void markAsDeleted() {
        this.deletedAt = LocalDateTime.now();
    }

    // =================================================================
    //  STRICT MODE CONSTRUCTOR
    // =================================================================
    public Envelope(Budget budget, String name, BigDecimal amount, Map<String, Object> conditions) {
        this.budget = budget;
        this.name = name;

        // 1. Set the Vault Values
        this.amount = amount;
        this.initialAmount = amount;
        this.totalRemainingAmount = amount;

        // 2. Set the Wallet to ZERO (Strict Mode)
        // We do NOT call getPeriodLimit() here. We wait for the Scheduler/Service.
        this.remainingAmount = BigDecimal.ZERO;

        this.conditions = conditions;
        this.createdAt = LocalDateTime.now();
        this.hasMatured = false;
    }
}