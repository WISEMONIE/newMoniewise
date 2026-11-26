// src/main/java/com/moniewise/moniewise_backend/dto/transaction/TransactionDetailResponse.java
package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionDetailResponse(
        Long id,
        String reference,
        String status,
        String direction,
        String title,
        String fullDescription,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal stampDuty,
        BigDecimal netAmount,
        String sender,
        String recipient,
        TransactionType transactionType,
        LocalDateTime createdAt,

        // Context
        Long budgetId,
        String budgetName,
        String sourceEnvelopeName,
        Long sourceEnvelopeId,
        String targetEnvelopeName,
        Long targetEnvelopeId,

        // External bank transfer details
        String externalAccountId,       // legacy — kept for backward compat
        String externalBankName,        // bank name (e.g. "OPay")
        String externalAccountNumber    // 10-digit account number
) {}
