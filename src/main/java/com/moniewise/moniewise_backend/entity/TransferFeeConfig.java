package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.TransferFeeSource;
import com.moniewise.moniewise_backend.enums.TransferFeeTransferType;
import com.moniewise.moniewise_backend.enums.TransferFeeType;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transfer_fee_configs")
public class TransferFeeConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_name", nullable = false)
    private String providerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", nullable = false)
    private TransferFeeTransferType transferType;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_type", nullable = false)
    private TransferFeeType feeType;

    @Column(name = "flat_fee", nullable = false)
    private BigDecimal flatFee = BigDecimal.ZERO;

    @Column(name = "percentage_fee", nullable = false)
    private BigDecimal percentageFee = BigDecimal.ZERO;

    @Column(name = "min_fee")
    private BigDecimal minFee;

    @Column(name = "max_fee")
    private BigDecimal maxFee;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_source", nullable = false)
    private TransferFeeSource feeSource;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getProviderName() {
        return providerName;
    }

    public TransferFeeTransferType getTransferType() {
        return transferType;
    }

    public TransferFeeType getFeeType() {
        return feeType;
    }

    public BigDecimal getFlatFee() {
        return flatFee;
    }

    public BigDecimal getPercentageFee() {
        return percentageFee;
    }

    public BigDecimal getMinFee() {
        return minFee;
    }

    public BigDecimal getMaxFee() {
        return maxFee;
    }

    public TransferFeeSource getFeeSource() {
        return feeSource;
    }

    public boolean isActive() {
        return active;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public void setTransferType(TransferFeeTransferType transferType) {
        this.transferType = transferType;
    }

    public void setFeeType(TransferFeeType feeType) {
        this.feeType = feeType;
    }

    public void setFlatFee(BigDecimal flatFee) {
        this.flatFee = flatFee;
    }

    public void setPercentageFee(BigDecimal percentageFee) {
        this.percentageFee = percentageFee;
    }

    public void setMinFee(BigDecimal minFee) {
        this.minFee = minFee;
    }

    public void setMaxFee(BigDecimal maxFee) {
        this.maxFee = maxFee;
    }

    public void setFeeSource(TransferFeeSource feeSource) {
        this.feeSource = feeSource;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}