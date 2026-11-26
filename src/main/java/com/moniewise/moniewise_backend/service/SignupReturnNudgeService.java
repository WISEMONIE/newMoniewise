package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.EngagementNudgeNotification;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.EngagementNudgeCampaign;
import com.moniewise.moniewise_backend.enums.EngagementNudgeChannel;
import com.moniewise.moniewise_backend.enums.EngagementNudgeSegment;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.BudgetEngagementNudgeRepository;
import com.moniewise.moniewise_backend.repository.EngagementNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.SalaryNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

@Service
public class SignupReturnNudgeService {

    private static final Logger logger = LoggerFactory.getLogger(SignupReturnNudgeService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final String OCCASION_KEY = "SIGNUP_RETURN_48H";
    private static final String EMAIL_TAG = "A gentle reset";
    private static final String EMAIL_CTA = "Return to Wisemonie";
    private static final long PUSH_TTL_SECONDS = 172_800L;
    private static final int MAX_TRACKED_MESSAGES_PER_DAY = 2;

    private final UserRepository userRepository;
    private final EngagementNudgeNotificationRepository nudgeRepository;
    private final SalaryNudgeNotificationRepository salaryNudgeRepository;
    private final BudgetEngagementNudgeRepository legacyBudgetNudgeRepository;
    private final NotificationService notificationService;
    private final AuthSessionService authSessionService;

    @Autowired
    @Lazy
    private SignupReturnNudgeService self;

    @Value("${moniewise.engagement.signup-return.enabled:${MONIEWISE_SIGNUP_RETURN_NUDGE_ENABLED:true}}")
    private boolean enabled;

    @Value("${moniewise.engagement.signup-return.batch-size:${MONIEWISE_SIGNUP_RETURN_NUDGE_BATCH_SIZE:250}}")
    private int batchSize;

    @Value("${moniewise.engagement.signup-return.wait-hours:${MONIEWISE_SIGNUP_RETURN_NUDGE_WAIT_HOURS:48}}")
    private int waitHours;

    @Value("${moniewise.engagement.signup-return.signup-lookback-days:${MONIEWISE_SIGNUP_RETURN_NUDGE_LOOKBACK_DAYS:14}}")
    private int signupLookbackDays;

    @Value("${moniewise.engagement.signup-return.return-grace-minutes:${MONIEWISE_SIGNUP_RETURN_NUDGE_RETURN_GRACE_MINUTES:60}}")
    private int returnGraceMinutes;

    @Value("${app.base-url:http://localhost:9000}")
    private String appBaseUrl;

    public SignupReturnNudgeService(UserRepository userRepository,
                                    EngagementNudgeNotificationRepository nudgeRepository,
                                    SalaryNudgeNotificationRepository salaryNudgeRepository,
                                    BudgetEngagementNudgeRepository legacyBudgetNudgeRepository,
                                    NotificationService notificationService,
                                    AuthSessionService authSessionService) {
        this.userRepository = userRepository;
        this.nudgeRepository = nudgeRepository;
        this.salaryNudgeRepository = salaryNudgeRepository;
        this.legacyBudgetNudgeRepository = legacyBudgetNudgeRepository;
        this.notificationService = notificationService;
        this.authSessionService = authSessionService;
    }

    @Scheduled(cron = "${moniewise.engagement.signup-return.cron:${MONIEWISE_SIGNUP_RETURN_NUDGE_CRON:0 20 10 * * ?}}", zone = "Africa/Lagos")
    public void processSignupReturnNudges() {
        if (!enabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        LocalDateTime eligibleBefore = now.minusHours(Math.max(1, waitHours));
        LocalDateTime createdAfter = now.minusDays(Math.max(1, signupLookbackDays));
        int pageSize = Math.max(1, batchSize);
        int graceMinutes = Math.max(5, returnGraceMinutes);
        int sent = 0;
        Long afterUserId = 0L;

        while (true) {
            List<User> users = userRepository.findSignupUsersInactiveAfter48Hours(
                    afterUserId,
                    createdAfter,
                    eligibleBefore,
                    graceMinutes,
                    pageSize);

            if (users.isEmpty()) {
                break;
            }

            for (User user : users) {
                afterUserId = user.getId();
                try {
                    if (self.sendSignupReturnNudgeIfAllowed(user, now)) {
                        sent++;
                    }
                } catch (DataIntegrityViolationException e) {
                    logger.info("[SIGNUP-RETURN] Duplicate 48h nudge skipped for user={}", user.getId());
                } catch (Exception e) {
                    logger.error("[SIGNUP-RETURN] Failed 48h nudge for user={}", user.getId(), e);
                }
            }

            if (users.size() < pageSize) {
                break;
            }
        }

        if (sent > 0) {
            logger.info("[SIGNUP-RETURN] Sent {} 48h return nudge(s)", sent);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendSignupReturnNudgeIfAllowed(User user, LocalDateTime now) {
        if (user == null || user.getId() == null || user.isDeleted() || user.isTestAccount() || !hasEmail(user)) {
            return false;
        }
        if (nudgeRepository.countByUserIdAndCampaignAndSentAtAfter(
                user.getId(), EngagementNudgeCampaign.SIGNUP_RETURN_48H, now.minusYears(5)) > 0) {
            return false;
        }

        boolean pushAvailable = hasPushTarget(user.getId());
        if (!hasDailyRoom(user.getId(), now, pushAvailable)) {
            return false;
        }

        String firstName = extractFirstName(user);
        ReturnCopy copy = selectCopy(user.getId(), now);
        String subject = personalize(firstName, copy.emailSubject());
        String headline = personalize(firstName, copy.emailHeadline());

        record(user.getId(), EngagementNudgeChannel.EMAIL, copy.key(), now);
        notificationService.sendEngagementNudgeEmail(
                user.getEmail(),
                firstName,
                subject,
                EMAIL_TAG,
                headline,
                copy.emailBody(),
                copy.footerNote(),
                EMAIL_CTA,
                appBaseUrl);

        if (pushAvailable) {
            record(user.getId(), EngagementNudgeChannel.PUSH, copy.key(), now);
            notificationService.enqueuePushOnlyNotification(
                    user.getId(),
                    NotificationType.SIGNUP_RETURN_NUDGE,
                    personalize(firstName, copy.pushTitle()),
                    copy.pushBody(),
                    "/dashboard",
                    PUSH_TTL_SECONDS);
        }

        return true;
    }

    private boolean hasDailyRoom(Long userId, LocalDateTime now, boolean pushAvailable) {
        int plannedMessages = pushAvailable ? 2 : 1;
        return totalTrackedMessagesToday(userId, now.toLocalDate()) + plannedMessages <= MAX_TRACKED_MESSAGES_PER_DAY;
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

    private boolean hasPushTarget(Long userId) {
        try {
            if (!authSessionService.getActiveFcmTokens(userId).isEmpty()) {
                return true;
            }
        } catch (Exception e) {
            logger.debug("[SIGNUP-RETURN] Could not read active FCM sessions for user={}: {}", userId, e.getMessage());
        }

        try {
            String fallbackToken = userRepository.findFcmTokenById(userId);
            return fallbackToken != null && !fallbackToken.isBlank();
        } catch (Exception e) {
            logger.debug("[SIGNUP-RETURN] Could not read fallback FCM token for user={}: {}", userId, e.getMessage());
            return false;
        }
    }

    private void record(Long userId,
                        EngagementNudgeChannel channel,
                        String copyKey,
                        LocalDateTime sentAt) {
        EngagementNudgeNotification nudge = new EngagementNudgeNotification();
        nudge.setUserId(userId);
        nudge.setCampaign(EngagementNudgeCampaign.SIGNUP_RETURN_48H);
        nudge.setSegment(EngagementNudgeSegment.INACTIVE_AFTER_SIGNUP);
        nudge.setChannel(channel);
        nudge.setOccasionKey(OCCASION_KEY);
        nudge.setCopyKey(copyKey);
        nudge.setSentDate(sentAt.toLocalDate());
        nudge.setSentAt(sentAt);
        nudgeRepository.save(nudge);
    }

    private ReturnCopy selectCopy(Long userId, LocalDateTime now) {
        List<ReturnCopy> copies = returnCopies();
        return copies.get(new Random(Objects.hash(userId, now.toLocalDate())).nextInt(copies.size()));
    }

    private List<ReturnCopy> returnCopies() {
        return List.of(
                new ReturnCopy(
                        "signup-return-48h-01",
                        "{firstName}, you signed up for a reason 🧭",
                        "{firstName}, let us make the first step lighter",
                        "Most people do not open a money app because they are bored. They open it because something about spending, saving, family requests, transport, food, bills or impulse pressure has started taking too much mental energy. If you paused after signup, that is okay. Come back and create just one simple envelope today. One plan is enough to show your money where to go before the month starts pushing back.",
                        "You do not need to set up everything. Start with food, transport, savings or family support. Let Wisemonie hold the plan while you breathe.",
                        "{firstName}, one small plan 🧭",
                        "You signed up for a reason. Come back and create one envelope so this month feels less stressful."),
                new ReturnCopy(
                        "signup-return-48h-02",
                        "{firstName}, the pressure can get lighter 😌",
                        "Your money does not have to stay in your head",
                        "That mental maths before every spend can become exhausting: can I afford this lunch, this ride, this family request, this offering, this small enjoyment? Wisemonie was built so you do not have to carry all of that in your head. Come back and set one plan. Even one envelope can turn a confusing balance into a calmer decision.",
                        "Start small. A simple food or transport envelope can change how the rest of the week feels.",
                        "Less mental maths, {firstName} 😌",
                        "Come back and set one simple money plan. Food, transport, family or savings can feel easier with an envelope."),
                new ReturnCopy(
                        "signup-return-48h-03",
                        "{firstName}, do not let old money pressure win 🌱",
                        "The first plan is the real win",
                        "It is easy to sign up for something helpful and still go back to the old pattern because the first step feels like work. But the old pattern is what keeps money feeling random. Give Wisemonie one small chance today: create a plan for one thing you already spend on. Food. Transport. Family. Savings. The goal is not perfection; it is control you can feel.",
                        "You are not behind. You only need one next step.",
                        "Give it one chance 🌱",
                        "Create one plan for something you already spend on. Small control today can reduce money pressure later."),
                new ReturnCopy(
                        "signup-return-48h-04",
                        "{firstName}, your future self needs this small setup 💚",
                        "A few minutes now can protect the rest of the month",
                        "Money stress usually does not arrive loudly. It builds from many small unplanned spends until the balance no longer makes sense. Wisemonie helps you decide ahead: this is for food, this is for transport, this is for savings, this is for family, this is for enjoyment. Come back and set one envelope. Your future self will thank you when spending feels clearer.",
                        "Small planning now can save you from pressure later.",
                        "Protect this month 💚",
                        "Come back and give one part of your money a job. Your future self will thank you."),
                new ReturnCopy(
                        "signup-return-48h-05",
                        "{firstName}, start where the pressure is loudest 🎯",
                        "You do not need a perfect budget to begin",
                        "If money has been feeling tight, scattered or emotionally heavy, do not wait until you have the perfect plan. Start with the area that stresses you most: transport, feeding, family support, savings, rent, giving or small enjoyment. Wisemonie helps you separate the money, follow the plan and spend without guessing every time.",
                        "One envelope is a good beginning. You can improve the rest later.",
                        "Start with one thing 🎯",
                        "Open Wisemonie and plan the spend that stresses you most. One envelope is enough to begin.")
        );
    }

    private String personalize(String firstName, String template) {
        String safeName = firstName == null || firstName.isBlank() ? "there" : firstName;
        return template.replace("{firstName}", safeName);
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

    private record ReturnCopy(String key,
                              String emailSubject,
                              String emailHeadline,
                              String emailBody,
                              String footerNote,
                              String pushTitle,
                              String pushBody) {
    }
}
