package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.response.SavingsProjectionMessage;
import com.moniewise.moniewise_backend.entity.SavingsGoal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Service
public class SavingsProjectionWebSocketService {

    private static final Logger logger = LoggerFactory.getLogger(SavingsProjectionWebSocketService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final SimpMessagingTemplate messagingTemplate;

    public SavingsProjectionWebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void sendTopUpProjectionAfterCommit(String userEmail,
                                               Long userId,
                                               SavingsGoal goal,
                                               BigDecimal topUpAmount) {
        if (userEmail == null || userEmail.isBlank()) {
            logger.warn("Skipping savings projection websocket because user email is missing for userId={}", userId);
            return;
        }

        SavingsProjectionMessage payload = buildTopUpProjection(userId, goal, topUpAmount);
        Runnable send = () -> messagingTemplate.convertAndSendToUser(
                userEmail,
                "/queue/savings/projections",
                payload
        );

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendSafely(userId, goal.getId(), send);
                }
            });
        } else {
            sendSafely(userId, goal.getId(), send);
        }
    }

    SavingsProjectionMessage buildTopUpProjection(Long userId, SavingsGoal goal, BigDecimal topUpAmount) {
        LocalDate today = LocalDate.now(LAGOS_ZONE);
        BigDecimal amount = money(topUpAmount);
        BigDecimal target = money(goal.getTargetAmount());
        BigDecimal current = money(goal.getCurrentBalance());
        BigDecimal remaining = target.subtract(current).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        boolean targetReached = remaining.compareTo(BigDecimal.ZERO) == 0;

        int depositsNeeded = targetReached ? 0 : ceilUnits(remaining, amount);
        int estimatedDays = depositsNeeded;
        LocalDate projectedDate = targetReached ? today : today.plusDays(estimatedDays);
        String durationLabel = targetReached ? "now" : humanDuration(estimatedDays);

        LocalDate maturityDate = goal.getMaturityDate();
        long daysUntilMaturity = maturityDate == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(today, maturityDate));
        long daysForPlan = Math.max(1, daysUntilMaturity);
        long weeksForPlan = Math.max(1, ceilLong(daysForPlan, 7));
        long monthsForPlan = Math.max(1, ceilLong(daysForPlan, 30));

        BigDecimal requiredDaily = targetReached ? ZERO : divideMoney(remaining, daysForPlan);
        BigDecimal requiredWeekly = targetReached ? ZERO : divideMoney(remaining, weeksForPlan);
        BigDecimal requiredMonthly = targetReached ? ZERO : divideMoney(remaining, monthsForPlan);

        String title = targetReached ? "Savings target reached" : "Savings projection";
        String message = targetReached
                ? String.format("You have reached your %s target for '%s'.", formatMoney(target), goal.getName())
                : String.format("If you keep saving %s daily for %s, you will reach '%s' around %s.",
                        formatMoney(amount), durationLabel, goal.getName(), projectedDate);

        return new SavingsProjectionMessage(
                "SAVINGS_TOP_UP_PROJECTION",
                userId,
                goal.getId(),
                goal.getName(),
                target,
                current,
                amount,
                remaining,
                targetReached,
                depositsNeeded,
                estimatedDays,
                durationLabel,
                projectedDate,
                maturityDate,
                daysUntilMaturity,
                requiredDaily,
                requiredWeekly,
                requiredMonthly,
                title,
                message
        );
    }

    private void sendSafely(Long userId, Long savingsGoalId, Runnable send) {
        try {
            send.run();
            logger.info("Sent savings projection websocket for userId={} savingsGoalId={}", userId, savingsGoalId);
        } catch (Exception e) {
            logger.error("Failed to send savings projection websocket for userId={} savingsGoalId={}",
                    userId, savingsGoalId, e);
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal divideMoney(BigDecimal amount, long divisor) {
        return amount.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.CEILING);
    }

    private int ceilUnits(BigDecimal amount, BigDecimal unit) {
        if (unit == null || unit.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return amount.divide(unit, 0, RoundingMode.CEILING).intValue();
    }

    private long ceilLong(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    private String humanDuration(int days) {
        if (days <= 1) {
            return "1 day";
        }
        if (days < 31) {
            return days + " days";
        }
        if (days < 365) {
            int months = (int) ceilLong(days, 30);
            return months == 1 ? "about 1 month" : "about " + months + " months";
        }
        int years = (int) ceilLong(days, 365);
        return years == 1 ? "about 1 year" : "about " + years + " years";
    }

    private String formatMoney(BigDecimal amount) {
        return "NGN " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
