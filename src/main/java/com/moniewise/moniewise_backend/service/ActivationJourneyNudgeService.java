package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.EngagementNudgeNotification;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.EngagementNudgeCampaign;
import com.moniewise.moniewise_backend.enums.EngagementNudgeChannel;
import com.moniewise.moniewise_backend.enums.EngagementNudgeSegment;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.repository.BudgetEngagementNudgeRepository;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EngagementNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.SalaryNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
public class ActivationJourneyNudgeService {

    private static final Logger logger = LoggerFactory.getLogger(ActivationJourneyNudgeService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final EngagementNudgeCampaign CAMPAIGN = EngagementNudgeCampaign.ACTIVATION_JOURNEY;
    private static final long PUSH_TTL_SECONDS = 259_200L;
    private static final int MESSAGES_PER_STEP = 2;
    private static final int MAX_TRACKED_MESSAGES_PER_DAY = 2;
    private static final String ROUTE_WALLET = "/wallet";
    private static final String ROUTE_CREATE_BUDGET = "/create_budget";
    private static final String ROUTE_ACTIVITY = "/activity";

    private static final Set<TransactionType> DIRECT_SPEND_TYPES = Set.of(
            TransactionType.ENVELOPE_TO_EXTERNAL,
            TransactionType.ENVELOPE_TO_USER,
            TransactionType.VAS_PURCHASE
    );
    private static final Set<TransactionStatus> DIRECT_SPEND_STATUSES = Set.of(
            TransactionStatus.COMPLETED,
            TransactionStatus.PROCESSING
    );

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final BudgetRepository budgetRepository;
    private final EnvelopeRepository envelopeRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final EngagementNudgeNotificationRepository nudgeRepository;
    private final SalaryNudgeNotificationRepository salaryNudgeRepository;
    private final BudgetEngagementNudgeRepository legacyBudgetNudgeRepository;
    private final NotificationService notificationService;
    private final DeepLinkService deepLinkService;

    @Autowired
    @Lazy
    private ActivationJourneyNudgeService self;

    @Value("${moniewise.engagement.activation-journey.enabled:true}")
    private boolean enabled;

    @Value("${moniewise.engagement.activation-journey.catchup.enabled:true}")
    private boolean catchupEnabled;

    @Value("${moniewise.engagement.activation-journey.batch-size:150}")
    private int batchSize;

    @Value("${moniewise.engagement.activation-journey.catchup-delay-hours:1}")
    private int catchupDelayHours;

    @Value("${moniewise.engagement.activation-journey.recent-contact-guard-hours:18}")
    private int recentContactGuardHours;

    public ActivationJourneyNudgeService(UserRepository userRepository,
                                         WalletRepository walletRepository,
                                         BudgetRepository budgetRepository,
                                         EnvelopeRepository envelopeRepository,
                                         TransactionLogRepository transactionLogRepository,
                                         EngagementNudgeNotificationRepository nudgeRepository,
                                         SalaryNudgeNotificationRepository salaryNudgeRepository,
                                         BudgetEngagementNudgeRepository legacyBudgetNudgeRepository,
                                         NotificationService notificationService,
                                         DeepLinkService deepLinkService) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.budgetRepository = budgetRepository;
        this.envelopeRepository = envelopeRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.nudgeRepository = nudgeRepository;
        this.salaryNudgeRepository = salaryNudgeRepository;
        this.legacyBudgetNudgeRepository = legacyBudgetNudgeRepository;
        this.notificationService = notificationService;
        this.deepLinkService = deepLinkService;
    }

    public void nudgeAfterWalletCreated(Long userId) {
        if (!enabled) {
            return;
        }
        runAfterCommit("wallet-created", userId, () -> self.sendWalletReadyStep(userId));
    }

    public void nudgeAfterWalletFunded(Long userId) {
        if (!enabled) {
            return;
        }
        runAfterCommit("wallet-funded", userId, () -> self.sendCreateBudgetStep(userId));
    }

    public void nudgeAfterBudgetCreated(Long userId, Long budgetId) {
        if (!enabled) {
            return;
        }
        runAfterCommit("budget-created", userId, () -> self.sendReviewBudgetStep(userId, budgetId));
    }

    public void nudgeAfterEnvelopeUnlocked(Long userId, Long budgetId, Long envelopeId) {
        if (!enabled) {
            return;
        }
        runAfterCommit("envelope-unlocked", userId, () -> self.sendSpendFromEnvelopeStep(userId, budgetId, envelopeId));
    }

    public void nudgeAfterFirstDirectSpend(Long userId, Long budgetId, Long envelopeId) {
        if (!enabled) {
            return;
        }
        runAfterCommit("first-direct-spend", userId, () -> self.sendFirstDirectSpendDoneStep(userId, budgetId, envelopeId));
    }

    @Scheduled(cron = "${moniewise.engagement.activation-journey.catchup-cron:0 15 11 * * ?}", zone = "Africa/Lagos")
    public void processActivationCatchUp() {
        if (!enabled || !catchupEnabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        LocalDateTime eligibleBefore = now.minusHours(Math.max(1, catchupDelayHours));
        int limit = Math.max(1, batchSize);
        Set<Long> contactedThisRun = new HashSet<>();

        int fundedNoBudget = processCatchUpCandidates(
                userRepository.findFundedWalletUsersWithoutActiveBudget(eligibleBefore, limit),
                ActivationStep.CREATE_FIRST_BUDGET,
                now,
                contactedThisRun);

        int walletReady = processCatchUpCandidates(
                userRepository.findWalletReadyUsersWithoutActiveBudgetAndEmptyWallet(eligibleBefore, limit),
                ActivationStep.FUND_WALLET,
                now,
                contactedThisRun);

        int total = fundedNoBudget + walletReady;
        if (total > 0) {
            logger.info("[ACTIVATION-JOURNEY] Sent {} catch-up step(s): fundedNoBudget={}, walletReady={}",
                    total, fundedNoBudget, walletReady);
        }
    }

    private int processCatchUpCandidates(List<User> users,
                                         ActivationStep step,
                                         LocalDateTime now,
                                         Set<Long> contactedThisRun) {
        int sent = 0;
        for (User user : users) {
            if (user == null || user.getId() == null || contactedThisRun.contains(user.getId())) {
                continue;
            }
            if (!hasDailyRoom(user.getId(), now) || wasContactedRecently(user.getId(), now)) {
                continue;
            }

            try {
                boolean delivered = switch (step) {
                    case FUND_WALLET -> self.sendWalletReadyStep(user.getId());
                    case CREATE_FIRST_BUDGET -> self.sendCreateBudgetStep(user.getId());
                    case REVIEW_FIRST_BUDGET, SPEND_FROM_ENVELOPE, FIRST_DIRECT_SPEND_DONE -> false;
                };
                if (delivered) {
                    contactedThisRun.add(user.getId());
                    sent++;
                }
            } catch (DataIntegrityViolationException e) {
                logger.info("[ACTIVATION-JOURNEY] Duplicate catch-up {} skipped for user={}", step, user.getId());
            } catch (Exception e) {
                logger.error("[ACTIVATION-JOURNEY] Failed catch-up {} for user={}", step, user.getId(), e);
            }
        }
        return sent;
    }

    @Transactional
    public boolean sendWalletReadyStep(Long userId) {
        User user = loadNudgeableUser(userId).orElse(null);
        if (user == null) {
            return false;
        }

        Wallet wallet = walletRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId).orElse(null);
        if (!isReadyWallet(wallet) || isPositive(wallet.getBalance()) || budgetRepository.existsByUserId(userId)) {
            return false;
        }

        return sendStepIfAllowed(user, ActivationStep.FUND_WALLET, null, null, null, LocalDateTime.now(LAGOS_ZONE));
    }

    @Transactional
    public boolean sendCreateBudgetStep(Long userId) {
        User user = loadNudgeableUser(userId).orElse(null);
        if (user == null) {
            return false;
        }

        Wallet wallet = walletRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId).orElse(null);
        if (!isReadyWallet(wallet) || !isPositive(wallet.getBalance()) || budgetRepository.existsByUserId(userId)) {
            return false;
        }

        return sendStepIfAllowed(user, ActivationStep.CREATE_FIRST_BUDGET, null, null, null, LocalDateTime.now(LAGOS_ZONE));
    }

    @Transactional
    public boolean sendReviewBudgetStep(Long userId, Long budgetId) {
        User user = loadNudgeableUser(userId).orElse(null);
        Budget budget = budgetId == null ? null : budgetRepository.findById(budgetId).orElse(null);
        if (user == null || budget == null || budget.getUser() == null
                || !user.getId().equals(budget.getUser().getId())) {
            return false;
        }
        if (budgetRepository.countByUserId(userId) > 1 || !isFundedStatus(budget.getStatus())) {
            return false;
        }

        return sendStepIfAllowed(user, ActivationStep.REVIEW_FIRST_BUDGET, budget.getId(), null, null,
                LocalDateTime.now(LAGOS_ZONE));
    }

    @Transactional
    public boolean sendSpendFromEnvelopeStep(Long userId, Long budgetId, Long envelopeId) {
        User user = loadNudgeableUser(userId).orElse(null);
        Envelope envelope = envelopeId == null ? null : envelopeRepository.findById(envelopeId).orElse(null);
        if (user == null || envelope == null || envelope.getBudget() == null || envelope.getBudget().getUser() == null
                || !user.getId().equals(envelope.getBudget().getUser().getId())) {
            return false;
        }
        if (budgetId != null && !budgetId.equals(envelope.getBudget().getId())) {
            return false;
        }
        if (budgetRepository.countByUserId(userId) > 1 || hasDirectSpend(userId)
                || !isPositive(envelope.getRemainingAmount())) {
            return false;
        }

        return sendStepIfAllowed(user, ActivationStep.SPEND_FROM_ENVELOPE, envelope.getBudget().getId(),
                envelope.getId(), envelope.getName(), LocalDateTime.now(LAGOS_ZONE));
    }

    @Transactional
    public boolean sendFirstDirectSpendDoneStep(Long userId, Long budgetId, Long envelopeId) {
        User user = loadNudgeableUser(userId).orElse(null);
        if (user == null) {
            return false;
        }
        if (budgetRepository.countByUserId(userId) > 1) {
            return false;
        }

        return sendStepIfAllowed(user, ActivationStep.FIRST_DIRECT_SPEND_DONE, budgetId, envelopeId, null,
                LocalDateTime.now(LAGOS_ZONE));
    }

    private boolean sendStepIfAllowed(User user,
                                      ActivationStep step,
                                      Long budgetId,
                                      Long envelopeId,
                                      String envelopeName,
                                      LocalDateTime now) {
        if (hasAlreadySentStep(user.getId(), step)) {
            return false;
        }

        ActivationCopy copy = copyFor(step, envelopeName);
        String route = routeFor(step, budgetId, envelopeId);
        if (route == null || route.isBlank()) {
            return false;
        }

        String firstName = extractFirstName(user);
        record(user.getId(), copy.segment(), EngagementNudgeChannel.EMAIL, step, copy.key(), now);
        notificationService.sendEngagementNudgeEmail(
                user.getEmail(),
                firstName,
                copy.emailSubject(),
                copy.emailTag(),
                copy.emailHeadline(),
                copy.emailBody(),
                copy.footerNote(),
                copy.ctaLabel(),
                deepLinkService.toDeepLink(route));

        record(user.getId(), copy.segment(), EngagementNudgeChannel.PUSH, step, copy.key(), now);
        notificationService.enqueuePushOnlyNotification(
                user.getId(),
                NotificationType.BUDGET_ENGAGEMENT_NUDGE,
                copy.pushTitle(),
                copy.pushBody(),
                route,
                PUSH_TTL_SECONDS);

        logger.info("[ACTIVATION-JOURNEY] Sent {} step to user={} route={}", step, user.getId(), route);
        return true;
    }

    private void record(Long userId,
                        EngagementNudgeSegment segment,
                        EngagementNudgeChannel channel,
                        ActivationStep step,
                        String copyKey,
                        LocalDateTime sentAt) {
        EngagementNudgeNotification nudge = new EngagementNudgeNotification();
        nudge.setUserId(userId);
        nudge.setCampaign(CAMPAIGN);
        nudge.setSegment(segment);
        nudge.setChannel(channel);
        nudge.setOccasionKey(step.name());
        nudge.setCopyKey(copyKey);
        nudge.setSentDate(sentAt.toLocalDate());
        nudge.setSentAt(sentAt);
        nudgeRepository.save(nudge);
    }

    private boolean hasAlreadySentStep(Long userId, ActivationStep step) {
        return nudgeRepository.existsByUserIdAndCampaignAndOccasionKey(userId, CAMPAIGN, step.name());
    }

    private boolean hasDailyRoom(Long userId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        int legacyBudgetCount = legacyBudgetNudgeRepository.existsByUserIdAndLastSentAtBetween(
                userId, today.atStartOfDay(), today.plusDays(1).atStartOfDay()) ? MESSAGES_PER_STEP : 0;
        int trackedToday = nudgeRepository.countByUserIdAndSentDate(userId, today)
                + salaryNudgeRepository.countByUserIdAndSentDate(userId, today)
                + legacyBudgetCount;
        return trackedToday + MESSAGES_PER_STEP <= MAX_TRACKED_MESSAGES_PER_DAY;
    }

    private boolean wasContactedRecently(Long userId, LocalDateTime now) {
        LocalDateTime since = now.minusHours(Math.max(1, recentContactGuardHours));
        if (nudgeRepository.countByUserIdAndChannelAndSentAtBetween(
                userId, EngagementNudgeChannel.EMAIL, since, now) > 0) {
            return true;
        }
        return legacyBudgetNudgeRepository.existsByUserIdAndLastSentAtBetween(userId, since, now);
    }

    private Optional<User> loadNudgeableUser(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId)
                .filter(user -> !user.isDeleted())
                .filter(user -> !user.isTestAccount())
                .filter(User::isVerified)
                .filter(this::hasEmail);
    }

    private boolean hasEmail(User user) {
        return user.getEmail() != null && !user.getEmail().isBlank();
    }

    private boolean isReadyWallet(Wallet wallet) {
        return wallet != null
                && wallet.getStatus() == WalletStatus.ACTIVE
                && !wallet.isRevenueWallet()
                && wallet.getAccountNumber() != null
                && !wallet.getAccountNumber().isBlank();
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean isFundedStatus(BudgetStatus status) {
        return status == BudgetStatus.ACTIVE || status == BudgetStatus.SCHEDULED;
    }

    private boolean hasDirectSpend(Long userId) {
        return transactionLogRepository.existsByUserIdAndTransactionTypeInAndStatusIn(
                userId, DIRECT_SPEND_TYPES, DIRECT_SPEND_STATUSES);
    }

    private String routeFor(ActivationStep step, Long budgetId, Long envelopeId) {
        return switch (step) {
            case FUND_WALLET -> ROUTE_WALLET;
            case CREATE_FIRST_BUDGET -> ROUTE_CREATE_BUDGET;
            case REVIEW_FIRST_BUDGET -> budgetId == null ? null : "/budgets/" + budgetId;
            case SPEND_FROM_ENVELOPE -> envelopeId == null ? null : "/envelopes/" + envelopeId;
            case FIRST_DIRECT_SPEND_DONE -> ROUTE_ACTIVITY;
        };
    }

    private ActivationCopy copyFor(ActivationStep step, String envelopeName) {
        String envelopeLabel = envelopeName == null || envelopeName.isBlank()
                ? "your envelope"
                : "'" + envelopeName.trim() + "' envelope";

        return switch (step) {
            case FUND_WALLET -> new ActivationCopy(
                    "activation-fund-wallet-v1",
                    "Your Wisemonie wallet is ready 💚",
                    "Fund with any amount, then turn it into your first plan.",
                    "Your Wisemonie wallet is ready 💚",
                    "Step 1 of 4",
                    "Your wallet is ready. Now fund it with any amount.",
                    "Use your Wisemonie account details to fund your wallet. Once money lands, we will guide you to create your first simple plan with envelopes for things like food, transport, giving, data or savings.",
                    "Start small. Even ₦1,000 is enough to understand the flow.",
                    "Open wallet",
                    EngagementNudgeSegment.WALLET_NOT_FUNDED);
            case CREATE_FIRST_BUDGET -> new ActivationCopy(
                    "activation-create-budget-v1",
                    "Your money has landed 💰",
                    "Create one simple budget so every naira has a clear job.",
                    "Your money has landed. Create your first plan 💰",
                    "Step 2 of 4",
                    "Turn your wallet balance into a plan",
                    "Pick one real need, split the money into envelopes, and choose when each envelope should unlock. That is where Wisemonie starts doing real work for you.",
                    "One practical plan is enough: food, transport, offering, data, family support or savings.",
                    "Create budget",
                    EngagementNudgeSegment.FUNDED_NO_PLAN);
            case REVIEW_FIRST_BUDGET -> new ActivationCopy(
                    "activation-review-budget-v1",
                    "Your first budget is ready 🎯",
                    "Open it and review the envelopes you created.",
                    "Your first Wisemonie budget is ready 🎯",
                    "Step 3 of 4",
                    "Your money now has instructions",
                    "Review your envelopes and their unlock rules so you know what is available now and what is protected for later.",
                    "This is the moment your wallet balance stops being one big tempting number.",
                    "Open budget",
                    EngagementNudgeSegment.ACTIVE_BUDGET);
            case SPEND_FROM_ENVELOPE -> new ActivationCopy(
                    "activation-spend-envelope-v1",
                    "Envelope money unlocked 🔓",
                    "Open " + envelopeLabel + " and spend from the right plan when you are ready.",
                    "Your envelope money is ready 🔓",
                    "Step 4 of 4",
                    "Money is ready in " + envelopeLabel,
                    "Money has moved from the vault into your spendable envelope balance. Use the envelope screen to spend to Wisemonie, bank, airtime or data from the plan.",
                    "Spend from the envelope so money meant for later does not quietly disappear.",
                    "Open envelope",
                    EngagementNudgeSegment.ACTIVE_BUDGET);
            case FIRST_DIRECT_SPEND_DONE -> new ActivationCopy(
                    "activation-first-spend-done-v1",
                    "That is your first planned spend ✅",
                    "Nice. You just spent from a plan, not from guesswork.",
                    "You made your first planned spend ✅",
                    "First spend",
                    "That is the Wisemonie rhythm",
                    "You funded your wallet, created a plan, unlocked money into envelopes and spent from the right place. Keep using envelopes for money you want to protect from impulse decisions.",
                    "Next time money lands, start with one clear plan again.",
                    "View activity",
                    EngagementNudgeSegment.ACTIVE_BUDGET);
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
        int atIndex = email.indexOf("@");
        String prefix = email.substring(0, atIndex > 0 ? atIndex : email.length()).trim();
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

    private void runAfterCommit(String action, Long userId, Runnable task) {
        Runnable guardedTask = () -> {
            try {
                task.run();
            } catch (DataIntegrityViolationException e) {
                logger.info("[ACTIVATION-JOURNEY] Duplicate {} step skipped for user={}", action, userId);
            } catch (Exception e) {
                logger.error("[ACTIVATION-JOURNEY] Failed {} step for user={}", action, userId, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guardedTask.run();
                }
            });
            return;
        }

        guardedTask.run();
    }

    private enum ActivationStep {
        FUND_WALLET,
        CREATE_FIRST_BUDGET,
        REVIEW_FIRST_BUDGET,
        SPEND_FROM_ENVELOPE,
        FIRST_DIRECT_SPEND_DONE
    }

    private record ActivationCopy(String key,
                                  String pushTitle,
                                  String pushBody,
                                  String emailSubject,
                                  String emailTag,
                                  String emailHeadline,
                                  String emailBody,
                                  String footerNote,
                                  String ctaLabel,
                                  EngagementNudgeSegment segment) {
    }
}
