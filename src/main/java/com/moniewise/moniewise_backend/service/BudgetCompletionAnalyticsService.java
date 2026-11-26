package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.response.BudgetCompletionAnalyticsResponse;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BudgetCompletionAnalyticsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final Set<TransactionStatus> MONEY_MOVED_STATUSES = EnumSet.of(
            TransactionStatus.COMPLETED,
            TransactionStatus.SUCCESS,
            TransactionStatus.PROCESSING
    );
    private static final Set<TransactionType> SPEND_TYPES = EnumSet.of(
            TransactionType.ENVELOPE_TO_EXTERNAL,
            TransactionType.ENVELOPE_TO_USER,
            TransactionType.VAS_PURCHASE
    );
    private static final Set<TransactionType> UNUSED_TYPES = EnumSet.of(
            TransactionType.BUDGET_COMPLETION_REFUND,
            TransactionType.BUDGET_UNALLOCATED_REFUNDED,
            TransactionType.STRICT_LOCK_ROLLBACK
    );

    private final BudgetRepository budgetRepository;
    private final EnvelopeRepository envelopeRepository;
    private final TransactionLogRepository transactionLogRepository;

    public BudgetCompletionAnalyticsService(BudgetRepository budgetRepository,
                                            EnvelopeRepository envelopeRepository,
                                            TransactionLogRepository transactionLogRepository) {
        this.budgetRepository = budgetRepository;
        this.envelopeRepository = envelopeRepository;
        this.transactionLogRepository = transactionLogRepository;
    }

    @Transactional(readOnly = true)
    public BudgetCompletionAnalyticsResponse getCompletionAnalytics(Long userId, Long budgetId) {
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found"));

        if (budget.getUser() == null || !budget.getUser().getId().equals(userId)) {
            throw new SecurityException("You do not have permission to view this budget analytics.");
        }
        if (budget.getStatus() != BudgetStatus.COMPLETED) {
            throw new IllegalStateException("Budget analytics is available after the budget is completed.");
        }

        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);
        List<TransactionLog> logs = transactionLogRepository.findBudgetActivityLogs(userId, budgetId);

        List<TransactionLog> spendLogs = logs.stream()
                .filter(this::isSpendLog)
                .toList();
        List<TransactionLog> unusedLogs = logs.stream()
                .filter(this::isUnusedLog)
                .toList();

        BigDecimal usedAmount = sumAmounts(spendLogs);
        BigDecimal unusedAmount = sumAmounts(unusedLogs);
        BigDecimal budgetAmountAfterFee = money(firstNonNull(
                budget.getTotalAmount(),
                budget.getAllocatedAmount(),
                budget.getOriginalAmount()
        ));
        BigDecimal unclassified = budgetAmountAfterFee.subtract(usedAmount).subtract(unusedAmount);
        if (unclassified.compareTo(BigDecimal.ZERO) < 0) {
            unclassified = ZERO;
        }

        List<BudgetCompletionAnalyticsResponse.EnvelopeUsage> envelopeUsage =
                buildEnvelopeUsage(envelopes, spendLogs, unusedLogs);
        List<BudgetCompletionAnalyticsResponse.WithdrawalTimeBucket> timeBuckets =
                buildTimeBuckets(spendLogs);

        String busiestPeriod = timeBuckets.stream()
                .max(Comparator
                        .comparing(BudgetCompletionAnalyticsResponse.WithdrawalTimeBucket::withdrawalCount)
                        .thenComparing(BudgetCompletionAnalyticsResponse.WithdrawalTimeBucket::totalAmount))
                .filter(bucket -> bucket.withdrawalCount() > 0)
                .map(BudgetCompletionAnalyticsResponse.WithdrawalTimeBucket::label)
                .orElse("No withdrawals recorded");

        int withdrawalCount = spendLogs.size();
        BigDecimal averageWithdrawal = withdrawalCount == 0
                ? ZERO
                : usedAmount.divide(BigDecimal.valueOf(withdrawalCount), 2, RoundingMode.HALF_UP);

        return new BudgetCompletionAnalyticsResponse(
                budget.getId(),
                userId,
                budget.getName(),
                budget.getStatus(),
                budget.getStartDate(),
                budget.getEndDate(),
                budget.getDurationDays(),
                money(budget.getOriginalAmount()),
                money(budget.getFeeAmount()),
                budgetAmountAfterFee,
                money(budget.getAllocatedAmount()),
                usedAmount,
                unusedAmount,
                money(unclassified),
                percent(usedAmount, budgetAmountAfterFee),
                percent(unusedAmount, budgetAmountAfterFee),
                envelopes.size(),
                withdrawalCount,
                averageWithdrawal,
                busiestPeriod,
                envelopeUsage,
                timeBuckets,
                buildInsights(budget, budgetAmountAfterFee, usedAmount, unusedAmount, withdrawalCount, busiestPeriod)
        );
    }

    private List<BudgetCompletionAnalyticsResponse.EnvelopeUsage> buildEnvelopeUsage(
            List<Envelope> envelopes,
            List<TransactionLog> spendLogs,
            List<TransactionLog> unusedLogs) {

        Map<Long, List<TransactionLog>> spendByEnvelope = spendLogs.stream()
                .filter(log -> log.getSourceEnvelopeId() != null)
                .collect(Collectors.groupingBy(TransactionLog::getSourceEnvelopeId));
        Map<Long, List<TransactionLog>> unusedByEnvelope = unusedLogs.stream()
                .filter(log -> log.getSourceEnvelopeId() != null)
                .collect(Collectors.groupingBy(TransactionLog::getSourceEnvelopeId));

        return envelopes.stream()
                .map(envelope -> {
                    BigDecimal allocated = money(firstNonNull(envelope.getInitialAmount(), envelope.getAmount()));
                    List<TransactionLog> envelopeSpendLogs = spendByEnvelope.getOrDefault(envelope.getId(), List.of());
                    BigDecimal used = sumAmounts(envelopeSpendLogs);
                    BigDecimal unused = sumAmounts(unusedByEnvelope.getOrDefault(envelope.getId(), List.of()));
                    return new BudgetCompletionAnalyticsResponse.EnvelopeUsage(
                            envelope.getId(),
                            envelope.getName(),
                            envelopeType(envelope),
                            allocated,
                            used,
                            unused,
                            percent(used, allocated),
                            envelopeSpendLogs.size()
                    );
                })
                .sorted(Comparator.comparing(BudgetCompletionAnalyticsResponse.EnvelopeUsage::allocatedAmount).reversed())
                .toList();
    }

    private List<BudgetCompletionAnalyticsResponse.WithdrawalTimeBucket> buildTimeBuckets(List<TransactionLog> spendLogs) {
        Map<String, BucketAccumulator> buckets = new LinkedHashMap<>();
        buckets.put("MORNING", new BucketAccumulator("MORNING", "Morning", ZERO));
        buckets.put("AFTERNOON", new BucketAccumulator("AFTERNOON", "Afternoon", ZERO));
        buckets.put("EVENING", new BucketAccumulator("EVENING", "Evening", ZERO));
        buckets.put("NIGHT", new BucketAccumulator("NIGHT", "Night", ZERO));

        for (TransactionLog log : spendLogs) {
            String key = timeBucketKey(log.getCreatedAt() == null ? LocalTime.MIDNIGHT : log.getCreatedAt().toLocalTime());
            buckets.get(key).add(absMoney(log.getAmount()));
        }

        return buckets.values().stream()
                .map(bucket -> new BudgetCompletionAnalyticsResponse.WithdrawalTimeBucket(
                        bucket.key,
                        bucket.label,
                        bucket.count,
                        bucket.totalAmount
                ))
                .toList();
    }

    private List<String> buildInsights(Budget budget,
                                       BigDecimal total,
                                       BigDecimal used,
                                       BigDecimal unused,
                                       int withdrawalCount,
                                       String busiestPeriod) {
        List<String> insights = new ArrayList<>();
        insights.add(String.format("You used %s out of %s from '%s'.",
                formatMoney(used), formatMoney(total), budget.getName()));
        insights.add(String.format("You kept %s unused by the time this budget completed.",
                formatMoney(unused)));
        if (withdrawalCount > 0) {
            insights.add("Most withdrawals happened in the " + busiestPeriod.toLowerCase() + ".");
        } else {
            insights.add("No withdrawals were recorded for this budget.");
        }
        return insights;
    }

    private boolean isSpendLog(TransactionLog log) {
        return log.getTransactionType() != null
                && SPEND_TYPES.contains(log.getTransactionType())
                && log.getStatus() != null
                && MONEY_MOVED_STATUSES.contains(log.getStatus());
    }

    private boolean isUnusedLog(TransactionLog log) {
        return log.getTransactionType() != null
                && UNUSED_TYPES.contains(log.getTransactionType())
                && log.getStatus() != null
                && MONEY_MOVED_STATUSES.contains(log.getStatus());
    }

    private String envelopeType(Envelope envelope) {
        if (envelope.getConditions() == null) {
            return "unknown";
        }
        Object type = envelope.getConditions().get("type");
        return type == null ? "unknown" : type.toString();
    }

    private String timeBucketKey(LocalTime time) {
        int hour = time.getHour();
        if (hour >= 6 && hour < 12) {
            return "MORNING";
        }
        if (hour >= 12 && hour < 17) {
            return "AFTERNOON";
        }
        if (hour >= 17 && hour < 22) {
            return "EVENING";
        }
        return "NIGHT";
    }

    private BigDecimal sumAmounts(List<TransactionLog> logs) {
        return money(logs.stream()
                .map(TransactionLog::getAmount)
                .map(this::absMoney)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal absMoney(BigDecimal value) {
        return money(value == null ? BigDecimal.ZERO : value.abs());
    }

    private BigDecimal money(BigDecimal value) {
        return value == null
                ? ZERO
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return ZERO;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first != null ? first : second;
    }

    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second, BigDecimal third) {
        return first != null ? first : firstNonNull(second, third);
    }

    private String formatMoney(BigDecimal value) {
        return "NGN " + money(value).toPlainString();
    }

    private static class BucketAccumulator {
        private final String key;
        private final String label;
        private int count;
        private BigDecimal totalAmount;

        private BucketAccumulator(String key, String label, BigDecimal totalAmount) {
            this.key = key;
            this.label = label;
            this.totalAmount = totalAmount;
        }

        private void add(BigDecimal amount) {
            this.count++;
            this.totalAmount = this.totalAmount.add(amount).setScale(2, RoundingMode.HALF_UP);
        }
    }
}
