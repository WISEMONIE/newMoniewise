package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.FeeReserveTransactionType;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fee_reserve_transactions")
@Getter
@Setter
public class FeeReserveTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fee_reserve_id", nullable = false)
    private FeeReserve feeReserve;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FeeReserveTransactionType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

    @Column(length = 255)
    private String reference;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
