package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fee_reserve_estimates")
@Getter
@Setter
public class FeeReserveEstimate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fee_reserve_id", nullable = false)
    private FeeReserve feeReserve;

    @Column(name = "budget_id", nullable = false)
    private Long budgetId;

    @Column(name = "envelope_id", nullable = false)
    private Long envelopeId;

    @Column(name = "envelope_amount", nullable = false)
    private BigDecimal envelopeAmount;

    @Column(name = "estimated_nip_fee", nullable = false)
    private BigDecimal estimatedNipFee = BigDecimal.ZERO;

    @Column(name = "estimated_stamp_duty", nullable = false)
    private BigDecimal estimatedStampDuty = BigDecimal.ZERO;

    @Column(name = "estimated_markup", nullable = false)
    private BigDecimal estimatedMarkup = BigDecimal.ZERO;

    @Column(name = "estimated_total", nullable = false)
    private BigDecimal estimatedTotal = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
