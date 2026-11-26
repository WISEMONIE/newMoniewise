package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.repository.SavingsGoalRepository;
import com.moniewise.moniewise_backend.service.BadgeAwardService;
import com.moniewise.moniewise_backend.service.NotificationService;
import com.moniewise.moniewise_backend.service.SavingsCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class SavingsLifeCycleManager {

    private static final Logger logger = LoggerFactory.getLogger(SavingsLifeCycleManager.class);

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final int MORNING_HOUR = 7;
    private static final int PAGE_SIZE = 100;

    private final SavingsGoalRepository savingsGoalRepository;
    private final NotificationService notificationService;
    private final SavingsCacheService savingsCacheService;
    private final BadgeAwardService badgeAwardService;
    private final TransactionTemplate transactionTemplate;

    public SavingsLifeCycleManager(SavingsGoalRepository savingsGoalRepository,
                                   NotificationService notificationService,
                                   SavingsCacheService savingsCacheService,
                                   BadgeAwardService badgeAwardService,
                                   TransactionTemplate transactionTemplate) {
        this.savingsGoalRepository = savingsGoalRepository;
        this.notificationService = notificationService;
        this.savingsCacheService = savingsCacheService;
        this.badgeAwardService = badgeAwardService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Runs every 4 hours in Africa/Lagos. Each event is locked and saved in its
     * own short transaction, so a rename or a later day committed alongside this
     * pass is not written back to the old values. Interest still applies at most
     * once per Lagos day. Reminder switches are read on every pass, so turning
     * one on after midnight still sends inside the same day.
     */
    @Scheduled(cron = "0 0 */4 * * *", zone = "Africa/Lagos")
    public void processDailySavings() {
        logger.info("🌤️ Waking up Savings Engine: Calculating interest & checking maturities...");

        LocalDate today = LocalDate.now(LAGOS);
        long afterId = 0L;
        while (true) {
            List<Long> ids = savingsGoalRepository.findIdsByStatusAfter(
                    SavingsStatus.ACTIVE, afterId, PageRequest.of(0, PAGE_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                try {
                    transactionTemplate.execute(status -> {
                        processOneGoal(id, today);
                        return null;
                    });
                } catch (Exception e) {
                    logger.error("❌ Failed to process Savings Goal ID: {}", id, e);
                }
            }
            afterId = ids.get(ids.size() - 1);
        }

        if (ZonedDateTime.now(LAGOS).getHour() >= MORNING_HOUR) {
            sendDueMorningReminders();
        }

        logger.info("💤 Savings Engine finished daily processing.");
    }

    /** 07:00 Africa/Lagos. Sends the unlock-day push that was held back overnight. */
    @Scheduled(cron = "0 0 7 * * *", zone = "Africa/Lagos")
    public void morningReminderClock() {
        sendDueMorningReminders();
    }

    private void processOneGoal(Long goalId, LocalDate today) {
        SavingsGoal goal = savingsGoalRepository.findByIdForUpdate(goalId).orElse(null);
        if (goal == null || goal.getStatus() != SavingsStatus.ACTIVE || goal.getMaturityDate() == null) {
            return;
        }

        boolean alreadyProcessedToday = goal.getLastProcessedDate() != null
                && !goal.getLastProcessedDate().isBefore(today);
        if (!alreadyProcessedToday) {
            applyDailyInterest(goal);
            goal.setLastProcessedDate(today);
        }

        long daysUntilMaturity = ChronoUnit.DAYS.between(today, goal.getMaturityDate());
        boolean createdToday = goal.getStartDate() != null && !today.isAfter(goal.getStartDate());

        if (goal.isRemindWeek()
                && PrepReminderSchedule.due(daysUntilMaturity, today, goal.getStartDate(), goal.getLastPrepReminderDate())
                && sendPrepPush(goal, daysUntilMaturity)) {
            goal.setLastPrepReminderDate(today);
        }

        // The last-week email is its own once-only note. It does not wait for
        // the repeating push, so a push that landed on day 8 cannot skip it.
        if (!createdToday
                && goal.isRemindWeek()
                && daysUntilMaturity > 1
                && daysUntilMaturity <= 7
                && !goal.isMaturityReminderSent()) {
            sendMaturingSoonEmail(goal, daysUntilMaturity);
            goal.setMaturityReminderSent(true);
        }

        if (!createdToday
                && goal.isRemindDayBefore()
                && !goal.isMaturityEveReminderSent()
                && daysUntilMaturity == 1
                && sendMaturingSoonPush(goal)) {
            sendMaturingSoonEmail(goal, 1);
            goal.setMaturityEveReminderSent(true);
            goal.setMaturityReminderSent(true);
        }

        if (!today.isBefore(goal.getMaturityDate())) {
            applyMaturity(goal);
        }

        savingsGoalRepository.save(goal);
        if (goal.getUser() != null && goal.getUser().getId() != null) {
            savingsCacheService.evictUserSavingsCachesAfterCommit(goal.getUser().getId());
        }
    }

    private void applyDailyInterest(SavingsGoal goal) {
        if (goal.getInterestRate() == null || goal.getCurrentBalance() == null || goal.getAccruedInterest() == null) {
            return;
        }
        if (goal.getInterestRate().compareTo(BigDecimal.ZERO) <= 0
                || goal.getCurrentBalance().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal annualInterest = goal.getCurrentBalance()
                .multiply(goal.getInterestRate())
                .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        BigDecimal dailyInterest = annualInterest.divide(new BigDecimal("365"), 6, RoundingMode.HALF_UP);
        goal.setAccruedInterest(goal.getAccruedInterest().add(dailyInterest));
    }

    /**
     * Unlocks the event as soon as the day arrives. The ready push waits until
     * 07:00 Lagos when the person asked for a morning reminder. The flag
     * morningReminderSent is what stops a second ready push.
     */
    @Transactional
    public void matureGoal(Long goalId) {
        if (goalId == null) {
            return;
        }
        SavingsGoal goal = savingsGoalRepository.findByIdForUpdate(goalId).orElse(null);
        if (goal == null || goal.getStatus() != SavingsStatus.ACTIVE || goal.getMaturityDate() == null) {
            return;
        }
        if (LocalDate.now(LAGOS).isBefore(goal.getMaturityDate())) {
            return;
        }
        applyMaturity(goal);
        savingsGoalRepository.save(goal);
        if (goal.getUser() != null && goal.getUser().getId() != null) {
            savingsCacheService.evictUserSavingsCachesAfterCommit(goal.getUser().getId());
        }
    }

    private void applyMaturity(SavingsGoal goal) {
        if (goal.getStatus() != SavingsStatus.ACTIVE) {
            return;
        }
        goal.setStatus(SavingsStatus.MATURED);
        badgeAwardService.awardSavingsMaturedBadgeAfterCommit(goal);
        logger.info("🎉 Savings Goal '{}' (ID: {}) for User {} has MATURED!",
                goal.getName(), goal.getId(), goal.getUser() != null ? goal.getUser().getId() : null);

        if (!shouldSendMaturityNoticeNow(goal)) {
            return;
        }
        deliverMaturityNotice(goal);
    }

    private boolean shouldSendMaturityNoticeNow(SavingsGoal goal) {
        if (goal.isMorningReminderSent()) {
            return false;
        }
        if (!goal.isRemindMorning()) {
            return true;
        }
        ZonedDateTime now = ZonedDateTime.now(LAGOS);
        if (goal.getMaturityDate() != null && now.toLocalDate().isEqual(goal.getMaturityDate())) {
            return now.getHour() >= MORNING_HOUR;
        }
        return true;
    }

    private void deliverMaturityNotice(SavingsGoal goal) {
        if (!sendMaturityNotification(goal)) {
            return;
        }
        sendMaturedEmail(goal);
        goal.setMorningReminderSent(true);
    }

    private void sendDueMorningReminders() {
        ZonedDateTime now = ZonedDateTime.now(LAGOS);
        if (now.getHour() < MORNING_HOUR) {
            return;
        }
        LocalDate today = now.toLocalDate();
        long afterId = 0L;
        while (true) {
            List<Long> ids = savingsGoalRepository.findPendingMorningReminderIdsAfter(
                    SavingsStatus.MATURED, afterId, PageRequest.of(0, PAGE_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                try {
                    transactionTemplate.execute(status -> {
                        sendMorningReminderIfDue(id, today);
                        return null;
                    });
                } catch (Exception e) {
                    logger.error("Failed to send morning reminder for Savings Goal ID: {}", id, e);
                }
            }
            afterId = ids.get(ids.size() - 1);
        }
    }

    private void sendMorningReminderIfDue(Long goalId, LocalDate today) {
        SavingsGoal goal = savingsGoalRepository.findByIdForUpdate(goalId).orElse(null);
        if (goal == null
                || goal.getStatus() != SavingsStatus.MATURED
                || !goal.isRemindMorning()
                || goal.isMorningReminderSent()
                || goal.getMaturityDate() == null
                || today.isBefore(goal.getMaturityDate())) {
            return;
        }
        deliverMaturityNotice(goal);
        savingsGoalRepository.save(goal);
    }

    /**
     * Fires the "your savings is maturing soon" email — once at ~7 days and once
     * the day before maturity (each guarded by its own flag on the goal).
     */
    private void sendMaturingSoonEmail(SavingsGoal goal, long daysRemaining) {
        var user = goal.getUser();
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;
        if (goal.getCurrentBalance() == null || goal.getAccruedInterest() == null || goal.getMaturityDate() == null) return;

        String firstName = user.getName();
        if (firstName == null || firstName.isBlank()) {
            String username = user.getEmail().split("@")[0];
            firstName = username.isEmpty() ? "there" : username.substring(0, 1).toUpperCase() + username.substring(1);
        }

        BigDecimal projectedPayout = goal.getCurrentBalance().add(goal.getAccruedInterest());
        String maturityDateLabel = goal.getMaturityDate().format(DateTimeFormatter.ofPattern("d MMMM yyyy"));

        notificationService.sendSavingsMaturingSoonEmail(
                user.getEmail(),
                firstName,
                goal.getName(),
                goal.getCurrentBalance(),
                goal.getAccruedInterest(),
                projectedPayout,
                maturityDateLabel,
                (int) daysRemaining
        );

        logger.info("📧 Sent 'maturing soon' email for Savings Goal '{}' (ID: {}) to {} — {} day(s) left",
                goal.getName(), goal.getId(), user.getEmail(), daysRemaining);
    }

    /**
     * Repeating preparation push. The copy uses the days left today and asks
     * the person to add money while the event is still ahead.
     */
    private boolean sendPrepPush(SavingsGoal goal, long daysRemaining) {
        BigDecimal ready = readyAmount(goal);
        BigDecimal target = goal.getTargetAmount() == null ? BigDecimal.ZERO : goal.getTargetAmount();
        String money = naira(ready);
        String message;
        if (ready.compareTo(BigDecimal.ZERO) <= 0) {
            message = String.format(
                    "%d days to go. Nothing is set aside yet. Add toward it so the day is covered.",
                    daysRemaining);
        } else if (target.compareTo(BigDecimal.ZERO) > 0 && ready.compareTo(target) >= 0) {
            message = String.format(
                    "%d days to go. %s is set aside. It stays locked until the day.",
                    daysRemaining, money);
        } else {
            message = String.format(
                    "%d days to go. %s is set aside. Add more so you are ready.",
                    daysRemaining, money);
        }
        return sendMaturingSoonNotice(goal, goal.getName(), message);
    }

    /** The day before. Last chance to add money, then it unlocks. */
    private boolean sendMaturingSoonPush(SavingsGoal goal) {
        BigDecimal ready = readyAmount(goal);
        String money = naira(ready);
        String message = ready.compareTo(BigDecimal.ZERO) <= 0
                ? "Nothing is set aside yet. Add toward it today. It unlocks tomorrow."
                : money + " is set aside. Add anything you still need today. It unlocks tomorrow.";
        return sendMaturingSoonNotice(goal, goal.getName() + " is tomorrow", message);
    }

    private boolean sendMaturingSoonNotice(SavingsGoal goal, String title, String message) {
        if (goal.getUser() == null || goal.getUser().getId() == null) {
            return false;
        }
        return notificationService.sendTitledNotification(
                goal.getUser().getId().toString(),
                title,
                message,
                NotificationType.SAVINGS_MATURING_SOON,
                "VIEW_SAVINGS",
                "/savings/" + goal.getId()
        );
    }

    /**
     * Fires the "your savings has matured" email — distinct from
     * sendMaturityNotification() below, which only sends the push/in-app one.
     */
    private void sendMaturedEmail(SavingsGoal goal) {
        var user = goal.getUser();
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;
        if (goal.getCurrentBalance() == null || goal.getAccruedInterest() == null) return;

        String firstName = user.getName();
        if (firstName == null || firstName.isBlank()) {
            String username = user.getEmail().split("@")[0];
            firstName = username.isEmpty() ? "there" : username.substring(0, 1).toUpperCase() + username.substring(1);
        }

        BigDecimal totalPayout = goal.getCurrentBalance().add(goal.getAccruedInterest());

        notificationService.sendSavingsMaturedEmail(
                user.getEmail(),
                firstName,
                goal.getName(),
                goal.getCurrentBalance(),
                goal.getAccruedInterest(),
                totalPayout
        );
    }

    /**
     * Push + in-app inbox for maturity. Single canonical send — the old duplicate
     * GenericNotificationEvent publish was removed (it produced a second inbox row
     * and push for the same maturity).
     */
    private boolean sendMaturityNotification(SavingsGoal goal) {
        if (goal.getUser() == null || goal.getUser().getId() == null) {
            return false;
        }
        String userId = goal.getUser().getId().toString();
        String money = naira(readyAmount(goal));
        boolean onTheDay = goal.getMaturityDate() != null
                && LocalDate.now(LAGOS).isEqual(goal.getMaturityDate());
        boolean morning = goal.isRemindMorning() && onTheDay;
        String title = morning ? goal.getName() + " is today" : goal.getName();
        String message = morning
                ? money + " is ready. Move it to your wallet, a bank, or a friend."
                : money + " is ready to take out.";

        return notificationService.sendTitledNotification(
                userId,
                title,
                message,
                NotificationType.SAVINGS_MATURED,
                "VIEW_SAVINGS",
                "/savings/" + goal.getId()
        );
    }

    private static BigDecimal readyAmount(SavingsGoal goal) {
        BigDecimal balance = goal.getCurrentBalance() == null ? BigDecimal.ZERO : goal.getCurrentBalance();
        BigDecimal interest = goal.getAccruedInterest() == null ? BigDecimal.ZERO : goal.getAccruedInterest();
        return balance.add(interest);
    }

    private static String naira(BigDecimal amount) {
        return String.format("₦%,.2f", amount == null ? BigDecimal.ZERO : amount);
    }
}
