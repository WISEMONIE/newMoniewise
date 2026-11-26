package com.moniewise.moniewise_backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SavingsProjectionMessage(
        String type,
        Long userId,
        Long savingsGoalId,
        String goalName,
        BigDecimal targetAmount,
        BigDecimal currentBalance,
        BigDecimal topUpAmount,
        BigDecimal remainingAmount,
        boolean targetReached,
        int depositsNeededAtTopUpAmount,
        int estimatedDaysAtDailyTopUp,
        String durationLabel,
        LocalDate projectedTargetDate,
        LocalDate maturityDate,
        long daysUntilMaturity,
        BigDecimal requiredDailyAmount,
        BigDecimal requiredWeeklyAmount,
        BigDecimal requiredMonthlyAmount,
        String title,
        String message
) {
}
