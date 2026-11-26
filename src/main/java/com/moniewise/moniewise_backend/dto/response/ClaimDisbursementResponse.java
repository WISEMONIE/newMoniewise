package com.moniewise.moniewise_backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ClaimDisbursementResponse(
        Long disbursementId,
        String envelopeName,
        BigDecimal amount,
        LocalDateTime claimedAt,
        BigDecimal envelopeTotalRemaining,
        BigDecimal budgetRemaining,
        String message
) {}