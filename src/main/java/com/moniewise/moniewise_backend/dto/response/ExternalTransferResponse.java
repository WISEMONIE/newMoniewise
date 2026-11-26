package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.enums.TransactionStatus;

import java.math.BigDecimal;

public class ExternalTransferResponse {
    private final String clientReference;
    private final String providerReference;
    private final BigDecimal amount;
    private final String recipientName;
    private final String bankName;
    private final TransactionStatus status;

    public ExternalTransferResponse(
            String clientReference,
            String providerReference,
            BigDecimal amount,
            String recipientName,
            String bankName,
            TransactionStatus status
    ) {
        this.clientReference = clientReference;
        this.providerReference = providerReference;
        this.amount = amount;
        this.recipientName = recipientName;
        this.bankName = bankName;
        this.status = status;
    }

    public String getClientReference() {
        return clientReference;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getBankName() {
        return bankName;
    }

    public TransactionStatus getStatus() {
        return status;
    }
}
