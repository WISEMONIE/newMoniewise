package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.EngagementNudgeNotification;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.EngagementNudgeCampaign;
import com.moniewise.moniewise_backend.enums.EngagementNudgeChannel;
import com.moniewise.moniewise_backend.enums.EngagementNudgeSegment;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.BudgetEngagementNudgeRepository;
import com.moniewise.moniewise_backend.repository.EngagementNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.SalaryNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

@Service
public class HowToUseWisemonieNudgeService {

    private static final Logger logger = LoggerFactory.getLogger(HowToUseWisemonieNudgeService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final String OCCASION_KEY = "HOW_TO_USE_WISEMONIE";
    private static final String WHATSAPP_CHANNEL_URL = "https://whatsapp.com/channel/0029Vb6kU683bbUy3azQF047/234";
    private static final String GUIDE_VIDEO_TITLE = "HOW TO USE WISEMONIE";
    private static final String EMAIL_TAG = "Start here";
    private static final String EMAIL_CTA = "Watch the guide";
    private static final String ACTION_OPEN_EXTERNAL_URL = "OPEN_EXTERNAL_URL";
    private static final int MAX_TRACKED_MESSAGES_PER_DAY = 2;

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final WalletRepository walletRepository;
    private final BudgetRepository budgetRepository;
    private final EngagementNudgeNotificationRepository nudgeRepository;
    private final SalaryNudgeNotificationRepository salaryNudgeRepository;
    private final BudgetEngagementNudgeRepository legacyBudgetNudgeRepository;
    private final NotificationService notificationService;

    @Autowired
    @Lazy
    private HowToUseWisemonieNudgeService self;

    @Value("${moniewise.engagement.how-to-use.enabled:true}")
    private boolean enabled;

    @Value("${moniewise.engagement.how-to-use.batch-size:250}")
    private int batchSize;

    @Value("${moniewise.engagement.how-to-use.signup-lookback-days:180}")
    private int signupLookbackDays;

    @Value("${moniewise.engagement.how-to-use.repeat-interval-days:3}")
    private int repeatIntervalDays;

    @Value("${moniewise.engagement.how-to-use.max-send-days:9}")
    private int maxSendDays;

    public HowToUseWisemonieNudgeService(UserRepository userRepository,
                                         NotificationRepository notificationRepository,
                                         WalletRepository walletRepository,
                                         BudgetRepository budgetRepository,
                                         EngagementNudgeNotificationRepository nudgeRepository,
                                         SalaryNudgeNotificationRepository salaryNudgeRepository,
                                         BudgetEngagementNudgeRepository legacyBudgetNudgeRepository,
                                         NotificationService notificationService) {
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.walletRepository = walletRepository;
        this.budgetRepository = budgetRepository;
        this.nudgeRepository = nudgeRepository;
        this.salaryNudgeRepository = salaryNudgeRepository;
        this.legacyBudgetNudgeRepository = legacyBudgetNudgeRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "${moniewise.engagement.how-to-use.cron:0 30 14 * * SUN,WED}", zone = "Africa/Lagos")
    public void processHowToUseNudges() {
        if (!enabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        LocalDateTime createdAfter = now.minusDays(Math.max(1, signupLookbackDays));
        LocalDateTime sentAfter = now.minusDays(Math.max(1, repeatIntervalDays));
        int pageSize = Math.max(1, batchSize);
        int sent = 0;
        Long afterUserId = 0L;

        while (true) {
            List<User> users = userRepository.findRecentWalletUsersNeedingHowToUseNudgeAfter(
                    afterUserId,
                    createdAfter,
                    now,
                    sentAfter,
                    effectiveMaxSendDays(),
                    pageSize);
            if (users.isEmpty()) {
                break;
            }

            for (User user : users) {
                afterUserId = user.getId();
                try {
                    if (self.sendHowToUseNudgeIfAllowed(user, now)) {
                        sent++;
                    }
                } catch (DataIntegrityViolationException e) {
                    logger.info("[HOW-TO-USE] Duplicate guide nudge skipped for user={}", user.getId());
                } catch (Exception e) {
                    logger.error("[HOW-TO-USE] Failed guide nudge for user={}", user.getId(), e);
                }
            }

            if (users.size() < pageSize) {
                break;
            }
        }

        if (sent > 0) {
            logger.info("[HOW-TO-USE] Sent {} guide nudge(s)", sent);
        }
    }

    @Async
    public void sendImmediateGuideAfterAuth(User user) {
        if (!enabled || user == null || user.getId() == null) {
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
            if (nudgeRepository.countDistinctSendDaysByUserIdAndCampaign(
                    user.getId(), EngagementNudgeCampaign.HOW_TO_USE_WISEMONIE) > 0) {
                return;
            }
            self.sendHowToUseNudgeIfAllowed(user, now);
        } catch (DataIntegrityViolationException e) {
            logger.info("[HOW-TO-USE] Immediate duplicate guide nudge skipped for user={}", user.getId());
        } catch (Exception e) {
            logger.error("[HOW-TO-USE] Immediate guide nudge failed for user={}", user.getId(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendHowToUseNudgeIfAllowed(User user, LocalDateTime now) {
        if (user == null || user.getId() == null || user.isDeleted() || user.isTestAccount()
                || !user.isVerified() || !hasEmail(user)) {
            return false;
        }

        if (!walletRepository.existsReadyUserWallet(user.getId())) {
            return false;
        }

        if (shouldStopGuideNudges(user)) {
            return false;
        }
        if (!hasDailyRoom(user.getId(), now)) {
            return false;
        }
        if (wasSentRecently(user.getId(), now)) {
            return false;
        }

        GuideCopy copy = selectCopy(user.getId(), now);
        String firstName = extractFirstName(user);
        String emailTitle = firstName + ", " + lowerFirstLetter(copy.emailTitle());
        String emailFooter = "The flow is simple: fund your wallet, create one plan, split money into envelopes, choose when money should be available, then spend from the right envelope. You can also watch the pinned video on the Wisemonie WhatsApp channel: \""
                + GUIDE_VIDEO_TITLE
                + "\".";

        record(user.getId(), EngagementNudgeChannel.EMAIL, copy.key(), now);
        notificationService.sendEngagementNudgeEmail(
                user.getEmail(),
                firstName,
                emailTitle,
                EMAIL_TAG,
                emailTitle,
                copy.emailBody(),
                emailFooter,
                EMAIL_CTA,
                WHATSAPP_CHANNEL_URL);

        record(user.getId(), EngagementNudgeChannel.IN_APP, copy.key(), now);
        notificationService.sendNotification(
                user.getId().toString(),
                guideNotificationMessage(firstName, copy),
                NotificationType.HOW_TO_USE_WISEMONIE,
                null,
                null,
                ACTION_OPEN_EXTERNAL_URL,
                WHATSAPP_CHANNEL_URL);

        return true;
    }

    private boolean wasSentRecently(Long userId, LocalDateTime now) {
        return nudgeRepository.countByUserIdAndCampaignAndSentAtAfter(
                userId,
                EngagementNudgeCampaign.HOW_TO_USE_WISEMONIE,
                now.minusDays(Math.max(1, repeatIntervalDays))) > 0;
    }

    private boolean hasDailyRoom(Long userId, LocalDateTime now) {
        return totalTrackedMessagesToday(userId, now.toLocalDate()) + 2 <= MAX_TRACKED_MESSAGES_PER_DAY;
    }

    private int totalTrackedMessagesToday(Long userId, LocalDate today) {
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        int engagementCount = nudgeRepository.countByUserIdAndSentDate(userId, today);
        int salaryCount = salaryNudgeRepository.countByUserIdAndSentDate(userId, today);
        int legacyBudgetCount = legacyBudgetNudgeRepository.existsByUserIdAndLastSentAtBetween(userId, start, end)
                ? 2
                : 0;
        return engagementCount + salaryCount + legacyBudgetCount;
    }

    private boolean shouldStopGuideNudges(User user) {
        Long userId = user.getId();
        return notificationRepository.existsByUserIdAndTypeAndIsReadTrue(userId, NotificationType.HOW_TO_USE_WISEMONIE)
                || walletRepository.existsFundedUserWallet(userId)
                || budgetRepository.existsByUserIdAndStatus(userId, BudgetStatus.ACTIVE)
                || budgetRepository.existsByUserIdAndStatus(userId, BudgetStatus.SCHEDULED)
                || nudgeRepository.countDistinctSendDaysByUserIdAndCampaign(
                        userId, EngagementNudgeCampaign.HOW_TO_USE_WISEMONIE) >= effectiveMaxSendDays();
    }

    private int effectiveMaxSendDays() {
        return Math.max(1, maxSendDays);
    }

    private void record(Long userId,
                        EngagementNudgeChannel channel,
                        String copyKey,
                        LocalDateTime sentAt) {
        EngagementNudgeNotification nudge = new EngagementNudgeNotification();
        nudge.setUserId(userId);
        nudge.setCampaign(EngagementNudgeCampaign.HOW_TO_USE_WISEMONIE);
        nudge.setSegment(EngagementNudgeSegment.NO_ACTIVE_BUDGET);
        nudge.setChannel(channel);
        nudge.setOccasionKey(OCCASION_KEY);
        nudge.setCopyKey(copyKey);
        nudge.setSentDate(sentAt.toLocalDate());
        nudge.setSentAt(sentAt);
        nudgeRepository.save(nudge);
    }

    private GuideCopy selectCopy(Long userId, LocalDateTime now) {
        Set<String> sentRecently = new HashSet<>(
                nudgeRepository.findCopyKeysSentSince(userId, now.minusMonths(3)));
        List<GuideCopy> eligible = guideCopies().stream()
                .filter(copy -> !sentRecently.contains(copy.key()))
                .toList();

        List<GuideCopy> pool = eligible.isEmpty() ? guideCopies() : eligible;
        return pool.get(new Random(Objects.hash(userId, now.toLocalDate())).nextInt(pool.size()));
    }

    private String guideNotificationMessage(String firstName, GuideCopy copy) {
        String greetingName = firstName == null || firstName.isBlank() ? "there" : firstName;
        return "Hello " + greetingName + ", " + copy.notificationBody() + " " + WHATSAPP_CHANNEL_URL;
    }

    private List<GuideCopy> guideCopies() {
        return List.of(
                new GuideCopy(
                        "how-to-use-01",
                        "your Wisemonie wallet is ready \uD83E\uDDED",
                        "Thank you for downloading Wisemonie. You probably came because you want money to feel less scattered and more intentional. The first step can feel unclear, so we made a short video guide for you. Watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" to see how to fund your wallet, create a simple budget, use envelopes and spend from your plan without mental maths.",
                        "welcome to Wisemonie. If getting started feels unclear, watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" now:"),
                new GuideCopy(
                        "how-to-use-02",
                        "start with one small plan \uD83C\uDF31",
                        "You do not need to figure everything out alone. A calm money habit can start with one small plan: food, transport, savings, family support or giving. The \""
                                + GUIDE_VIDEO_TITLE
                                + "\" video shows the steps clearly, so you can use Wisemonie with confidence from your first budget.",
                        "one small plan is enough to start. Watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" and see the simple first step:"),
                new GuideCopy(
                        "how-to-use-03",
                        "no pressure, just a clearer first step \uD83D\uDE0C",
                        "Many people sign up because they want better control, then pause because the next action is not obvious. That is normal. We recorded \""
                                + GUIDE_VIDEO_TITLE
                                + "\" to show you how the wallet, budget and envelopes work together as one real spending plan.",
                        "no pressure. If you are not sure what to do next, watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" here:"),
                new GuideCopy(
                        "how-to-use-04",
                        "turn the account into a plan \uD83C\uDFAF",
                        "A Wisemonie account becomes powerful when you tell your money what to do before pressure arrives. The guide video walks you through funding, creating envelopes, setting disbursement rules and spending from the right plan.",
                        "your account becomes powerful when your money has a plan. Watch the quick guide here:"),
                new GuideCopy(
                        "how-to-use-05",
                        "your money can feel easier this month \uD83D\uDC9A",
                        "Wisemonie is not just another wallet. It is a way to reduce the stress of calculating every spend in your head. Watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" and see how to plan food, transport, family support, giving, enjoyment and savings before the month gets loud.",
                        "Wisemonie can help reduce money stress. Watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" and see how it works:"),
                new GuideCopy(
                        "how-to-use-06",
                        "let Wisemonie show you the rhythm \uD83D\uDCDD",
                        "If you opened Wisemonie because impulse spending has been tiring, you are in the right place. The guide shows how to put money in, create a budget, choose when money should be available and spend directly from the plan.",
                        "if impulse spending has been tiring, this guide will help you start with clarity:"),
                new GuideCopy(
                        "how-to-use-07",
                        "your first budget can be simple \uD83C\uDF92",
                        "You do not have to start with a big complicated budget. Start with something familiar: lunch at work, transport, offering, snacks, data, family support or savings. The guide shows how to set that up quickly on Wisemonie.",
                        "your first budget can be simple: lunch, transport, family or savings. Watch the guide here:"),
                new GuideCopy(
                        "how-to-use-08",
                        "less mental maths, more calm \uD83E\uDDE0",
                        "One big balance can make spending feel confusing. Wisemonie helps you split money into clear envelopes so every naira has a job. Watch the guide and see how to move from guessing to planning.",
                        "one big balance can be confusing. Watch how Wisemonie turns it into clear envelopes:"),
                new GuideCopy(
                        "how-to-use-09",
                        "see how to spend from the plan \uD83D\uDCB3",
                        "The best part is not only planning. Wisemonie helps you spend directly from the plan, so money meant for later does not quietly disappear. Watch the guide to see the full flow.",
                        "you can plan and spend from the plan. Watch \""
                                + GUIDE_VIDEO_TITLE
                                + "\" here:"),
                new GuideCopy(
                        "how-to-use-10",
                        "make Wisemonie useful today \uD83D\uDE0A",
                        "Your wallet is ready. The next step is simply understanding the flow: fund wallet, create budget, split into envelopes, set when money is available, then spend with more peace. The video shows it clearly.",
                        "your wallet is ready. Watch the quick video and make Wisemonie useful today:")
        );
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

    private String lowerFirstLetter(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int firstCodePoint = value.codePointAt(0);
        int lowerCodePoint = Character.toLowerCase(firstCodePoint);
        return new String(Character.toChars(lowerCodePoint)) + value.substring(Character.charCount(firstCodePoint));
    }

    private record GuideCopy(String key,
                             String emailTitle,
                             String emailBody,
                             String notificationBody) {
    }
}
