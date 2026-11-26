package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.EngagementNudgeNotification;
import com.moniewise.moniewise_backend.entity.EngagementSpecialOccasion;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.EngagementNudgeCampaign;
import com.moniewise.moniewise_backend.enums.EngagementNudgeChannel;
import com.moniewise.moniewise_backend.enums.EngagementNudgeSegment;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.Gender;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.BudgetEngagementNudgeRepository;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EngagementNudgeNotificationRepository;
import com.moniewise.moniewise_backend.repository.EngagementSpecialOccasionRepository;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

@Service
public class EngagementNudgeService {

    private static final Logger logger = LoggerFactory.getLogger(EngagementNudgeService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final long ENGAGEMENT_PUSH_TTL_SECONDS = 86_400L;
    private static final int MAX_ENGAGEMENT_MESSAGES_PER_DAY = 2;
    private static final String BUDGET_ROUTE = "/budgets";

    private final UserRepository userRepository;
    private final BudgetRepository budgetRepository;
    private final EngagementNudgeNotificationRepository nudgeRepository;
    private final EngagementSpecialOccasionRepository occasionRepository;
    private final BudgetEngagementNudgeRepository legacyBudgetNudgeRepository;
    private final SalaryNudgeNotificationRepository salaryNudgeRepository;
    private final NotificationService notificationService;
    private final AuthSessionService authSessionService;

    @Autowired
    @Lazy
    private EngagementNudgeService self;

    @Value("${moniewise.engagement.mid-month.enabled:true}")
    private boolean midMonthEnabled;

    @Value("${moniewise.engagement.special-occasions.enabled:true}")
    private boolean specialOccasionsEnabled;

    @Value("${moniewise.engagement.birthdays.enabled:true}")
    private boolean birthdayEnabled;

    @Value("${moniewise.engagement.mid-month.batch-size:500}")
    private int batchSize;

    public EngagementNudgeService(UserRepository userRepository,
                                  BudgetRepository budgetRepository,
                                  EngagementNudgeNotificationRepository nudgeRepository,
                                  EngagementSpecialOccasionRepository occasionRepository,
                                  BudgetEngagementNudgeRepository legacyBudgetNudgeRepository,
                                  SalaryNudgeNotificationRepository salaryNudgeRepository,
                                  NotificationService notificationService,
                                  AuthSessionService authSessionService) {
        this.userRepository = userRepository;
        this.budgetRepository = budgetRepository;
        this.nudgeRepository = nudgeRepository;
        this.occasionRepository = occasionRepository;
        this.legacyBudgetNudgeRepository = legacyBudgetNudgeRepository;
        this.salaryNudgeRepository = salaryNudgeRepository;
        this.notificationService = notificationService;
        this.authSessionService = authSessionService;
    }

    @Scheduled(cron = "${moniewise.engagement.mid-month.monday-cron:0 15 10 * * MON}", zone = "Africa/Lagos")
    public void processMondayMorningNudges() {
        processMidMonthSlot(MidMonthSlot.MONDAY_MORNING);
    }

    @Scheduled(cron = "${moniewise.engagement.mid-month.wednesday-cron:0 30 14 * * WED}", zone = "Africa/Lagos")
    public void processWednesdayAfternoonNudges() {
        processMidMonthSlot(MidMonthSlot.WEDNESDAY_AFTERNOON);
    }

    @Scheduled(cron = "${moniewise.engagement.mid-month.friday-cron:0 30 18 * * FRI}", zone = "Africa/Lagos")
    public void processFridayEveningNudges() {
        processMidMonthSlot(MidMonthSlot.FRIDAY_EVENING);
    }

    @Scheduled(cron = "${moniewise.engagement.mid-month.saturday-cron:0 15 18 * * SAT}", zone = "Africa/Lagos")
    public void processSaturdayEveningNudges() {
        processMidMonthSlot(MidMonthSlot.SATURDAY_EVENING);
    }

    @Scheduled(cron = "${moniewise.engagement.birthdays.cron:0 5 10 * * ?}", zone = "Africa/Lagos")
    public void processBirthdayNudges() {
        if (!birthdayEnabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        LocalDate today = now.toLocalDate();
        String monthDay = String.format("%02d-%02d", today.getMonthValue(), today.getDayOfMonth());
        int sent = 0;
        Long afterUserId = 0L;

        while (true) {
            List<User> users = userRepository.findBirthdayWalletUsersAfter(
                    monthDay,
                    afterUserId,
                    Math.max(1, batchSize));
            if (users.isEmpty()) {
                break;
            }

            for (User user : users) {
                afterUserId = user.getId();
                try {
                    if (self.sendBirthdayNudgeIfAllowed(user, now)) {
                        sent++;
                    }
                } catch (DataIntegrityViolationException e) {
                    logger.info("[ENGAGEMENT] Duplicate birthday nudge skipped for user={}", user.getId());
                } catch (Exception e) {
                    logger.error("[ENGAGEMENT] Failed birthday nudge for user={}", user.getId(), e);
                }
            }

            if (users.size() < Math.max(1, batchSize)) {
                break;
            }
        }

        if (sent > 0) {
            logger.info("[ENGAGEMENT] Sent {} birthday nudge(s)", sent);
        }
    }

    @Scheduled(cron = "${moniewise.engagement.special-occasions.cron:0 45 10 * * ?}", zone = "Africa/Lagos")
    public void processSpecialOccasionNudges() {
        if (!specialOccasionsEnabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        Optional<SpecialOccasion> occasion = topOccasionFor(now.toLocalDate());
        if (occasion.isEmpty()) {
            return;
        }

        int activeSent = processOccasionSegment(occasion.get(), EngagementNudgeSegment.ACTIVE_BUDGET, now);
        int noBudgetSent = processOccasionSegment(occasion.get(), EngagementNudgeSegment.NO_ACTIVE_BUDGET, now);
        int total = activeSent + noBudgetSent;
        if (total > 0) {
            logger.info("[ENGAGEMENT] Sent {} special occasion nudge(s) for {}", total, occasion.get().key());
        }
    }

    private void processMidMonthSlot(MidMonthSlot slot) {
        if (!midMonthEnabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        LocalDate today = now.toLocalDate();
        if (!isMidMonthWindow(today) || today.getDayOfWeek() != slot.dayOfWeek()) {
            return;
        }

        if (topOccasionFor(today).isPresent()) {
            logger.info("[ENGAGEMENT] Skipping {} mid-month nudges because today has a special occasion", slot);
            return;
        }

        int activeSent = processMidMonthSegment(slot, EngagementNudgeSegment.ACTIVE_BUDGET, now);
        int noBudgetSent = processMidMonthSegment(slot, EngagementNudgeSegment.NO_ACTIVE_BUDGET, now);
        int total = activeSent + noBudgetSent;
        if (total > 0) {
            logger.info("[ENGAGEMENT] Sent {} mid-month {} nudge(s): activeBudget={}, noActiveBudget={}",
                    total, slot, activeSent, noBudgetSent);
        }
    }

    private int processMidMonthSegment(MidMonthSlot slot,
                                       EngagementNudgeSegment segment,
                                       LocalDateTime now) {
        int sent = 0;
        Long afterUserId = 0L;
        int pageSize = Math.max(1, batchSize);

        while (true) {
            List<User> users = segment == EngagementNudgeSegment.ACTIVE_BUDGET
                    ? userRepository.findActiveBudgetEngagementUsersAfter(afterUserId, pageSize)
                    : userRepository.findNoActiveBudgetEngagementUsersAfter(afterUserId, pageSize);
            if (users.isEmpty()) {
                break;
            }

            for (User user : users) {
                afterUserId = user.getId();
                try {
                    if (self.sendMidMonthNudgeIfAllowed(user, segment, slot, now)) {
                        sent++;
                    }
                } catch (DataIntegrityViolationException e) {
                    logger.info("[ENGAGEMENT] Duplicate {} {} nudge skipped for user={}",
                            segment, slot, user.getId());
                } catch (Exception e) {
                    logger.error("[ENGAGEMENT] Failed {} {} nudge for user={}", segment, slot, user.getId(), e);
                }
            }

            if (users.size() < pageSize) {
                break;
            }
        }

        return sent;
    }

    private int processOccasionSegment(SpecialOccasion occasion,
                                       EngagementNudgeSegment segment,
                                       LocalDateTime now) {
        int sent = 0;
        Long afterUserId = 0L;
        int pageSize = Math.max(1, batchSize);

        while (true) {
            List<User> users = segment == EngagementNudgeSegment.ACTIVE_BUDGET
                    ? userRepository.findActiveBudgetEngagementUsersAfter(afterUserId, pageSize)
                    : userRepository.findNoActiveBudgetEngagementUsersAfter(afterUserId, pageSize);
            if (users.isEmpty()) {
                break;
            }

            for (User user : users) {
                afterUserId = user.getId();
                if (!isUserEligibleForOccasion(user, occasion)) {
                    continue;
                }
                if (isBirthdayToday(user, now.toLocalDate())) {
                    continue;
                }
                try {
                    if (self.sendSpecialOccasionNudgeIfAllowed(user, segment, occasion, now)) {
                        sent++;
                    }
                } catch (DataIntegrityViolationException e) {
                    logger.info("[ENGAGEMENT] Duplicate occasion {} nudge skipped for user={}",
                            occasion.key(), user.getId());
                } catch (Exception e) {
                    logger.error("[ENGAGEMENT] Failed occasion {} nudge for user={}", occasion.key(), user.getId(), e);
                }
            }

            if (users.size() < pageSize) {
                break;
            }
        }

        return sent;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendMidMonthNudgeIfAllowed(User user,
                                              EngagementNudgeSegment segment,
                                              MidMonthSlot slot,
                                              LocalDateTime now) {
        if (!canContact(user, now.toLocalDate())) {
            return false;
        }

        EngagementNudgeChannel channel = selectWeeklyChannel(user, now, false);
        if (!canUseChannel(user, channel)) {
            return false;
        }

        String occasionKey = slot.name();
        if (alreadySent(user.getId(), EngagementNudgeCampaign.MID_MONTH, occasionKey, channel, now.toLocalDate())) {
            return false;
        }

        AgeBand ageBand = ageBandFor(user, now.toLocalDate());
        NudgeCopy copy = selectCopy(
                user.getId(),
                midMonthCopies(segment, slot),
                segment,
                slot.name(),
                ageBand,
                now);
        NudgeCopy personalized = personalize(copy, user, segment, ageBand);

        record(user.getId(), EngagementNudgeCampaign.MID_MONTH, segment, channel,
                occasionKey, personalized.key(), now);
        deliver(user, channel, NotificationType.MID_MONTH_NUDGE, personalized,
                "Mid-month money check", midMonthFooter(segment, ageBand));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendSpecialOccasionNudgeIfAllowed(User user,
                                                     EngagementNudgeSegment segment,
                                                     SpecialOccasion occasion,
                                                     LocalDateTime now) {
        if (!canContact(user, now.toLocalDate())) {
            return false;
        }
        if (!isUserEligibleForOccasion(user, occasion)) {
            return false;
        }

        EngagementNudgeChannel channel = selectOccasionChannel(user, now);
        if (!canUseChannel(user, channel)) {
            return false;
        }

        if (alreadySent(user.getId(), EngagementNudgeCampaign.SPECIAL_OCCASION,
                occasion.key(), channel, now.toLocalDate())) {
            return false;
        }

        AgeBand ageBand = ageBandFor(user, now.toLocalDate());
        NudgeCopy copy = selectCopy(
                user.getId(),
                occasionCopies(segment, occasion),
                segment,
                occasion.key(),
                ageBand,
                now);
        NudgeCopy personalized = personalize(copy, user, segment, ageBand);

        record(user.getId(), EngagementNudgeCampaign.SPECIAL_OCCASION, segment, channel,
                occasion.key(), personalized.key(), now);
        deliver(user, channel, NotificationType.SPECIAL_OCCASION_NUDGE, personalized,
                occasion.displayName(), occasionFooter(segment, occasion));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendBirthdayNudgeIfAllowed(User user, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (!canContact(user, today)) {
            return false;
        }
        if (!isBirthdayToday(user, today)) {
            return false;
        }

        EngagementNudgeChannel channel = hasPushTarget(user.getId())
                ? EngagementNudgeChannel.PUSH
                : EngagementNudgeChannel.EMAIL;
        if (!canUseChannel(user, channel)) {
            return false;
        }

        if (alreadySent(user.getId(), EngagementNudgeCampaign.BIRTHDAY, "BIRTHDAY", channel, today)) {
            return false;
        }

        EngagementNudgeSegment segment = hasActiveBudget(user)
                ? EngagementNudgeSegment.ACTIVE_BUDGET
                : EngagementNudgeSegment.NO_ACTIVE_BUDGET;
        AgeBand ageBand = ageBandFor(user, today);
        NudgeCopy copy = selectCopy(
                user.getId(),
                birthdayCopies(segment, ageBand),
                segment,
                "BIRTHDAY",
                ageBand,
                now);
        NudgeCopy personalized = personalize(copy, user, segment, ageBand);

        record(user.getId(), EngagementNudgeCampaign.BIRTHDAY, segment, channel,
                "BIRTHDAY", personalized.key(), now);
        deliver(user, channel, NotificationType.BIRTHDAY_NUDGE, personalized,
                "Birthday money note", birthdayFooter(ageBand));
        return true;
    }

    private boolean canContact(User user, LocalDate today) {
        if (user == null || user.getId() == null || user.isDeleted() || user.isTestAccount()) {
            return false;
        }
        return totalTrackedMessagesToday(user.getId(), today) < MAX_ENGAGEMENT_MESSAGES_PER_DAY;
    }

    private boolean isUserEligibleForOccasion(User user, SpecialOccasion occasion) {
        if (user == null || occasion == null || occasion.key() == null) {
            return false;
        }

        return switch (occasion.key()) {
            case "WORLD_GIRLFRIENDS_DAY", "INTERNATIONAL_MENS_DAY", "FATHERS_DAY" ->
                    user.getGender() == Gender.MALE;
            case "INTERNATIONAL_WOMENS_DAY", "MOTHERS_DAY" ->
                    user.getGender() == Gender.FEMALE;
            default -> true;
        };
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

    private EngagementNudgeChannel selectWeeklyChannel(User user, LocalDateTime now, boolean allowBirthdayEmailOverride) {
        if (hasEmail(user) && (allowBirthdayEmailOverride || !hasReceivedEmailThisWeek(user.getId(), now))) {
            return EngagementNudgeChannel.EMAIL;
        }
        return EngagementNudgeChannel.PUSH;
    }

    private EngagementNudgeChannel selectOccasionChannel(User user, LocalDateTime now) {
        if (hasPushTarget(user.getId())) {
            return EngagementNudgeChannel.PUSH;
        }
        if (hasEmail(user) && !hasReceivedEmailThisWeek(user.getId(), now)) {
            return EngagementNudgeChannel.EMAIL;
        }
        return EngagementNudgeChannel.PUSH;
    }

    private boolean hasReceivedEmailThisWeek(Long userId, LocalDateTime now) {
        LocalDate weekStartDate = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDateTime weekStart = weekStartDate.atStartOfDay();
        LocalDateTime weekEnd = weekStart.plusDays(7);
        return nudgeRepository.countByUserIdAndChannelAndSentAtBetween(
                userId,
                EngagementNudgeChannel.EMAIL,
                weekStart,
                weekEnd) > 0;
    }

    private boolean canUseChannel(User user, EngagementNudgeChannel channel) {
        if (channel == EngagementNudgeChannel.EMAIL) {
            return hasEmail(user);
        }
        return hasPushTarget(user.getId());
    }

    private boolean alreadySent(Long userId,
                                EngagementNudgeCampaign campaign,
                                String occasionKey,
                                EngagementNudgeChannel channel,
                                LocalDate today) {
        return nudgeRepository.existsByUserIdAndCampaignAndOccasionKeyAndChannelAndSentDate(
                userId,
                campaign,
                occasionKey,
                channel,
                today);
    }

    private void record(Long userId,
                        EngagementNudgeCampaign campaign,
                        EngagementNudgeSegment segment,
                        EngagementNudgeChannel channel,
                        String occasionKey,
                        String copyKey,
                        LocalDateTime sentAt) {
        EngagementNudgeNotification nudge = new EngagementNudgeNotification();
        nudge.setUserId(userId);
        nudge.setCampaign(campaign);
        nudge.setSegment(segment);
        nudge.setChannel(channel);
        nudge.setOccasionKey(occasionKey);
        nudge.setCopyKey(copyKey);
        nudge.setSentDate(sentAt.toLocalDate());
        nudge.setSentAt(sentAt);
        nudgeRepository.save(nudge);
    }

    private void deliver(User user,
                         EngagementNudgeChannel channel,
                         NotificationType type,
                         NudgeCopy copy,
                         String tag,
                         String footerNote) {
        if (channel == EngagementNudgeChannel.EMAIL) {
            notificationService.sendEngagementNudgeEmail(
                    user.getEmail(),
                    firstName(user),
                    copy.title(),
                    tag,
                    copy.title(),
                    copy.body(),
                    footerNote,
                    "Open Wisemonie",
                    null);
            return;
        }

        notificationService.enqueuePushOnlyNotification(
                user.getId(),
                type,
                copy.title(),
                copy.body(),
                BUDGET_ROUTE,
                ENGAGEMENT_PUSH_TTL_SECONDS);
    }

    private NudgeCopy selectCopy(Long userId,
                                 List<NudgeCopy> copies,
                                 EngagementNudgeSegment segment,
                                 String contextKey,
                                 AgeBand ageBand,
                                 LocalDateTime now) {
        Set<String> sentRecently = new HashSet<>(
                nudgeRepository.findCopyKeysSentSince(userId, now.minusMonths(3)));
        List<NudgeCopy> eligible = copies.stream()
                .map(copy -> withStableKey(copy, segment, contextKey, ageBand))
                .filter(copy -> !sentRecently.contains(copy.key()))
                .toList();

        List<NudgeCopy> pool = eligible.isEmpty()
                ? copies.stream().map(copy -> withStableKey(copy, segment, contextKey, ageBand)).toList()
                : eligible;
        return pool.get(new Random(Objects.hash(userId, contextKey, now.toLocalDate())).nextInt(pool.size()));
    }

    private NudgeCopy withStableKey(NudgeCopy copy,
                                    EngagementNudgeSegment segment,
                                    String contextKey,
                                    AgeBand ageBand) {
        return new NudgeCopy(
                segment.name() + ":" + contextKey + ":" + ageBand.name() + ":" + copy.key(),
                copy.title(),
                copy.body());
    }

    private NudgeCopy personalize(NudgeCopy copy,
                                  User user,
                                  EngagementNudgeSegment segment,
                                  AgeBand ageBand) {
        String name = firstName(user);
        String title = titleWithFirstName(copy.title(), name);
        String body = name + ", " + lowerFirstLetter(copy.body()) + " " + toneLine(ageBand, segment);
        return new NudgeCopy(copy.key(), title, body.trim());
    }

    private String titleWithFirstName(String title, String firstName) {
        if (firstName == null || firstName.isBlank()) {
            return title;
        }
        if (title == null || title.isBlank()) {
            return firstName;
        }

        String birthdayPrefix = "Happy birthday";
        if (title.toLowerCase(Locale.ROOT).startsWith(birthdayPrefix.toLowerCase(Locale.ROOT))) {
            String suffix = title.substring(birthdayPrefix.length()).trim();
            return suffix.isBlank()
                    ? birthdayPrefix + ", " + firstName
                    : birthdayPrefix + ", " + firstName + " " + suffix;
        }

        return firstName + ", " + lowerFirstLetter(title);
    }

    private String lowerFirstLetter(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int firstCodePoint = value.codePointAt(0);
        int lowerCodePoint = Character.toLowerCase(firstCodePoint);
        return new String(Character.toChars(lowerCodePoint)) + value.substring(Character.charCount(firstCodePoint));
    }

    private boolean isMidMonthWindow(LocalDate today) {
        int day = today.getDayOfMonth();
        return day >= 6 && day <= 24;
    }

    private boolean hasPushTarget(Long userId) {
        if (userId == null) {
            return false;
        }
        try {
            if (!authSessionService.getActiveFcmTokens(userId).isEmpty()) {
                return true;
            }
        } catch (Exception e) {
            logger.debug("[ENGAGEMENT] Could not read active FCM sessions for user={}: {}", userId, e.getMessage());
        }
        try {
            String fallbackToken = userRepository.findFcmTokenById(userId);
            return fallbackToken != null && !fallbackToken.isBlank();
        } catch (Exception e) {
            logger.debug("[ENGAGEMENT] Could not read fallback FCM token for user={}: {}", userId, e.getMessage());
            return false;
        }
    }

    private boolean hasEmail(User user) {
        return user != null && user.getEmail() != null && !user.getEmail().isBlank();
    }

    private boolean hasActiveBudget(User user) {
        if (user == null || user.getId() == null) {
            return false;
        }
        return budgetRepository.existsByUserIdAndStatus(user.getId(), BudgetStatus.ACTIVE)
                || budgetRepository.existsByUserIdAndStatus(user.getId(), BudgetStatus.SCHEDULED);
    }

    private boolean isBirthdayToday(User user, LocalDate today) {
        return dateOfBirth(user)
                .map(dob -> dob.getMonthValue() == today.getMonthValue()
                        && dob.getDayOfMonth() == today.getDayOfMonth())
                .orElse(false);
    }

    private AgeBand ageBandFor(User user, LocalDate today) {
        return dateOfBirth(user)
                .map(dob -> Period.between(dob, today).getYears())
                .map(AgeBand::fromAge)
                .orElse(AgeBand.UNKNOWN);
    }

    private Optional<LocalDate> dateOfBirth(User user) {
        if (user == null || user.getProfileData() == null || user.getProfileData().isEmpty()) {
            return Optional.empty();
        }

        Object dateOfBirth = user.getProfileData().get("dateOfBirth");
        if (dateOfBirth != null && !dateOfBirth.toString().isBlank()) {
            try {
                return Optional.of(LocalDate.parse(dateOfBirth.toString().trim()));
            } catch (Exception ignored) {
                // Fall through to the older dob array format.
            }
        }

        Object dob = user.getProfileData().get("dob");
        if (dob instanceof List<?> values && values.size() >= 3) {
            try {
                int year = ((Number) values.get(0)).intValue();
                int month = ((Number) values.get(1)).intValue();
                int day = ((Number) values.get(2)).intValue();
                return Optional.of(LocalDate.of(year, month, day));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private String firstName(User user) {
        if (user != null && user.getProfileData() != null) {
            String firstName = textValue(user.getProfileData().get("firstName"));
            if (firstName != null) {
                return firstName;
            }
            String name = textValue(user.getProfileData().get("name"));
            if (name != null) {
                int firstSpace = name.indexOf(" ");
                return firstSpace > 0 ? name.substring(0, firstSpace) : name;
            }
        }

        String email = user != null ? user.getEmail() : null;
        if (email == null || email.isBlank()) {
            return "there";
        }
        int at = email.indexOf("@");
        return at > 0 ? email.substring(0, at) : email;
    }

    private String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private String toneLine(AgeBand ageBand, EngagementNudgeSegment segment) {
        if (segment == EngagementNudgeSegment.NO_ACTIVE_BUDGET) {
            return switch (ageBand) {
                case AGE_0_25 -> "Small money habits now can save you from plenty mental maths later.";
                case AGE_26_34 -> "Let the plan protect work, family, transport and soft-life decisions.";
                case AGE_35_40 -> "A simple plan helps bills, home needs and family support stay calmer.";
                case AGE_41_50 -> "Structure helps responsibility feel clear without turning every spend into pressure.";
                case AGE_51_60 -> "A calm plan keeps giving, household needs and personal spending easier to manage.";
                case AGE_61_70, AGE_71_PLUS -> "Plan with peace; let Wisemonie help you steward each naira clearly.";
                case UNKNOWN -> "Give your money a direction before the month starts pulling from every side.";
            };
        }

        return switch (ageBand) {
            case AGE_0_25 -> "You are building a money habit your future self will thank you for.";
            case AGE_26_34 -> "That structure is quietly protecting your goals, bills, people and peace.";
            case AGE_35_40 -> "Your plan is helping the month carry family, work and home needs with less pressure.";
            case AGE_41_50 -> "You are handling responsibility with structure, and that deserves calm respect.";
            case AGE_51_60 -> "The plan is helping you keep responsibility steady without carrying every figure in your head.";
            case AGE_61_70, AGE_71_PLUS -> "You are stewarding your money with clarity, care and peace.";
            case UNKNOWN -> "You are doing well; the pressure that used to sit in your head now has structure.";
        };
    }

    private String midMonthFooter(EngagementNudgeSegment segment, AgeBand ageBand) {
        return segment == EngagementNudgeSegment.ACTIVE_BUDGET
                ? "If the week shifts, use the right envelope, including emergency money when it is truly needed."
                : "You do the planning; Wisemonie helps you keep the discipline when spending moments come.";
    }

    private String occasionFooter(EngagementNudgeSegment segment, SpecialOccasion occasion) {
        return segment == EngagementNudgeSegment.ACTIVE_BUDGET
                ? occasion.displayName() + " can be celebrated inside the plan, not outside your peace."
                : occasion.displayName() + " is another good reminder that money feels better when it has direction.";
    }

    private String birthdayFooter(AgeBand ageBand) {
        return switch (ageBand) {
            case AGE_51_60, AGE_61_70, AGE_71_PLUS ->
                    "May this new year bring peace, good health, steady provision and wiser stewardship.";
            default ->
                    "May this new year bring increase, discipline, softer pressure and better money peace.";
        };
    }

    private Optional<SpecialOccasion> topOccasionFor(LocalDate today) {
        Map<String, SpecialOccasion> occasions = new LinkedHashMap<>();
        for (SpecialOccasion occasion : builtInOccasions(today)) {
            occasions.put(occasion.key(), occasion);
        }
        for (EngagementSpecialOccasion occasion : occasionRepository.findByOccasionDateAndActiveTrue(today)) {
            occasions.put(occasion.getOccasionKey(), new SpecialOccasion(
                    occasion.getOccasionKey(),
                    occasion.getDisplayName(),
                    occasion.getPriority()));
        }

        return occasions.values().stream()
                .max(Comparator.comparingInt(SpecialOccasion::priority));
    }

    private List<SpecialOccasion> builtInOccasions(LocalDate today) {
        List<SpecialOccasion> occasions = new ArrayList<>();
        if (today.getMonth() == Month.JANUARY && today.getDayOfMonth() == 1) {
            occasions.add(new SpecialOccasion("NEW_YEARS_DAY", "New Year's Day", 85));
        }
        if (today.getMonth() == Month.FEBRUARY && today.getDayOfMonth() == 14) {
            occasions.add(new SpecialOccasion("VALENTINES_DAY", "Valentine's Day", 55));
        }
        if (today.getMonth() == Month.MARCH && today.getDayOfMonth() == 8) {
            occasions.add(new SpecialOccasion("INTERNATIONAL_WOMENS_DAY", "International Women's Day", 65));
        }
        if (today.getMonth() == Month.MAY && today.getDayOfMonth() == 1) {
            occasions.add(new SpecialOccasion("WORKERS_DAY", "Workers' Day", 80));
        }
        if (today.getMonth() == Month.MAY && today.getDayOfMonth() == 15) {
            occasions.add(new SpecialOccasion("INTERNATIONAL_FAMILY_DAY", "International Day of Families", 60));
        }
        if (today.getMonth() == Month.MAY && today.getDayOfMonth() == 27) {
            occasions.add(new SpecialOccasion("CHILDRENS_DAY", "Children's Day", 70));
        }
        if (today.getMonth() == Month.JUNE && today.getDayOfMonth() == 12) {
            occasions.add(new SpecialOccasion("DEMOCRACY_DAY", "Democracy Day", 80));
        }
        if (today.getMonth() == Month.AUGUST && today.getDayOfMonth() == 1) {
            occasions.add(new SpecialOccasion("WORLD_GIRLFRIENDS_DAY", "World Girlfriend's Day", 50));
        }
        if (today.getMonth() == Month.OCTOBER && today.getDayOfMonth() == 1) {
            occasions.add(new SpecialOccasion("INDEPENDENCE_DAY", "Independence Day", 90));
        }
        if (today.getMonth() == Month.OCTOBER && today.getDayOfMonth() == 31) {
            occasions.add(new SpecialOccasion("WORLD_SAVINGS_DAY", "World Savings Day", 70));
        }
        if (today.getMonth() == Month.NOVEMBER && today.getDayOfMonth() == 19) {
            occasions.add(new SpecialOccasion("INTERNATIONAL_MENS_DAY", "International Men's Day", 60));
        }
        if (today.getMonth() == Month.DECEMBER && today.getDayOfMonth() == 24) {
            occasions.add(new SpecialOccasion("CHRISTMAS_EVE", "Christmas Eve", 85));
        }
        if (today.getMonth() == Month.DECEMBER && today.getDayOfMonth() == 25) {
            occasions.add(new SpecialOccasion("CHRISTMAS_DAY", "Christmas Day", 95));
        }
        if (today.getMonth() == Month.DECEMBER && today.getDayOfMonth() == 26) {
            occasions.add(new SpecialOccasion("BOXING_DAY", "Boxing Day", 80));
        }
        if (today.getMonth() == Month.DECEMBER && today.getDayOfMonth() == 31) {
            occasions.add(new SpecialOccasion("NEW_YEARS_EVE", "New Year's Eve", 80));
        }
        if (today.equals(easterSunday(today.getYear()))) {
            occasions.add(new SpecialOccasion("EASTER_SUNDAY", "Easter Sunday", 90));
        }
        if (today.equals(easterSunday(today.getYear()).plusDays(1))) {
            occasions.add(new SpecialOccasion("EASTER_MONDAY", "Easter Monday", 85));
        }
        if (today.getMonth() == Month.MAY
                && today.equals(today.with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SUNDAY)))) {
            occasions.add(new SpecialOccasion("MOTHERS_DAY", "Mother's Day", 75));
        }
        if (today.getMonth() == Month.JUNE
                && today.equals(today.with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.SUNDAY)))) {
            occasions.add(new SpecialOccasion("FATHERS_DAY", "Father's Day", 75));
        }
        return occasions;
    }

    private LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    private List<NudgeCopy> midMonthCopies(EngagementNudgeSegment segment, MidMonthSlot slot) {
        if (segment == EngagementNudgeSegment.ACTIVE_BUDGET) {
            return activeBudgetCopies(slot);
        }
        return noActiveBudgetCopies(slot);
    }

    private List<NudgeCopy> activeBudgetCopies(MidMonthSlot slot) {
        return switch (slot) {
            case MONDAY_MORNING -> List.of(
                    copy("a-mon-01", "New week, less pressure \uD83E\uDDED", "Your envelopes are already carrying the heavy lifting. Check food, transport, giving and savings before the week starts moving."),
                    copy("a-mon-02", "Your plan is working \uD83D\uDCAA", "This is the part of the month where pressure used to build. Open Wisemonie and let the budget keep holding things calmly."),
                    copy("a-mon-03", "Start the week softly \uD83C\uDF24", "Before Monday gets loud, glance at your envelopes and decide what is safe to spend this week."),
                    copy("a-mon-04", "Budget check-in \uD83D\uDCDD", "You gave your money structure. A quick check today keeps transport, food, family and savings in their right lanes."),
                    copy("a-mon-05", "You are still in control \uD83C\uDFAF", "If the week is not going exactly as planned, adjust calmly. Your emergency envelope exists for real pressure, not guilt."),
                    copy("a-mon-06", "Money peace check \uD83D\uDE0C", "The month can feel different when every naira has somewhere to stand. Let Wisemonie carry the mental maths today."),
                    copy("a-mon-07", "Fresh week, clear lanes \uD83D\uDEE3", "Food, transport, giving, savings and bills already have a home. Spend from the right envelope and keep your head clear."),
                    copy("a-mon-08", "Small check, big calm \uD83D\uDD0D", "A two-minute budget check can protect the rest of the week from surprise spending."),
                    copy("a-mon-09", "Let the plan breathe \uD83C\uDF31", "This week, do not carry everything in your head. Let Wisemonie show what is for now, later, giving and emergencies."),
                    copy("a-mon-10", "Your money has order \uD83D\uDCE6", "Open the budget and confirm the week. The calm is in knowing what each envelope is meant to handle."),
                    copy("a-mon-11", "Monday with structure \uD83D\uDCAA", "The month may still ask for plenty, but your plan is already answering with food, transport, savings and family lanes."),
                    copy("a-mon-12", "Keep the pressure outside \uD83D\uDEE1", "Before new requests enter the week, check what the budget has already assigned and protect the plan.")
            );
            case WEDNESDAY_AFTERNOON -> List.of(
                    copy("a-wed-01", "Lunch money check \uD83C\uDF71", "Work lunch can be planned too. Check your food or daily-spend envelope before that afternoon craving makes the decision."),
                    copy("a-wed-02", "Midweek, still steady \uD83E\uDDED", "Half the week is gone. Let Wisemonie show what is safe for lunch, snacks, transport and small workday spends."),
                    copy("a-wed-03", "No mental maths today \uD83E\uDDE0", "Before lunch break, check the envelope meant for it. The plan should answer faster than your head calculator."),
                    copy("a-wed-04", "Snack without stress \uD83C\uDF6A", "Your favourite snack can fit inside the plan. Spend from the right envelope and enjoy it without side-eyeing your balance."),
                    copy("a-wed-05", "Midweek pressure check \uD83D\uDCA1", "If the week has shifted, pause before pulling money from everywhere. Wisemonie helps you adjust with clarity."),
                    copy("a-wed-06", "Food money has a lane \uD83C\uDF72", "Lunch, transport and little office spends are easier when they come from the envelopes you already chose."),
                    copy("a-wed-07", "You planned for this \uD83D\uDC4C", "This afternoon does not need guessing. Open Wisemonie, check the spend lane, and move with calm."),
                    copy("a-wed-08", "Wednesday reset \uD83D\uDD04", "If one envelope is moving too fast, adjust now before Friday starts asking for enjoyment money."),
                    copy("a-wed-09", "Afternoon clarity \uD83C\uDF24", "The day can still stay calm. Check lunch, snack and transport money before midweek spending starts blending together."),
                    copy("a-wed-10", "Spend from the right place \uD83C\uDFAF", "A workday spend is easier when it leaves the envelope made for it, not the money meant for later."),
                    copy("a-wed-11", "Your budget is guiding \uD83E\uDDED", "Midweek is where small spending hides. Let Wisemonie show the safe lane before lunch break."),
                    copy("a-wed-12", "Check before you tap \uD83D\uDCF2", "One quick look can save the rest of the week from confusion. Lunch and snacks deserve their own lane.")
            );
            case FRIDAY_EVENING -> List.of(
                    copy("a-fri-01", "Friday can still be soft \uD83C\uDF19", "Date night, hangout or quiet enjoyment can be planned. Spend from the right envelope and enjoy the evening without mental maths."),
                    copy("a-fri-02", "Enjoyment with control \uD83C\uDF89", "You deserve rest, but peace matters too. Check your enjoyment envelope before the night starts making suggestions."),
                    copy("a-fri-03", "Date plans need envelopes \uD83D\uDC9A", "Dinner, movie or a simple evening out can live inside Wisemonie, so enjoyment does not fight rent, food or savings."),
                    copy("a-fri-04", "Friday night check \uD83D\uDC40", "Before spending from vibes, check the plan. The right envelope lets you enjoy without wondering what you just affected."),
                    copy("a-fri-05", "Soft life, clear limit \uD83D\uDE0A", "Your budget is not here to stop joy. It is here to help joy happen without next-week pressure."),
                    copy("a-fri-06", "Evening money peace \uD83D\uDE0C", "If the week was heavy, enjoy what you planned for. If it was not planned, pause and protect tomorrow."),
                    copy("a-fri-07", "Spend from the plan \uD83C\uDFAF", "The balance on the dashboard is not the question tonight. The envelope for tonight is the answer."),
                    copy("a-fri-08", "Friday, but wiser \uD83E\uDDED", "Let Wisemonie keep the boundary, so you can enjoy the evening without calculating every minute."),
                    copy("a-fri-09", "Evening plans count \uD83C\uDF7D", "Food, date night or hangout money can stay inside the plan. Enjoy what was set aside and protect what was not."),
                    copy("a-fri-10", "Enjoy without guessing \uD83D\uDE0A", "Let tonight's envelope tell you the truth. That way enjoyment does not borrow from next week's peace."),
                    copy("a-fri-11", "Weekend boundary \uD83D\uDEE1", "If a plan exists for tonight, use it. If it does not, pause before one evening starts editing the month."),
                    copy("a-fri-12", "Friday with peace \uD83C\uDF19", "You can rest, laugh and spend within the plan. Wisemonie is holding the line so your head can rest too.")
            );
            case SATURDAY_EVENING -> List.of(
                    copy("a-sat-01", "Sunday giving check \u26EA", "If you plan to give tomorrow, set it aside tonight. Offering, charity and kindness can have their own calm envelope."),
                    copy("a-sat-02", "Giving can be planned \uD83E\uDD32", "Your heart can be generous and your money can still be structured. Check the envelope for Sunday, family or charity."),
                    copy("a-sat-03", "Tomorrow needs a lane \uD83D\uDCDD", "Sunday transport, offering, food and family support are easier when you decide tonight, not in a hurry tomorrow."),
                    copy("a-sat-04", "Plan kindness too \uD83D\uDC9A", "Giving to people, church, mosque or charity can sit inside your Wisemonie plan without scattering the rest of the month."),
                    copy("a-sat-05", "Weekend calm check \uD83C\uDF19", "Before Sunday begins, check what is available for worship, family, transport and food. Let the plan protect your peace."),
                    copy("a-sat-06", "No guilt spending \uD83D\uDE0C", "Family and giving matter. Planning them on Wisemonie helps you support people without feeling financially ambushed."),
                    copy("a-sat-07", "Saturday night wisdom \uD83D\uDD6F", "Set tomorrow's important money aside now, then rest knowing the month still has structure."),
                    copy("a-sat-08", "You are doing well \uD83D\uDCAA", "The financial pressure that used to chase the weekend is being handled by your plan. Keep going."),
                    copy("a-sat-09", "Giving with peace \uD83D\uDC9A", "If tomorrow includes offering, charity or helping someone, set it aside now so generosity does not become pressure."),
                    copy("a-sat-10", "Sunday prep check \uD83E\uDDED", "Tomorrow's movement, food and giving can be planned tonight. A little structure now protects the week ahead."),
                    copy("a-sat-11", "Family support, calmly \uD83E\uDD32", "If someone may need help tomorrow, check the family or giving envelope and let the boundary speak kindly."),
                    copy("a-sat-12", "Rest with a plan \uD83C\uDF19", "The weekend feels lighter when tomorrow's important money already has a place.")
            );
        };
    }

    private List<NudgeCopy> noActiveBudgetCopies(MidMonthSlot slot) {
        return switch (slot) {
            case MONDAY_MORNING -> List.of(
                    copy("n-mon-01", "This week can feel better \uD83E\uDDED", "A funded wallet with a simple budget can remove the mental maths from food, transport, family and savings decisions."),
                    copy("n-mon-02", "Give money a plan \uD83D\uDCDD", "Before the week starts pulling, fund Wisemonie and tell it when and how money should be available to you."),
                    copy("n-mon-03", "More control is possible \uD83C\uDFAF", "You do not have to keep spending from one big balance. Split the money, set the rules, and spend directly from the plan."),
                    copy("n-mon-04", "Start small, start clear \uD83C\uDF31", "Even a small budget can make transport, food, snacks and family support feel less confusing this week."),
                    copy("n-mon-05", "No more head calculator \uD83E\uDDE0", "Wisemonie can help you plan the week before spending moments arrive, so every naira has a clear job."),
                    copy("n-mon-06", "Let discipline get help \uD83D\uDD12", "You make the plan; Wisemonie helps hold it when impulse or pressure shows up."),
                    copy("n-mon-07", "Your wallet is ready \uD83D\uDCB3", "Fund it, create envelopes, and let transport, lunch, savings and giving stop fighting inside one balance."),
                    copy("n-mon-08", "Monday money reset \uD83D\uDD04", "This month does not have to run on vibes. Create a simple plan and let Wisemonie keep the structure."),
                    copy("n-mon-09", "Plan before pressure \uD83D\uDEE1", "Family, transport, food and giving can all have their place before the week starts asking urgently."),
                    copy("n-mon-10", "Control is learnable \uD83C\uDFAF", "Wisemonie helps you decide ahead, then spend from the plan when real life starts moving."),
                    copy("n-mon-11", "Give every naira a role \uD83E\uDDED", "One balance can feel big until the week touches it. Envelopes show what the money is truly for."),
                    copy("n-mon-12", "Make the week lighter \uD83C\uDF24", "A simple budget can turn worry into clear lanes for bills, family, snacks, transport and savings.")
            );
            case WEDNESDAY_AFTERNOON -> List.of(
                    copy("n-wed-01", "Lunch can be planned \uD83C\uDF71", "That work lunch or snack does not need mental maths. Create a food envelope and spend from it with confidence."),
                    copy("n-wed-02", "Midweek money clarity \uD83D\uDCA1", "If spending already feels blurry, Wisemonie can help you split what is left into transport, food, family, giving and savings."),
                    copy("n-wed-03", "Snack money without worry \uD83C\uDF6A", "Your favourite snack can have a lane. Plan it once, then enjoy it without wondering what you just spoiled."),
                    copy("n-wed-04", "Pause before lunch \uD83D\uDC40", "One big balance makes every spend feel like a guess. Wisemonie turns that balance into clear envelopes."),
                    copy("n-wed-05", "Workday spending needs calm \uD83E\uDDED", "Transport, lunch and small office costs can be planned ahead so your wallet is not surprised by Wednesday."),
                    copy("n-wed-06", "Make spending easier \uD83D\uDE0C", "When money has a plan, you do not have to calculate every time someone asks or hunger calls."),
                    copy("n-wed-07", "Split it before it scatters \uD83D\uDCE6", "If money is still sitting in one place, move it into envelopes before small spends start taking over."),
                    copy("n-wed-08", "Your week can still recover \uD83D\uDEE0", "Even if the month is not going as planned, a Wisemonie budget can bring the remaining money back under control."),
                    copy("n-wed-09", "Lunch break, less stress \uD83C\uDF7D", "Plan work lunch and transport on Wisemonie so the afternoon does not keep negotiating with your main balance."),
                    copy("n-wed-10", "Your snack deserves a lane \uD83C\uDF6B", "Enjoying small treats is easier when they are planned, not sneaking out of rent or savings money."),
                    copy("n-wed-11", "Midweek is not too late \uD83D\uDD04", "You can still split what remains and tell Wisemonie when money should be available."),
                    copy("n-wed-12", "Stop guessing at noon \uD83E\uDDE0", "Food, transport and small support requests are easier when the plan has already answered.")
            );
            case FRIDAY_EVENING -> List.of(
                    copy("n-fri-01", "Plan the evening \uD83C\uDF19", "Dates, hangouts and small enjoyment can be planned too. Wisemonie helps you enjoy without overspending by accident."),
                    copy("n-fri-02", "Soft life needs structure \uD83D\uDE0A", "Create an enjoyment envelope so Friday night does not quietly eat food, transport or savings money."),
                    copy("n-fri-03", "Date money can have peace \uD83D\uDC9A", "Whether it is dinner, movie or a small outing, plan it first and let Wisemonie show what is safe to spend."),
                    copy("n-fri-04", "Friday without fear \uD83C\uDFAF", "You can tell Wisemonie how much is for enjoyment, then spend directly from that plan without mental stress."),
                    copy("n-fri-05", "Enjoy, but know the limit \uD83E\uDDED", "The goal is not to stop enjoyment. The goal is to enjoy and still know rent, food and savings are protected."),
                    copy("n-fri-06", "Your balance needs boundaries \uD83D\uDEE1", "One balance can lie to you on Friday night. Envelopes make the true safe-to-spend amount clear."),
                    copy("n-fri-07", "Weekend money plan \uD83D\uDCDD", "Before the night gets expensive, create a quick Wisemonie plan for food, transport, giving and enjoyment."),
                    copy("n-fri-08", "Let Wisemonie hold it \uD83D\uDD12", "Impulse is easier to manage when the app is helping you keep the discipline you already wanted."),
                    copy("n-fri-09", "Date night can be planned \uD83C\uDF7D", "Plan the outing, set the amount, and spend from that envelope so enjoyment does not become Monday pressure."),
                    copy("n-fri-10", "One balance can confuse \uD83D\uDC40", "Before Friday night spending starts, split money into what is safe for enjoyment and what must stay protected."),
                    copy("n-fri-11", "Enjoyment with peace \uD83C\uDF19", "Wisemonie can help you enjoy the evening and still know what is left for transport, food and family."),
                    copy("n-fri-12", "No overspending surprise \uD83D\uDE0C", "When the plan is clear, the night can be fun without silent panic later.")
            );
            case SATURDAY_EVENING -> List.of(
                    copy("n-sat-01", "Plan tomorrow's giving \u26EA", "Offering, charity and support for people can be planned before Sunday comes. Generosity feels calmer with structure."),
                    copy("n-sat-02", "Black tax needs a lane \uD83D\uDC9A", "Family support matters, but guilt should not control the whole balance. Create a family envelope and give from it."),
                    copy("n-sat-03", "Giving without guilt \uD83E\uDD32", "Plan what you can give to people, church, mosque or charity, then let Wisemonie protect the rest of the month."),
                    copy("n-sat-04", "Sunday can be softer \uD83C\uDF24", "Transport, offering, food and family needs are easier when they are planned tonight instead of rushed tomorrow."),
                    copy("n-sat-05", "Kindness needs structure \uD83E\uDDED", "You can be generous and still be wise. Wisemonie helps you decide the amount before pressure decides for you."),
                    copy("n-sat-06", "Support people with peace \uD83D\uDE0C", "A family or charity envelope helps you give from intention, not from panic or guilt."),
                    copy("n-sat-07", "Weekend reset \uD83D\uDD04", "If the month feels scattered, fund Wisemonie and create a simple plan for what remains."),
                    copy("n-sat-08", "Tomorrow deserves a plan \uD83D\uDCDD", "Set aside Sunday money now so morning decisions do not disturb the rest of your month."),
                    copy("n-sat-09", "Family support needs peace \uD83D\uDC9A", "Black tax can feel heavy when it has no boundary. A family envelope helps you give what you planned."),
                    copy("n-sat-10", "Offering, charity, family \uD83E\uDD32", "Put tomorrow's giving in a plan tonight so kindness does not have to fight food or transport money."),
                    copy("n-sat-11", "Be generous with structure \uD83E\uDDED", "Wisemonie helps you decide what you can give, then protect the rest without guilt."),
                    copy("n-sat-12", "Sunday money, settled \u26EA", "Plan transport, offering, food and support before tomorrow begins. Calm starts the night before.")
            );
        };
    }

    private List<NudgeCopy> occasionCopies(EngagementNudgeSegment segment, SpecialOccasion occasion) {
        String name = occasion.displayName();
        if (segment == EngagementNudgeSegment.ACTIVE_BUDGET) {
            return List.of(
                    copy("o-active-01", name + " money peace \uD83C\uDF89", "As you mark " + name + ", let your Wisemonie envelopes keep celebration, giving, transport and food inside the plan."),
                    copy("o-active-02", "Celebrate with clarity \uD83E\uDDED", name + " can be joyful without scattering the month. Check the envelope meant for today and move with peace."),
                    copy("o-active-03", "Your plan can hold today \uD83D\uDC9A", "Special days can pull on money. Wisemonie helps you enjoy " + name + " while protecting what belongs to later."),
                    copy("o-active-04", "A calm " + name + " \uD83D\uDE0C", "You already gave your money structure. Let that structure guide today's spending, giving and family plans.")
            );
        }

        return List.of(
                copy("o-no-budget-01", name + " can feel calmer \uD83C\uDF89", "Special days can make money move quickly. A Wisemonie budget helps you plan celebration, giving, food and transport before spending starts."),
                copy("o-no-budget-02", "Plan the celebration \uD83E\uDDED", "Before " + name + " spending gets emotional, fund your wallet and create envelopes for what matters today."),
                copy("o-no-budget-03", "Enjoy with direction \uD83D\uDE0A", name + " does not have to disturb the rest of the month. Wisemonie helps you choose the amount and keep the boundary."),
                copy("o-no-budget-04", "Money peace for " + name + " \uD83D\uDC9A", "Give celebration, family, giving and transport their own lanes so one special day does not turn into financial pressure.")
        );
    }

    private List<NudgeCopy> birthdayCopies(EngagementNudgeSegment segment, AgeBand ageBand) {
        if (ageBand == AgeBand.AGE_51_60 || ageBand == AgeBand.AGE_61_70 || ageBand == AgeBand.AGE_71_PLUS) {
            return List.of(
                    copy("b-senior-01", "Happy birthday \uD83C\uDF82", "\"A new year is a quiet ledger: receive with gratitude, plan with wisdom, and spend with peace.\" Wisemonie celebrates you today."),
                    copy("b-senior-02", "A blessed new year \uD83C\uDF89", "May this new age bring peace, provision and wisdom. Let your money plans remain steady, dignified and clear."),
                    copy("b-senior-03", "Birthday blessings \uD83D\uDC9A", "Today we celebrate your life and your journey. May every naira in this new year serve purpose, family and peace.")
            );
        }

        if (segment == EngagementNudgeSegment.ACTIVE_BUDGET) {
            return List.of(
                    copy("b-active-01", "Happy birthday \uD83C\uDF82", "\"A new age is a clean page: earn with hope, plan with wisdom, spend with peace.\" Wisemonie is cheering you today."),
                    copy("b-active-02", "Your new year starts soft \uD83C\uDF89", "Happy birthday. May this year bring better income, calmer choices, stronger savings and less financial pressure."),
                    copy("b-active-03", "Birthday money peace \uD83D\uDC9A", "You are entering a new age with more intention. May your plans grow, your discipline strengthen and your money serve you well.")
            );
        }

        return List.of(
                copy("b-no-budget-01", "Happy birthday \uD83C\uDF82", "\"A new age is a clean page: give your money direction, and let peace follow your spending.\" Wisemonie celebrates you."),
                copy("b-no-budget-02", "Birthday reset \uD83C\uDF89", "This new year can be different. Start with a simple Wisemonie plan so money choices feel clearer and softer."),
                copy("b-no-budget-03", "Your money year can grow \uD83C\uDF31", "Happy birthday. May this age bring increase, discipline and the calm of knowing what each naira is meant to do.")
        );
    }

    private NudgeCopy copy(String key, String title, String body) {
        return new NudgeCopy(key, title, body);
    }

    public enum MidMonthSlot {
        MONDAY_MORNING(DayOfWeek.MONDAY),
        WEDNESDAY_AFTERNOON(DayOfWeek.WEDNESDAY),
        FRIDAY_EVENING(DayOfWeek.FRIDAY),
        SATURDAY_EVENING(DayOfWeek.SATURDAY);

        private final DayOfWeek dayOfWeek;

        MidMonthSlot(DayOfWeek dayOfWeek) {
            this.dayOfWeek = dayOfWeek;
        }

        public DayOfWeek dayOfWeek() {
            return dayOfWeek;
        }
    }

    private enum AgeBand {
        AGE_0_25,
        AGE_26_34,
        AGE_35_40,
        AGE_41_50,
        AGE_51_60,
        AGE_61_70,
        AGE_71_PLUS,
        UNKNOWN;

        static AgeBand fromAge(int age) {
            if (age < 0) {
                return UNKNOWN;
            }
            if (age <= 25) {
                return AGE_0_25;
            }
            if (age <= 34) {
                return AGE_26_34;
            }
            if (age <= 40) {
                return AGE_35_40;
            }
            if (age <= 50) {
                return AGE_41_50;
            }
            if (age <= 60) {
                return AGE_51_60;
            }
            if (age <= 70) {
                return AGE_61_70;
            }
            return AGE_71_PLUS;
        }
    }

    private record NudgeCopy(String key, String title, String body) {
    }

    private record SpecialOccasion(String key, String displayName, int priority) {
    }
}
