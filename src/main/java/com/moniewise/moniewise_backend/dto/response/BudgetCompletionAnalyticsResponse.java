package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.enums.BudgetStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BudgetCompletionAnalyticsResponse(
        Long budgetId,
        Long userId,
        String budgetName,
        BudgetStatus status,
        LocalDate startDate,
        LocalDate endDate,
        Integer durationDays,
        BigDecimal originalAmount,
        BigDecimal feeAmount,
        BigDecimal budgetAmountAfterFee,
        BigDecimal allocatedAmount,
        BigDecimal usedAmount,
        BigDecimal unusedAmount,
        BigDecimal unclassifiedAmount,
        BigDecimal usageRatePercent,
        BigDecimal unusedRatePercent,
        Integer envelopeCount,
        Integer withdrawalCount,
        BigDecimal averageWithdrawalAmount,
        String busiestWithdrawalPeriod,
        List<EnvelopeUsage> envelopes,
        List<WithdrawalTimeBucket> withdrawalTimeBuckets,
        List<String> insights
) {
    public record EnvelopeUsage(
            Long envelopeId,
            String envelopeName,
            String envelopeType,
            BigDecimal allocatedAmount,
            BigDecimal usedAmount,
            BigDecimal unusedAmount,
            BigDecimal usageRatePercent,
            Integer withdrawalCount
    ) {
    }

    public record WithdrawalTimeBucket(
            String key,
            String label,
            Integer withdrawalCount,
            BigDecimal totalAmount
    ) {
    }
}
