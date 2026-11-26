package com.moniewise.moniewise_backend.dto;

import com.moniewise.moniewise_backend.enums.TransferFeeSource;
import com.moniewise.moniewise_backend.enums.TransferFeeTransferType;
import com.moniewise.moniewise_backend.enums.TransferFeeType;

import java.math.BigDecimal;

public class TransferFeeQuote {

    private String providerName;
    private TransferFeeTransferType transferType;
    private TransferFeeType feeType;
    private TransferFeeSource feeSource;
    private BigDecimal amount;
    private BigDecimal fee;
    private BigDecimal totalDebit;
    private BigDecimal recipientReceives;

    public TransferFeeQuote(
            String providerName,
            TransferFeeTransferType transferType,
            TransferFeeType feeType,
            TransferFeeSource feeSource,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal totalDebit,
            BigDecimal recipientReceives
    ) {
        this.providerName = providerName;
        this.transferType = transferType;
        this.feeType = feeType;
        this.feeSource = feeSource;
        this.amount = amount;
        this.fee = fee;
        this.totalDebit = totalDebit;
        this.recipientReceives = recipientReceives;
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

    public TransferFeeSource getFeeSource() {
        return feeSource;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public BigDecimal getTotalDebit() {
        return totalDebit;
    }

    public BigDecimal getRecipientReceives() {
        return recipientReceives;
    }
}