package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.BudgetEngagementNudge;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.BudgetEngagementNudgeType;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.EngagementNudgeChannel;
import com.moniewise.moniewise_backend.repository.BudgetEngagementNudgeRepository;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EngagementNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.SalaryNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class BudgetEngagementNudgeService {

    private static final Logger logger = LoggerFactory.getLogger(BudgetEngagementNudgeService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final int WALLET_READY_DELAY_DAYS = 1;
    private static final int FUNDED_WALLET_DELAY_DAYS = 2;
    private static final int COMPLETED_BUDGET_DELAY_DAYS = 5;
    private static final int REPEAT_INTERVAL_DAYS = 5;
    private static final int LEGACY_SENDS_PER_RUN = 2;
    private static final int MAX_TRACKED_NUDGES_PER_DAY = 2;

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final BudgetRepository budgetRepository;
    private final BudgetEngagementNudgeRepository nudgeRepository;
    private final EngagementNudgeNotificationRepository engagementNudgeRepository;
    private final SalaryNudgeNotificationRepository salaryNudgeRepository;
    private final NotificationService notificationService;

    @Value("${moniewise.engagement.budget-nudges.enabled:true}")
    private boolean enabled;

    @Value("${moniewise.engagement.budget-nudges.batch-size:200}")
    private int batchSize;

    public BudgetEngagementNudgeService(UserRepository userRepository,
                                        WalletRepository walletRepository,
                                        BudgetRepository budgetRepository,
                                        BudgetEngagementNudgeRepository nudgeRepository,
                                        EngagementNudgeNotificationRepository engagementNudgeRepository,
                                        SalaryNudgeNotificationRepository salaryNudgeRepository,
                                        NotificationService notificationService) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.budgetRepository = budgetRepository;
        this.nudgeRepository = nudgeRepository;
        this.engagementNudgeRepository = engagementNudgeRepository;
        this.salaryNudgeRepository = salaryNudgeRepository;
        this.notificationService = notificationService;
    }

    /**
     * Daily budget engagement sweep. Post-budget users are handled first
     * because that context is more specific than a generic funded-wallet nudge.
     */
    @Scheduled(cron = "${moniewise.engagement.budget-nudges.cron:0 30 10 * * ?}", zone = "Africa/Lagos")
    public void processBudgetEngagementNudges() {
        if (!enabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        Set<Long> contactedThisRun = new HashSet<>();

        int completedBudgetNudges = processCandidates(
                userRepository.findUsersDormantAfterCompletedBudget(
                        LocalDate.now(LAGOS_ZONE).minusDays(COMPLETED_BUDGET_DELAY_DAYS),
                        batchSize),
                BudgetEngagementNudgeType.POST_BUDGET_COMPLETION,
                now,
                contactedThisRun);

        int fundedWalletNudges = processCandidates(
                userRepository.findFundedWalletUsersWithoutActiveBudget(
                        now.minusDays(FUNDED_WALLET_DELAY_DAYS),
                        batchSize),
                BudgetEngagementNudgeType.FUNDED_WALLET_NO_BUDGET,
                now,
                contactedThisRun);

        int walletReadyNudges = processCandidates(
                userRepository.findWalletReadyUsersWithoutActiveBudgetAndEmptyWallet(
                        now.minusDays(WALLET_READY_DELAY_DAYS),
                        batchSize),
                BudgetEngagementNudgeType.WALLET_READY_NO_BUDGET,
                now,
                contactedThisRun);

        int total = completedBudgetNudges + fundedWalletNudges + walletReadyNudges;
        if (total > 0) {
            logger.info("[BUDGET-NUDGE] Sent {} budget engagement nudge(s): completed={}, funded={}, walletReady={}",
                    total, completedBudgetNudges, fundedWalletNudges, walletReadyNudges);
        }
    }

    private int processCandidates(List<User> candidates,
                                  BudgetEngagementNudgeType type,
                                  LocalDateTime now,
                                  Set<Long> contactedThisRun) {
        int sent = 0;
        for (User user : candidates) {
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                continue;
            }
            if (contactedThisRun.contains(user.getId()) || wasContactedRecently(user.getId(), now)) {
                continue;
            }
            if (!hasDailyRoomForLegacyNudge(user.getId(), now)) {
                continue;
            }

            try {
                BigDecimal walletBalance = walletBalanceFor(user.getId());
                String lastBudgetName = latestCompletedBudgetName(user.getId());

                recordSend(user.getId(), type, now, lastEligibleAt(user.getId(), type, now));
                notificationService.sendBudgetEngagementNudgeEmail(
                        user.getEmail(),
                        extractFirstName(user),
                        type,
                        walletBalance,
                        lastBudgetName);
                notificationService.sendBudgetEngagementNudgePush(
                        user.getId(),
                        type,
                        walletBalance,
                        lastBudgetName);

                contactedThisRun.add(user.getId());
                sent++;
            } catch (Exception e) {
                logger.error("[BUDGET-NUDGE] Failed to process {} nudge for user={}", type, user.getId(), e);
            }
        }
        return sent;
    }

    private boolean wasContactedRecently(Long userId, LocalDateTime now) {
        if (engagementNudgeRepository.countByUserIdAndChannelAndSentAtBetween(
                userId,
                EngagementNudgeChannel.EMAIL,
                now.minusDays(REPEAT_INTERVAL_DAYS),
                now) > 0) {
            return true;
        }
        return nudgeRepository.findFirstByUserIdOrderByLastSentAtDesc(userId)
                .map(nudge -> nudge.getLastSentAt() != null
                        && nudge.getLastSentAt().isAfter(now.minusDays(REPEAT_INTERVAL_DAYS)))
                .orElse(false);
    }

    private boolean hasDailyRoomForLegacyNudge(Long userId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        int trackedToday = engagementNudgeRepository.countByUserIdAndSentDate(userId, today)
                + salaryNudgeRepository.countByUserIdAndSentDate(userId, today);
        return trackedToday + LEGACY_SENDS_PER_RUN <= MAX_TRACKED_NUDGES_PER_DAY;
    }

    private void recordSend(Long userId,
                            BudgetEngagementNudgeType type,
                            LocalDateTime sentAt,
                            LocalDateTime eligibleAt) {
        BudgetEngagementNudge nudge = nudgeRepository.findByUserIdAndNudgeType(userId, type)
                .orElseGet(() -> {
                    BudgetEngagementNudge created = new BudgetEngagementNudge();
                    created.setUserId(userId);
                    created.setNudgeType(type);
                    created.setFirstSentAt(sentAt);
                    created.setSendCount(0);
                    return created;
                });

        if (nudge.getFirstSentAt() == null) {
            nudge.setFirstSentAt(sentAt);
        }
        nudge.setLastSentAt(sentAt);
        nudge.setLastEligibleAt(eligibleAt);
        nudge.setSendCount(nudge.getSendCount() + 1);
        nudgeRepository.save(nudge);
    }

    private LocalDateTime lastEligibleAt(Long userId, BudgetEngagementNudgeType type, LocalDateTime fallback) {
        if (type == BudgetEngagementNudgeType.POST_BUDGET_COMPLETION) {
            return budgetRepository.findTopByUserIdAndStatusOrderByEndDateDesc(userId, BudgetStatus.COMPLETED)
                    .map(Budget::getEndDate)
                    .map(LocalDate::atStartOfDay)
                    .orElse(fallback);
        }

        return walletRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId)
                .map(Wallet::getUpdatedAt)
                .orElse(fallback);
    }

    private BigDecimal walletBalanceFor(Long userId) {
        return walletRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId)
                .map(Wallet::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    private String latestCompletedBudgetName(Long userId) {
        return budgetRepository.findTopByUserIdAndStatusOrderByEndDateDesc(userId, BudgetStatus.COMPLETED)
                .map(Budget::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(null);
    }

    private String extractFirstName(User user) {
        Optional<String> profileName = extractProfileName(user.getProfileData());
        if (profileName.isPresent()) {
            return profileName.get();
        }

        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            return "there";
        }
        String prefix = email.substring(0, email.indexOf("@") > 0 ? email.indexOf("@") : email.length()).trim();
        return prefix.isBlank() ? "there" : prefix;
    }

    private Optional<String> extractProfileName(Map<String, Object> profileData) {
        if (profileData == null || profileData.isEmpty()) {
            return Optional.empty();
        }

        String firstName = textValue(profileData.get("firstName"));
        if (firstName != null) {
            return Optional.of(firstName);
        }

        String fullName = textValue(profileData.get("name"));
        if (fullName == null) {
            return Optional.empty();
        }

        int firstSpace = fullName.indexOf(" ");
        return Optional.of(firstSpace > 0 ? fullName.substring(0, firstSpace) : fullName);
    }

    private String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }
}
