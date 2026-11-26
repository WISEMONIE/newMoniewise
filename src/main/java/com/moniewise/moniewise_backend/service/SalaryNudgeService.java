package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.SalaryNudgeNotification;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.SalaryNudgeWindow;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

@Service
public class SalaryNudgeService {

    private static final Logger logger = LoggerFactory.getLogger(SalaryNudgeService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter MONTH_NAME_FORMATTER =
            DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH);
    private static final int SALARY_WEEK_MAX_ALLOWED_SENDS_PER_WINDOW = 4;
    private static final int POST_SALARY_MAX_ALLOWED_SENDS_PER_WINDOW = 3;
    private static final long PUSH_TTL_SECONDS = 86_400L;
    private static final String BUDGET_ROUTE = "/budgets";

    private final UserRepository userRepository;
    private final SalaryNudgeNotificationRepository nudgeRepository;
    private final NotificationService notificationService;

    @Autowired
    @Lazy
    private SalaryNudgeService self;

    @Value("${moniewise.engagement.salary-nudges.enabled:true}")
    private boolean enabled;

    @Value("${moniewise.engagement.salary-nudges.batch-size:500}")
    private int batchSize;

    @Value("${moniewise.engagement.salary-nudges.min-per-window:2}")
    private int configuredMinPerWindow;

    @Value("${moniewise.engagement.salary-nudges.max-per-window:4}")
    private int configuredMaxPerWindow;

    public SalaryNudgeService(UserRepository userRepository,
                              SalaryNudgeNotificationRepository nudgeRepository,
                              NotificationService notificationService) {
        this.userRepository = userRepository;
        this.nudgeRepository = nudgeRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "${moniewise.engagement.salary-nudges.cron:0 15 9 * * ?}", zone = "Africa/Lagos")
    public void processSalaryNudges() {
        if (!enabled) {
            return;
        }

        LocalDate today = LocalDate.now(LAGOS_ZONE);
        Optional<NudgeWindowPlan> planOpt = currentWindow(today);
        if (planOpt.isEmpty()) {
            return;
        }

        NudgeWindowPlan plan = planOpt.get();
        int pageSize = Math.max(1, batchSize);
        int dueToday = 0;
        int sent = 0;
        int candidates = 0;
        Long afterUserId = 0L;

        while (true) {
            List<Long> userIds = userRepository.findPushEligibleWalletUserIdsAfter(afterUserId, pageSize);
            if (userIds.isEmpty()) {
                break;
            }

            candidates += userIds.size();
            for (Long userId : userIds) {
                afterUserId = userId;
                if (!hasScheduledSendDueByToday(userId, plan)) {
                    continue;
                }

                dueToday++;
                try {
                    if (self.sendNudgeIfDue(userId, plan, LocalDateTime.now(LAGOS_ZONE))) {
                        sent++;
                    }
                } catch (DataIntegrityViolationException e) {
                    logger.info("[SALARY-NUDGE] Skipped duplicate {} nudge for user={} period={}",
                            plan.window(), userId, plan.period());
                } catch (Exception e) {
                    logger.error("[SALARY-NUDGE] Failed to process {} nudge for user={} period={}",
                            plan.window(), userId, plan.period(), e);
                }
            }

            if (userIds.size() < pageSize) {
                break;
            }
        }

        if (sent > 0) {
            logger.info("[SALARY-NUDGE] Sent {} {} push nudge(s), dueOrCatchupToday={}, candidates={}",
                    sent, plan.window(), dueToday, candidates);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendNudgeIfDue(Long userId, NudgeWindowPlan plan, LocalDateTime sentAt) {
        int targetSendCount = targetSendCount(userId, plan);
        if (targetSendCount <= 0) {
            return false;
        }

        int alreadySent = nudgeRepository.countByUserIdAndNudgeWindowAndPeriodYearAndPeriodMonth(
                userId,
                plan.window(),
                plan.period().getYear(),
                plan.period().getMonthValue());
        if (alreadySent >= targetSendCount) {
            return false;
        }

        if (nudgeRepository.existsByUserIdAndNudgeWindowAndPeriodYearAndPeriodMonthAndSentDate(
                userId,
                plan.window(),
                plan.period().getYear(),
                plan.period().getMonthValue(),
                plan.today())) {
            return false;
        }

        if (!shouldSendToday(userId, plan, alreadySent)) {
            return false;
        }

        int variationIndex = selectVariationIndex(userId, plan);
        SalaryNudgeCopy copy = copyFor(plan.window(), variationIndex, plan.period());

        SalaryNudgeNotification nudge = new SalaryNudgeNotification();
        nudge.setUserId(userId);
        nudge.setNudgeWindow(plan.window());
        nudge.setPeriodYear(plan.period().getYear());
        nudge.setPeriodMonth(plan.period().getMonthValue());
        nudge.setVariationIndex(variationIndex);
        nudge.setSentDate(plan.today());
        nudge.setSentAt(sentAt);
        nudgeRepository.save(nudge);

        notificationService.enqueuePushOnlyNotification(
                userId,
                plan.notificationType(),
                copy.title(),
                copy.body(),
                BUDGET_ROUTE,
                PUSH_TTL_SECONDS);
        return true;
    }

    private Optional<NudgeWindowPlan> currentWindow(LocalDate today) {
        int day = today.getDayOfMonth();
        YearMonth period = YearMonth.from(today);

        if (day >= 1 && day <= 5) {
            return Optional.of(new NudgeWindowPlan(
                    SalaryNudgeWindow.POST_SALARY,
                    NotificationType.POST_SALARY_NUDGE,
                    period,
                    today));
        }

        int salaryWindowEnd = salaryWindowEndDay(period);
        if (day >= 25 && day <= salaryWindowEnd) {
            return Optional.of(new NudgeWindowPlan(
                    SalaryNudgeWindow.SALARY_WEEK,
                    NotificationType.SALARY_WEEK_NUDGE,
                    period,
                    today));
        }

        return Optional.empty();
    }

    private boolean hasScheduledSendDueByToday(Long userId, NudgeWindowPlan plan) {
        int todayDay = plan.today().getDayOfMonth();
        for (Integer day : selectedSendDays(userId, plan)) {
            if (day <= todayDay) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSendToday(Long userId, NudgeWindowPlan plan, int alreadySent) {
        List<Integer> selectedDays = selectedSendDays(userId, plan);
        int todayDay = plan.today().getDayOfMonth();
        if (selectedDays.contains(todayDay)) {
            return true;
        }

        int scheduledSendsDueByToday = 0;
        for (Integer day : selectedDays) {
            if (day <= todayDay) {
                scheduledSendsDueByToday++;
            }
        }

        if (alreadySent < scheduledSendsDueByToday) {
            return true;
        }

        int minimumSendCount = minimumSendCount(plan);
        if (alreadySent >= minimumSendCount) {
            return false;
        }

        int remainingDaysIncludingToday = remainingWindowDaysIncludingToday(plan);
        return minimumSendCount - alreadySent >= remainingDaysIncludingToday;
    }

    private List<Integer> selectedSendDays(Long userId, NudgeWindowPlan plan) {
        List<Integer> days = windowDays(plan.window(), plan.period());
        int targetSendCount = targetSendCount(userId, plan);
        if (targetSendCount <= 0 || days.isEmpty()) {
            return List.of();
        }

        Collections.shuffle(days, new Random(stableSeed(userId, plan, "days")));
        List<Integer> selected = new ArrayList<>(days.subList(0, Math.min(targetSendCount, days.size())));
        Collections.sort(selected);
        return selected;
    }

    private int minimumSendCount(NudgeWindowPlan plan) {
        int windowSize = windowDays(plan.window(), plan.period()).size();
        if (windowSize <= 0) {
            return 0;
        }
        int min = Math.max(1, Math.min(configuredMinPerWindow, maxAllowedSends(plan)));
        return Math.min(min, windowSize);
    }

    private int targetSendCount(Long userId, NudgeWindowPlan plan) {
        int windowSize = windowDays(plan.window(), plan.period()).size();
        if (windowSize <= 0) {
            return 0;
        }

        int min = minimumSendCount(plan);
        int max = Math.max(min, Math.min(configuredMaxPerWindow, maxAllowedSends(plan)));
        max = Math.min(max, windowSize);
        min = Math.min(min, max);

        if (max <= min) {
            return min;
        }
        return min + new Random(stableSeed(userId, plan, "count")).nextInt(max - min + 1);
    }

    private int maxAllowedSends(NudgeWindowPlan plan) {
        return plan.window() == SalaryNudgeWindow.SALARY_WEEK
                ? SALARY_WEEK_MAX_ALLOWED_SENDS_PER_WINDOW
                : POST_SALARY_MAX_ALLOWED_SENDS_PER_WINDOW;
    }

    private int remainingWindowDaysIncludingToday(NudgeWindowPlan plan) {
        int todayDay = plan.today().getDayOfMonth();
        int remaining = 0;
        for (Integer day : windowDays(plan.window(), plan.period())) {
            if (day >= todayDay) {
                remaining++;
            }
        }
        return remaining;
    }

    private List<Integer> windowDays(SalaryNudgeWindow window, YearMonth period) {
        int startDay;
        int endDay;
        if (window == SalaryNudgeWindow.SALARY_WEEK) {
            startDay = 25;
            endDay = salaryWindowEndDay(period);
        } else {
            startDay = 1;
            endDay = 5;
        }

        if (endDay < startDay) {
            return List.of();
        }

        List<Integer> days = new ArrayList<>();
        for (int day = startDay; day <= endDay; day++) {
            days.add(day);
        }
        return days;
    }

    private int salaryWindowEndDay(YearMonth period) {
        if (period.getMonthValue() == 2) {
            return period.lengthOfMonth();
        }
        return period.lengthOfMonth();
    }

    private int selectVariationIndex(Long userId, NudgeWindowPlan plan) {
        Set<Integer> sentThisPeriod = new HashSet<>(nudgeRepository.findVariationIndexesForPeriod(
                userId,
                plan.window(),
                plan.period().getYear(),
                plan.period().getMonthValue()));

        YearMonth previousPeriod = plan.period().minusMonths(1);
        Set<Integer> sentPreviousPeriod = new HashSet<>(nudgeRepository.findVariationIndexesForPeriod(
                userId,
                plan.window(),
                previousPeriod.getYear(),
                previousPeriod.getMonthValue()));

        int copyVariationCount = copyVariationCount(plan.window());
        List<Integer> primaryPool = variationPool(sentThisPeriod, sentPreviousPeriod, copyVariationCount);
        List<Integer> candidates = primaryPool.isEmpty()
                ? variationPool(sentThisPeriod, Set.of(), copyVariationCount)
                : primaryPool;

        Collections.shuffle(candidates, new Random(stableSeed(
                userId,
                plan,
                "copy-" + sentThisPeriod.size())));
        return candidates.get(0);
    }

    private List<Integer> variationPool(Set<Integer> sentThisPeriod,
                                        Set<Integer> sentPreviousPeriod,
                                        int copyVariationCount) {
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < copyVariationCount; i++) {
            if (!sentThisPeriod.contains(i) && !sentPreviousPeriod.contains(i)) {
                pool.add(i);
            }
        }
        return pool;
    }

    private SalaryNudgeCopy copyFor(SalaryNudgeWindow window, int variationIndex, YearMonth period) {
        List<SalaryNudgeCopy> copies = window == SalaryNudgeWindow.SALARY_WEEK
                ? salaryWeekCopies()
                : postSalaryCopies(monthName(period));
        return copies.get(Math.floorMod(variationIndex, copies.size()));
    }

    private int copyVariationCount(SalaryNudgeWindow window) {
        return window == SalaryNudgeWindow.SALARY_WEEK
                ? salaryWeekCopies().size()
                : postSalaryCopies("").size();
    }

    private List<SalaryNudgeCopy> salaryWeekCopies() {
        return List.of(
                new SalaryNudgeCopy(
                        "Happy salary week! \uD83D\uDCB8",
                        "That end-of-month pressure is real. Before salary lands, open Wisemonie and give the next money a calmer plan."),
                new SalaryNudgeCopy(
                        "Fresh breath soon \uD83C\uDF24\uFE0F",
                        "You have carried this month well. Salary is close; set your budget early so next month does not start with pressure."),
                new SalaryNudgeCopy(
                        "Plan before salary enters \uD83D\uDCDD",
                        "When salary enters, small unplanned spends can move fast. Give rent, food, transport, savings and enjoyment money their envelopes first."),
                new SalaryNudgeCopy(
                        "You are almost there \uD83D\uDCAA",
                        "This month may have stretched you, but payday is near. Let Wisemonie help you turn relief into a simple spending plan."),
                new SalaryNudgeCopy(
                        "Payday is approaching \u23F3",
                        "Remember the promise to do better when money comes? Set the plan before the alert, and let Wisemonie hold the structure."),
                new SalaryNudgeCopy(
                        "One more calm check \uD83D\uDC40",
                        "Salary is very close now. Before the month resets, decide what should go to bills, food, transport, savings and soft life.")
        );
    }

    private List<SalaryNudgeCopy> postSalaryCopies(String monthName) {
        return List.of(
                new SalaryNudgeCopy(
                        "Salary has landed? \uD83D\uDCB0",
                        "If salary is in, pause before life starts pulling. Plan family, transport, food, giving, savings and emergency money in Wisemonie \uD83D\uDE0A."),
                new SalaryNudgeCopy(
                        "Give it direction \uD83E\uDDED",
                        "New month, fresh cash flow. Split your money into envelopes now so spending feels clear and the mental maths can rest."),
                new SalaryNudgeCopy(
                        monthName + " can feel softer \uD83C\uDF31",
                        "Before the balance starts reducing, give every naira a job: bills, food, transport, savings, enjoyment and emergency buffer \uD83D\uDE42."),
                new SalaryNudgeCopy(
                        "Plan while it is fresh \uD83D\uDCDD",
                        "Salary season is the best time to protect peace. Create your Wisemonie budget now and spend from the right envelope."),
                new SalaryNudgeCopy(
                        "Less pressure this month \uD83D\uDE0C",
                        "You worked for this money. Let it serve " + monthName + " calmly: budget it, save a piece, and keep emergency money aside.")
        );
    }

    private String monthName(YearMonth period) {
        return period.atDay(1).format(MONTH_NAME_FORMATTER);
    }

    private long stableSeed(Long userId, NudgeWindowPlan plan, String salt) {
        return Objects.hash(
                userId,
                plan.window().name(),
                plan.period().getYear(),
                plan.period().getMonthValue(),
                salt);
    }

    public record NudgeWindowPlan(
            SalaryNudgeWindow window,
            NotificationType notificationType,
            YearMonth period,
            LocalDate today) {
    }

    private record SalaryNudgeCopy(String title, String body) {
    }
}
