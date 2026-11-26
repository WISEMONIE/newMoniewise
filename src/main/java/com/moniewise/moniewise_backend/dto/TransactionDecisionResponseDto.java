package com.moniewise.moniewise_backend.dto;

import com.moniewise.moniewise_backend.entity.TransactionDecision;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDecisionResponseDto {
    private Long transactionRequestId;
    private TransactionDecision.Decision decision;
    private String reasonCode;
    private String reasonMessage;
    private boolean requiresConfirmation;
}