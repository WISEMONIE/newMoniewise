package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.EngagementNudgeNotification;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.EngagementNudgeCampaign;
import com.moniewise.moniewise_backend.enums.EngagementNudgeChannel;
import com.moniewise.moniewise_backend.enums.EngagementNudgeSegment;
import com.moniewise.moniewise_backend.enums.LifecycleRecoveryType;
import com.moniewise.moniewise_backend.repository.BudgetEngagementNudgeRepository;
import com.moniewise.moniewise_backend.repository.EngagementNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.SalaryNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class LifecycleRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(LifecycleRecoveryService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final int DORMANT_DAYS = 15;
    private static final int RECENT_EMAIL_GUARD_DAYS = 6;
    private static final int RECENT_ONBOARDING_GUARD_DAYS = 2;
    private static final int MAX_TRACKED_MESSAGES_PER_DAY = 2;

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final EngagementNudgeNotificationRepository nudgeRepository;
    private final SalaryNudgeNotificationRepository salaryNudgeRepository;
    private final BudgetEngagementNudgeRepository legacyBudgetNudgeRepository;
    private final NotificationService notificationService;

    @Value("${moniewise.engagement.lifecycle-recovery.enabled:true}")
    private boolean enabled;

    @Value("${moniewise.engagement.lifecycle-recovery.batch-size:150}")
    private int batchSize;

    public LifecycleRecoveryService(UserRepository userRepository,
                                    WalletRepository walletRepository,
                                    EngagementNudgeNotificationRepository nudgeRepository,
                                    SalaryNudgeNotificationRepository salaryNudgeRepository,
                                    BudgetEngagementNudgeRepository legacyBudgetNudgeRepository,
                                    NotificationService notificationService) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.nudgeRepository = nudgeRepository;
        this.salaryNudgeRepository = salaryNudgeRepository;
        this.legacyBudgetNudgeRepository = legacyBudgetNudgeRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "${moniewise.engagement.lifecycle-recovery.cron:0 50 9 * * ?}", zone = "Africa/Lagos")
    public void processDormantSetupRecovery() {
        if (!enabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        LocalDateTime inactiveBefore = now.minusDays(DORMANT_DAYS);
        Instant inactiveBeforeInstant = inactiveBefore.atZone(LAGOS_ZONE).toInstant();
        LocalDateTime recentOnboardingBefore = now.minusDays(RECENT_ONBOARDING_GUARD_DAYS);
        int limit = Math.max(1, batchSize);
        Set<Long> contactedThisRun = new HashSet<>();

        int noWallet = processCandidates(
                userRepository.findDormantUsersWithoutWalletForRecovery(
                        inactiveBefore, inactiveBeforeInstant, recentOnboardingBefore, limit),
                LifecycleRecoveryType.NO_WALLET,
                now,
                contactedThisRun);

        int walletNotFunded = processCandidates(
                userRepository.findDormantWalletUsersNotFundedForRecovery(
                        inactiveBefore, inactiveBeforeInstant, limit),
                LifecycleRecoveryType.WALLET_READY_NOT_FUNDED,
                now,
                contactedThisRun);

        int fundedNoPlan = processCandidates(
                userRepository.findDormantFundedWalletUsersWithoutPlanForRecovery(
                        inactiveBefore, inactiveBeforeInstant, limit),
                LifecycleRecoveryType.FUNDED_NO_PLAN,
                now,
                contactedThisRun);

        int total = noWallet + walletNotFunded + fundedNoPlan;
        if (total > 0) {
            logger.info("[LIFECYCLE-RECOVERY] Sent {} email(s): noWallet={}, walletNotFunded={}, fundedNoPlan={}",
                    total, noWallet, walletNotFunded, fundedNoPlan);
        }
    }

    private int processCandidates(List<User> users,
                                  LifecycleRecoveryType type,
                                  LocalDateTime now,
                                  Set<Long> contactedThisRun) {
        int sent = 0;
        for (User user : users) {
            if (user.getId() == null || contactedThisRun.contains(user.getId()) || !hasEmail(user)) {
                continue;
            }
            if (!hasDailyRoom(user.getId(), now) || wasEmailedRecently(user.getId(), now)) {
                continue;
            }

            try {
                if (sendIfAllowed(user, type, now)) {
                    contactedThisRun.add(user.getId());
                    sent++;
                }
            } catch (DataIntegrityViolationException e) {
                logger.info("[LIFECYCLE-RECOVERY] Duplicate {} skipped for user={}", type, user.getId());
            } catch (Exception e) {
                logger.error("[LIFECYCLE-RECOVERY] Failed {} for user={}", type, user.getId(), e);
            }
        }
        return sent;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendIfAllowed(User user, LifecycleRecoveryType type, LocalDateTime now) {
        if (nudgeRepository.countByUserIdAndCampaignAndSentAtAfter(
                user.getId(), EngagementNudgeCampaign.SETUP_RECOVERY_15D, now.minusYears(5)) > 0) {
            return false;
        }

        Wallet wallet = walletRepository.findFirstByUserIdOrderByUpdatedAtDesc(user.getId()).orElse(null);
        record(user.getId(), type, now);
        notificationService.sendLifecycleRecoveryEmail(
                user.getEmail(),
                extractFirstName(user),
                type,
                wallet != null ? wallet.getAccountNumber() : null,
                wallet != null ? wallet.getBankName() : null);
        return true;
    }

    private boolean hasDailyRoom(Long userId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        int legacyBudgetCount = legacyBudgetNudgeRepository.existsByUserIdAndLastSentAtBetween(
                userId, today.atStartOfDay(), today.plusDays(1).atStartOfDay()) ? 2 : 0;
        int trackedToday = nudgeRepository.countByUserIdAndSentDate(userId, today)
                + salaryNudgeRepository.countByUserIdAndSentDate(userId, today)
                + legacyBudgetCount;
        return trackedToday + 1 <= MAX_TRACKED_MESSAGES_PER_DAY;
    }

    private boolean wasEmailedRecently(Long userId, LocalDateTime now) {
        if (nudgeRepository.countByUserIdAndChannelAndSentAtBetween(
                userId,
                EngagementNudgeChannel.EMAIL,
                now.minusDays(RECENT_EMAIL_GUARD_DAYS),
                now) > 0) {
            return true;
        }
        return legacyBudgetNudgeRepository.existsByUserIdAndLastSentAtBetween(
                userId,
                now.minusDays(RECENT_EMAIL_GUARD_DAYS),
                now);
    }

    private void record(Long userId, LifecycleRecoveryType type, LocalDateTime sentAt) {
        EngagementNudgeNotification nudge = new EngagementNudgeNotification();
        nudge.setUserId(userId);
        nudge.setCampaign(EngagementNudgeCampaign.SETUP_RECOVERY_15D);
        nudge.setSegment(segmentFor(type));
        nudge.setChannel(EngagementNudgeChannel.EMAIL);
        nudge.setOccasionKey(type.name());
        nudge.setCopyKey("setup-recovery-" + type.name().toLowerCase());
        nudge.setSentDate(sentAt.toLocalDate());
        nudge.setSentAt(sentAt);
        nudgeRepository.save(nudge);
    }

    private EngagementNudgeSegment segmentFor(LifecycleRecoveryType type) {
        return switch (type) {
            case NO_WALLET -> EngagementNudgeSegment.NO_WALLET;
            case WALLET_READY_NOT_FUNDED -> EngagementNudgeSegment.WALLET_NOT_FUNDED;
            case FUNDED_NO_PLAN -> EngagementNudgeSegment.FUNDED_NO_PLAN;
        };
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

    private boolean hasEmail(User user) {
        return user.getEmail() != null && !user.getEmail().isBlank();
    }
}
