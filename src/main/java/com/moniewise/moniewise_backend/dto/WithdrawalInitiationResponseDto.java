package com.moniewise.moniewise_backend.dto;

import com.moniewise.moniewise_backend.entity.TransactionDecision;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawalInitiationResponseDto {
    private boolean success;
    private TransactionDecision.Decision decision;
    private String errorCode;
    private String errorMessage;
    private Long transactionRequestId;
    private String withdrawalReference;
    private BigDecimal amount;
    private BigDecimal withdrawalAmount;
    private BigDecimal fee;
    private BigDecimal bankCharge;
    private BigDecimal totalDebit;
    private BigDecimal recipientReceives;
    private BigDecimal remainingBalance;
}
