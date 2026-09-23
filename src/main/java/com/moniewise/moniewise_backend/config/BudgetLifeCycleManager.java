package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.repository.*;
import com.moniewise.moniewise_backend.service.ActivationJourneyNudgeService;
import com.moniewise.moniewise_backend.service.BadgeAwardService;
import com.moniewise.moniewise_backend.service.EnvelopeAutoTransferService;
import com.moniewise.moniewise_backend.service.EnvelopeService;
import com.moniewise.moniewise_backend.service.FeeReserveService;
import com.moniewise.moniewise_backend.service.MonnieCacheInvalidationService;
import com.moniewise.moniewise_backend.service.NotificationService;
import com.moniewise.moniewise_backend.service.SavingsService;
import com.moniewise.moniewise_backend.service.WalletService;
import com.moniewise.moniewise_backend.utils.ExceptionClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.moniewise.moniewise_backend.enums.TransactionStatus.COMPLETED;
import static com.moniewise.moniewise_backend.enums.TransactionType.BUDGET_COMPLETION_REFUND;

@Service
public class BudgetLifeCycleManager {

    private static final Logger logger = LoggerFactory.getLogger(BudgetLifeCycleManager.class);
    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;
    @Value("${moniewise.scheduler.scheduled-budget-activation.batch-size:50}")
    private int scheduledBudgetActivationBatchSize;
    @Value("${moniewise.scheduler.scheduled-budget-activation.max-loops:10}")
    private int scheduledBudgetActivationMaxLoops;
    @Value("${moniewise.scheduler.budget-expiry.batch-size:50}")
    private int budgetExpiryBatchSize;
    @Value("${moniewise.scheduler.budget-expiry.max-loops:10}")
    private int budgetExpiryMaxLoops;
    @Value("${moniewise.scheduler.critical-tasks.batch-size:50}")
    private int criticalTasksBatchSize;
    @Value("${moniewise.scheduler.critical-tasks.max-loops:10}")
    private int criticalTasksMaxLoops;
    private final BudgetRepository budgetRepository;
    private final EnvelopeRepository envelopeRepository;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final WalletService walletService;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;
    private final NotificationRepository notificationRepository;
    private final PendingDisbursementRepository pendingDisbursementRepository;
    private final EnvelopeService envelopeService;
    private final MonnieCacheInvalidationService monnieCacheInvalidationService;
    private final EnvelopeAutoTransferService envelopeAutoTransferService;
    private final SavingsGoalRepository savingsGoalRepository;
    private final SavingsService savingsService;
    private final BadgeAwardService badgeAwardService;
    private final ActivationJourneyNudgeService activationJourneyNudgeService;
    private final FeeReserveService feeReserveService;

    private final OutboxEventRepository outboxEventRepository;

    private final ApplicationEventPublisher eventPublisher;
    private final AtomicBoolean scheduledTasksRunning = new AtomicBoolean(false);
    @PersistenceContext
    private EntityManager entityManager;
    private final long GRACE_PERIOD_MINUTES = 10; // can be dynamic per envelope

    public BudgetLifeCycleManager(
            BudgetRepository budgetRepository,
            EnvelopeRepository envelopeRepository,
            ScheduledTaskRepository scheduledTaskRepository,
            TransactionLogRepository transactionLogRepository,
            WalletService walletService,
            NotificationService notificationService,
            TransactionTemplate transactionTemplate,
            NotificationRepository notificationRepository,
            PendingDisbursementRepository pendingDisbursementRepository,
            @Lazy EnvelopeService envelopeService,
            MonnieCacheInvalidationService monnieCacheInvalidationService,
            OutboxEventRepository outboxEventRepository, ApplicationEventPublisher eventPublisher,
            @Lazy EnvelopeAutoTransferService envelopeAutoTransferService,
            SavingsGoalRepository savingsGoalRepository,
            SavingsService savingsService,
            BadgeAwardService badgeAwardService,
            ActivationJourneyNudgeService activationJourneyNudgeService,
            @Lazy FeeReserveService feeReserveService) {
        this.budgetRepository = budgetRepository;
        this.envelopeRepository = envelopeRepository;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.walletService = walletService;
        this.notificationService = notificationService;
        this.transactionTemplate = transactionTemplate;
        this.notificationRepository = notificationRepository;
        this.pendingDisbursementRepository = pendingDisbursementRepository;
        this.envelopeService = envelopeService;
        this.monnieCacheInvalidationService = monnieCacheInvalidationService;
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.envelopeAutoTransferService = envelopeAutoTransferService;
        this.savingsGoalRepository = savingsGoalRepository;
        this.savingsService = savingsService;
        this.badgeAwardService = badgeAwardService;
        this.activationJourneyNudgeService = activationJourneyNudgeService;
        this.feeReserveService = feeReserveService;
    }

    @PostConstruct
    public void init() {
        logger.info("Revenue Wallet User ID: {}", revenueWalletUserId);
    }

    private LocalDateTime fetchCurrentDateTimeFromDatabase() {
        try {
            return budgetRepository.getCurrentLagosTime();
        } catch (Exception e) {
            if (ExceptionClassifier.isDatabasePoolExhausted(e)) {
                logger.warn("DB time unavailable because the connection pool is busy; using system Lagos time: {}",
                        ExceptionClassifier.rootCauseMessage(e));
            } else {
                logger.error("Failed to fetch DB time, using system: {}", e.getMessage());
            }
            return LocalDateTime.now(ZoneId.of("Africa/Lagos"));
        }
    }

    private int boundedBatchSize(int configured) {
        return Math.max(1, Math.min(configured, 200));
    }

    private int boundedLoopLimit(int configured) {
        return Math.max(1, Math.min(configured, 50));
    }

    public void scheduleDynamicTasks(Envelope envelope) {
        Budget budget = envelope.getBudget();
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        if (budget == null || budget.getStatus() != BudgetStatus.ACTIVE) {
            logger.info("Not scheduling envelope {} because budget is not active", envelope.getId());
            return;
        }

        if (isBudgetPastEndDate(budget, now)) {
            logger.info(
                    "Not scheduling envelope {} because budget {} ended on {}",
                    envelope.getId(),
                    budget.getId(),
                    budget.getEndDate()
            );

            envelope.setNextDisbursementAt(null);
            envelopeRepository.save(envelope);
            return;
        }

        scheduledTaskRepository.cancelPendingByEnvelopeIdAndTaskType(envelope.getId(), "DISBURSEMENT");

        LocalDateTime nextTriggerTime = calculateNextDisbursementTime(envelope);

        if (nextTriggerTime != null) {
            scheduleDisbursementGroup(envelope, nextTriggerTime, now);

            envelope.setNextDisbursementAt(nextTriggerTime);
            envelopeRepository.save(envelope);
        }
    }

    // Helper to schedule the trio: Disbursement + Warnings
    private void scheduleDisbursementGroup(Envelope envelope, LocalDateTime triggerTime, LocalDateTime now) {
        List<ScheduledTask> tasks = new ArrayList<>();

        // 1. The Main Event
        ScheduledTask mainTask = new ScheduledTask();
        mainTask.setEnvelopeId(envelope.getId());
        mainTask.setTaskType("DISBURSEMENT");
        mainTask.setTriggerTime(triggerTime);
        mainTask.setCreatedAt(now);
        mainTask.setStatus("PENDING");
        mainTask.setRetryCount(0);
        tasks.add(mainTask);

//        // 2. The Warnings (Only if time permits)
//        if (triggerTime.minusMinutes(15).isAfter(now)) {
//            ScheduledTask warn15 = new ScheduledTask();
//            warn15.setEnvelopeId(envelope.getId());
//            warn15.setTaskType("PRE_DISBURSEMENT_NOTIFICATION_15MIN");
//            warn15.setTriggerTime(triggerTime.minusMinutes(15));
//            warn15.setCreatedAt(now);
//            tasks.add(warn15);
//        }
//
//        if (triggerTime.minusMinutes(5).isAfter(now)) {
//            ScheduledTask warn5 = new ScheduledTask();
//            warn5.setEnvelopeId(envelope.getId());
//            warn5.setTaskType("PRE_DISBURSEMENT_NOTIFICATION_5MIN");
//            warn5.setTriggerTime(triggerTime.minusMinutes(5));
//            warn5.setCreatedAt(now);
//            tasks.add(warn5);
//        }

        scheduledTaskRepository.save(mainTask);
        logger.info("Scheduled next disbursement for envelope {} at {}", envelope.getId(), triggerTime);
    }

    @Scheduled(cron = "${moniewise.scheduler.scheduled-budget-activation.cron:0 */10 * * * ?}", zone = "Africa/Lagos")
    public void activateDueScheduledBudgets() {
        LocalDate today = fetchCurrentDateTimeFromDatabase().toLocalDate();

        int batchSize = boundedBatchSize(scheduledBudgetActivationBatchSize);
        int currentLoop = 0;
        int maxLoops = boundedLoopLimit(scheduledBudgetActivationMaxLoops);
        boolean hasMore = true;

        while (hasMore && currentLoop < maxLoops) {
            currentLoop++;

            Pageable pageable = PageRequest.of(0, batchSize);
            Page<Budget> page = budgetRepository.findByStatusAndStartDateLessThanEqual(
                    BudgetStatus.SCHEDULED,
                    today,
                    pageable
            );

            if (page.isEmpty()) {
                hasMore = false;
                continue;
            }

            for (Budget budget : page.getContent()) {
                try {
                    transactionTemplate.execute(status -> {
                        activateScheduledBudget(budget.getId());
                        return null;
                    });
                } catch (Exception e) {
                    logger.error("Failed to activate scheduled budget {}", budget.getId(), e);
                }
            }

            hasMore = page.getNumberOfElements() == batchSize;
        }
    }

    @Transactional
    public Budget activateScheduledBudget(Long budgetId) {
        Budget budget = budgetRepository.findByIdForUpdate(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));

        if (budget.getStatus() == BudgetStatus.ACTIVE
                || budget.getStatus() == BudgetStatus.COMPLETED
                || budget.getStatus() == BudgetStatus.CANCELLED) {
            return budget;
        }
        if (budget.getStatus() != BudgetStatus.SCHEDULED) {
            throw new IllegalStateException("Budget " + budgetId + " is not scheduled");
        }

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        LocalDate today = now.toLocalDate();
        if (budget.getStartDate() != null && budget.getStartDate().isAfter(today)) {
            throw new IllegalStateException("Budget " + budgetId + " starts on " + budget.getStartDate());
        }

        budget.setStatus(BudgetStatus.ACTIVE);

        if (isBudgetPastEndDate(budget, now)) {
            List<Budget> budgetsToUpdate = new ArrayList<>();
            List<Envelope> envelopesToUpdate = new ArrayList<>();
            List<TransactionLog> logsToSave = new ArrayList<>();
            processBudgetExpiry(budget, budgetsToUpdate, envelopesToUpdate, logsToSave);
            budgetRepository.saveAll(budgetsToUpdate);
            envelopeRepository.saveAll(envelopesToUpdate);
            transactionLogRepository.saveAll(logsToSave);
            monnieCacheInvalidationService.evictUserAfterCommit(budget.getUser().getId());
            return budget;
        }

        budgetRepository.save(budget);

        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budget.getId());
        List<Envelope> envelopesToSave = new ArrayList<>();
        List<ScheduledTask> tasksToSave = new ArrayList<>();
        List<OutboxEvent> outboxEventsToSave = new ArrayList<>();

        for (Envelope envelope : envelopes) {
            scheduledTaskRepository.deleteByEnvelopeId(envelope.getId());
            activateScheduledEnvelope(budget, envelope, now, tasksToSave, outboxEventsToSave);
            envelopesToSave.add(envelope);
        }

        BigDecimal remainingInBudget = envelopesToSave.stream()
                .map(envelope -> safeAmount(envelope.getTotalRemainingAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        budget.setRemainingAmount(remainingInBudget);
        budgetRepository.save(budget);

        if (!envelopesToSave.isEmpty()) {
            envelopeRepository.saveAll(envelopesToSave);
        }
        if (!tasksToSave.isEmpty()) {
            scheduledTaskRepository.saveAll(tasksToSave);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("budgetName", budget.getName() != null ? budget.getName() : "your");
        payload.put("startDate", budget.getStartDate() != null ? budget.getStartDate().toString() : today.toString());
        outboxEventsToSave.add(buildOutboxEvent(
                NotificationType.BUDGET_ACTIVATED,
                budget.getUser().getId(),
                budget.getId(),
                null,
                payload
        ));

        if (!outboxEventsToSave.isEmpty()) {
            outboxEventRepository.saveAll(outboxEventsToSave);
        }

        monnieCacheInvalidationService.evictUserAfterCommit(budget.getUser().getId());
        logger.info("Activated scheduled budget {} for user {}", budget.getId(), budget.getUser().getId());
        return budget;
    }

    private void activateScheduledEnvelope(
            Budget budget,
            Envelope envelope,
            LocalDateTime now,
            List<ScheduledTask> tasksToSave,
            List<OutboxEvent> outboxEventsToSave
    ) {
        Map<String, Object> conditions = mutableConditions(envelope);
        String type = conditions.getOrDefault("type", "").toString().toLowerCase();

        if ("savings_sweep".equals(type)) {
            activateScheduledSavingsSweep(budget, envelope, conditions, now, outboxEventsToSave);
            return;
        }

        if ("emergency".equals(type)) {
            envelope.setRemainingAmount(availableVaultBalance(envelope));
            envelope.setNextDisbursementAt(null);
            envelope.setHasMatured(false);
            return;
        }

        if ("safe_lock".equals(type) || "strict_lock".equals(type)) {
            LocalDateTime nextDisbursement = calculateNextDisbursementTime(envelope);
            if (nextDisbursement == null) {
                envelope.setRemainingAmount(availableVaultBalance(envelope));
                envelope.setHasMatured(true);
            } else {
                envelope.setRemainingAmount(BigDecimal.ZERO);
                tasksToSave.add(new ScheduledTask(envelope.getId(), "DISBURSEMENT", nextDisbursement));
            }
            envelope.setNextDisbursementAt(nextDisbursement);
            return;
        }

        if (isTimeReleasedEnvelope(type)) {
            try {
                envelopeService.triggerRecalculation(envelope);
            } catch (Exception e) {
                logger.error("Failed to recalculate scheduled envelope {} on activation", envelope.getId(), e);
            }

            LocalDateTime nextDisbursement = calculateNextDisbursementTime(envelope);
            boolean disbursesLaterToday = nextDisbursement != null
                    && nextDisbursement.toLocalDate().isEqual(now.toLocalDate());

            if (disbursesLaterToday) {
                envelope.setRemainingAmount(BigDecimal.ZERO);
            } else {
                BigDecimal startingPocket = getPeriodLimit(envelope).min(availableVaultBalance(envelope));
                envelope.setRemainingAmount(startingPocket);
                if (startingPocket.compareTo(BigDecimal.ZERO) > 0) {
                    envelope.setLastDisbursedAt(now);
                    nextDisbursement = calculateNextDisbursementTime(envelope);
                }
            }

            envelope.setNextDisbursementAt(nextDisbursement);
            if (nextDisbursement != null) {
                tasksToSave.add(new ScheduledTask(envelope.getId(), "DISBURSEMENT", nextDisbursement));
            }
            return;
        }

        BigDecimal startingPocket = getPeriodLimit(envelope).min(availableVaultBalance(envelope));
        envelope.setRemainingAmount(startingPocket);
        envelope.setNextDisbursementAt(null);
    }

    private void activateScheduledSavingsSweep(
            Budget budget,
            Envelope envelope,
            Map<String, Object> conditions,
            LocalDateTime now,
            List<OutboxEvent> outboxEventsToSave
    ) {
        BigDecimal amount = availableVaultBalance(envelope);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            conditions.put("savingsSweepStatus", "COMPLETED");
            envelope.setConditions(conditions);
            envelope.setRemainingAmount(BigDecimal.ZERO);
            envelope.setNextDisbursementAt(null);
            envelope.setHasMatured(true);
            return;
        }

        Long targetSavingsGoalId = parseLong(conditions.get("targetSavingsGoalId"));
        String skipReason = savingsSweepSkipReason(targetSavingsGoalId, budget.getUser().getId(), now.toLocalDate());
        if (skipReason != null) {
            markScheduledSavingsSweepSkipped(budget, envelope, conditions, amount, skipReason, outboxEventsToSave);
            return;
        }

        try {
            savingsService.sweepEnvelopeToSavings(
                    budget.getUser().getId(),
                    targetSavingsGoalId,
                    amount,
                    envelope.getName(),
                    budget.getId(),
                    envelope.getId()
            );

            conditions.put("savingsSweepStatus", "COMPLETED");
            conditions.remove("savingsSweepFailureReason");
            envelope.setConditions(conditions);
            envelope.setRemainingAmount(BigDecimal.ZERO);
            envelope.setTotalRemainingAmount(BigDecimal.ZERO);
            envelope.setHeldAmount(BigDecimal.ZERO);
            envelope.setNextDisbursementAt(null);
            envelope.setHasMatured(true);
        } catch (Exception e) {
            logger.error("Failed to sweep scheduled envelope {} to savings goal {}", envelope.getId(), targetSavingsGoalId, e);
            markScheduledSavingsSweepSkipped(
                    budget,
                    envelope,
                    conditions,
                    amount,
                    "we could not move it to the selected savings pot",
                    outboxEventsToSave
            );
        }
    }

    private void markScheduledSavingsSweepSkipped(
            Budget budget,
            Envelope envelope,
            Map<String, Object> conditions,
            BigDecimal amount,
            String reason,
            List<OutboxEvent> outboxEventsToSave
    ) {
        conditions.put("savingsSweepStatus", "SKIPPED");
        conditions.put("savingsSweepFailureReason", reason);
        conditions.put("limit", amount);
        envelope.setConditions(conditions);
        envelope.setRemainingAmount(amount);
        envelope.setNextDisbursementAt(null);
        envelope.setHasMatured(false);

        Map<String, Object> payload = new HashMap<>();
        String envelopeName = envelope.getName() != null ? envelope.getName() : "Savings";
        String budgetName = budget.getName() != null ? budget.getName() : "your";
        payload.put("budgetName", budgetName);
        payload.put("envelopeName", envelopeName);
        payload.put("amount", String.format("%,.2f", amount));
        payload.put("reason", reason);
        payload.put("__message", "Your '" + budgetName + "' budget is active, but '" + envelopeName
                + "' was not moved to savings because " + reason
                + ". The money is still protected in that envelope.");

        outboxEventsToSave.add(buildOutboxEvent(
                NotificationType.BUDGET_UPDATED,
                budget.getUser().getId(),
                budget.getId(),
                envelope.getId(),
                payload
        ));
    }

    private String savingsSweepSkipReason(Long savingsGoalId, Long userId, LocalDate today) {
        if (savingsGoalId == null) {
            return "no savings pot was selected";
        }

        Optional<SavingsGoal> goalOpt = savingsGoalRepository.findByIdForUpdate(savingsGoalId);
        if (goalOpt.isEmpty()) {
            return "the selected savings pot could not be found";
        }

        SavingsGoal goal = goalOpt.get();
        if (goal.getUser() == null || goal.getUser().getId() == null || !goal.getUser().getId().equals(userId)) {
            return "the selected savings pot no longer belongs to this account";
        }
        if (goal.getStatus() != SavingsStatus.ACTIVE) {
            return "the selected savings pot is no longer active";
        }
        if (goal.getMaturityDate() == null || !today.isBefore(goal.getMaturityDate())) {
            return "the selected savings pot has already matured";
        }

        return null;
    }

    private Map<String, Object> mutableConditions(Envelope envelope) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (envelope.getConditions() != null) {
            copy.putAll(envelope.getConditions());
        }
        envelope.setConditions(copy);
        return copy;
    }

    private boolean isTimeReleasedEnvelope(String type) {
        return "daily".equals(type) || "weekly".equals(type) || "dynamic".equals(type);
    }

    private BigDecimal getPeriodLimit(Envelope envelope) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null || !conditions.containsKey("limit")) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(conditions.get("limit").toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal availableVaultBalance(Envelope envelope) {
        return safeAmount(envelope.getTotalRemainingAmount()).subtract(safeAmount(envelope.getHeldAmount())).max(BigDecimal.ZERO);
    }

    private BigDecimal safeAmount(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Scheduled(cron = "0 */15 * * * ?", zone = "Africa/Lagos")
    public void processBudgets() {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        LocalDate today = now.toLocalDate();

        // We only fetch it for expiration if 'yesterday' was the end date.
        LocalDate yesterday = today.minusDays(1);

        int batchSize = boundedBatchSize(budgetExpiryBatchSize);
        boolean hasMore = true;
        int maxLoops = boundedLoopLimit(budgetExpiryMaxLoops);
        int currentLoop = 0;

        while (hasMore && currentLoop < maxLoops) {
            currentLoop++;

            // 1. Fetch the page OUTSIDE the transaction
            Pageable pageable = PageRequest.of(0, batchSize);
            Page<Budget> page = budgetRepository.findByStatusAndEndDateLessThanEqual(BudgetStatus.ACTIVE, yesterday, pageable);
//            Page<Budget> page = budgetRepository.findByStatusAndEndDateLessThanEqual(BudgetStatus.ACTIVE, today, pageable);


            if (page.isEmpty()) {
                hasMore = false;
                continue;
            }

            for (Budget budget : page.getContent()) {
                Long budgetId = budget.getId();
                try {
                    // 2. Process EACH budget in its own isolated transaction
                    transactionTemplate.execute(status -> {
                        Budget budgetToExpire = budgetRepository.findByIdForUpdate(budgetId).orElse(null);
                        if (budgetToExpire == null
                                || budgetToExpire.getStatus() != BudgetStatus.ACTIVE
                                || !isBudgetPastEndDate(budgetToExpire, now)) {
                            return null;
                        }

                        List<Budget> bUpdate = new ArrayList<>();
                        List<Envelope> eUpdate = new ArrayList<>();
                        List<TransactionLog> lSave = new ArrayList<>();

                        processBudgetExpiry(budgetToExpire, bUpdate, eUpdate, lSave);

                        budgetRepository.saveAll(bUpdate);
                        envelopeRepository.saveAll(eUpdate);
                        transactionLogRepository.saveAll(lSave);
                        return null;
                    });
                } catch (Exception e) {
                    logger.error("🚨 Failed to expire Budget ID {}. Quarantining.", budgetId, e);

                    // 3. Save Quarantine state in a NEW transaction so it doesn't roll back
                    transactionTemplate.execute(status -> {
                        Budget failedBudget = budgetRepository.findByIdForUpdate(budgetId).orElse(null);
                        if (failedBudget != null) {
                            failedBudget.setStatus(BudgetStatus.FAILED_PROCESSING);
                            budgetRepository.save(failedBudget);
                        }
                        return null;
                    });
                }
            }

            // The per-budget TransactionTemplate above commits writes. Keep only
            // the persistence-context cleanup here so this scheduled method never
            // requires an outer transaction.
            entityManager.clear();

            hasMore = page.hasNext();
        }

//        refreshDynamicTasks(today);
    }
    // ========================================================================
    // 🛑 FIXED SPAM: SEPARATE DAILY CRON FOR BUDGET WARNINGS (Runs at 9:00 AM)
    // ========================================================================
    @Scheduled(cron = "0 0 9 * * ?", zone = "Africa/Lagos")
    @Transactional
    public void notifyExpiringBudgets() {
        LocalDate today = fetchCurrentDateTimeFromDatabase().toLocalDate();
        LocalDate threeDaysFromNow = today.plusDays(3);

        List<Budget> nearingEnd = budgetRepository.findByStatusAndEndDate(BudgetStatus.ACTIVE, threeDaysFromNow);
        for (Budget budget : nearingEnd) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("budgetName", budget.getName() != null ? budget.getName() : "Your Budget");
            OutboxEvent event = buildOutboxEvent(
                    NotificationType.BUDGET_END_SOON,
                    budget.getUser().getId(),
                    budget.getId(),
                    null,
                    payload
            );

            outboxEventRepository.save(event);
        }
        logger.info("Sent 3-day warning notifications to {} budgets.", nearingEnd.size());

        // Same-day alert — distinct from the 3-day warning above. Budget stays
        // ACTIVE through its actual endDate (see isBudgetPastEndDate/processBudgets),
        // so this fires the day everything still works, not after it's already closed.
        List<Budget> endingToday = budgetRepository.findByStatusAndEndDate(BudgetStatus.ACTIVE, today);
        for (Budget budget : endingToday) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("budgetName", budget.getName() != null ? budget.getName() : "Your Budget");
            OutboxEvent event = buildOutboxEvent(
                    NotificationType.BUDGET_ENDS_TODAY,
                    budget.getUser().getId(),
                    budget.getId(),
                    null,
                    payload
            );

            outboxEventRepository.save(event);
        }
        logger.info("Sent 'ends today' notifications to {} budgets.", endingToday.size());
    }

    private void refreshDynamicTasks(LocalDate today) {
        // Keep your existing logic for dynamic envelopes here
        // If this list gets huge, we can batch it later.
        List<Envelope> dynamicEnvelopes = envelopeRepository.findByConditionsType("dynamic");
        for (Envelope envelope : dynamicEnvelopes) {
            Budget budget = envelope.getBudget();
            if (budget != null && budget.getStatus() == BudgetStatus.ACTIVE && !budget.getEndDate().isBefore(today)) {
                scheduleDynamicTasks(envelope);
            }
        }
    }
    // ✅ NEW SAFE VERSION
    @Scheduled(fixedRateString = "${moniewise.scheduler.critical-tasks.fixed-rate-ms:30000}")
    public void processScheduledTasks() {
        if (!scheduledTasksRunning.compareAndSet(false, true)) {
            logger.warn("Skipping scheduled-task processing because the previous run is still active");
            return;
        }

        try {
            long startTime = System.nanoTime();
            LocalDateTime now = fetchCurrentDateTimeFromDatabase();
            logger.debug("Starting BATCH task processing at {}", now);

            int batchSize = boundedBatchSize(criticalTasksBatchSize);
            boolean hasNextBatch = true;

            int maxLoops = boundedLoopLimit(criticalTasksMaxLoops);
            int currentLoop = 0;

            while (hasNextBatch && currentLoop < maxLoops) {
                currentLoop++;

                hasNextBatch = transactionTemplate.execute(status -> {
                    List<ScheduledTask> tasks = scheduledTaskRepository.claimDueTasks(now, batchSize);

                    if (tasks.isEmpty()) return false;

                    List<Long> tasksToComplete = new ArrayList<>();
                    List<Envelope> envelopesToUpdate = new ArrayList<>();
                    List<TransactionLog> logsToSave = new ArrayList<>();
                    List<OutboxEvent> outboxEventsToSave = new ArrayList<>();

                    for (ScheduledTask task : tasks) {
                        try {
                            processTask(
                                    task,
                                    now,
                                    envelopesToUpdate,
                                    logsToSave,
                                    tasksToComplete,
                                    outboxEventsToSave
                            );
                        } catch (Exception e) {
                            logger.error("Skipping failed task {}: {}", task.getId(), e.getMessage(), e);
                            scheduledTaskRepository.markTaskFailed(task.getId(), e.getMessage());
                        }
                    }

                    if (!envelopesToUpdate.isEmpty()) {
                        envelopeRepository.saveAll(envelopesToUpdate);
                        Set<Long> userIdsToEvict = new HashSet<>();
                        for (Envelope envelope : envelopesToUpdate) {
                            if (envelope.getBudget() != null
                                    && envelope.getBudget().getUser() != null
                                    && envelope.getBudget().getUser().getId() != null) {
                                userIdsToEvict.add(envelope.getBudget().getUser().getId());
                            }
                        }
                        monnieCacheInvalidationService.evictUsersAfterCommit(userIdsToEvict);
                    }

                    if (!logsToSave.isEmpty()) {
                        transactionLogRepository.saveAll(logsToSave);
                    }

                    if (!outboxEventsToSave.isEmpty()) {
                        outboxEventRepository.saveAll(outboxEventsToSave);
                    }

                    if (!tasksToComplete.isEmpty()) {
                        scheduledTaskRepository.markTasksCompleted(tasksToComplete, LocalDateTime.now());
                    }

                    entityManager.flush();
                    entityManager.clear();

                    return tasks.size() == batchSize;
                });
            }

            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            logger.debug("Batch tasks completed in {}ms", durationMs);
        } catch (Exception e) {
            if (ExceptionClassifier.isDatabasePoolExhausted(e)) {
                logger.warn("DB pool busy; scheduled-task processing will retry on the next tick: {}",
                        ExceptionClassifier.rootCauseMessage(e));
                return;
            }
            logger.error("Scheduled-task processing failed", e);
        } finally {
            scheduledTasksRunning.set(false);
        }
    }
    // FIX: New method to process tasks, including LIMIT_RESET
    private void processTask(
            ScheduledTask task,
            LocalDateTime now,
            List<Envelope> envelopesToUpdate,
            List<TransactionLog> logsToSave,
            List<Long> taskIdsToComplete,
            List<OutboxEvent> outboxEventsToSave
    ) {
        Envelope envelope = envelopeRepository.findById(task.getEnvelopeId()).orElse(null);

        if (envelope == null) {
            logger.warn("Envelope {} not found for task {}", task.getEnvelopeId(), task.getId());
            taskIdsToComplete.add(task.getId());
            return;
        }

        Budget budget = envelope.getBudget();

        if (budget == null || budget.getStatus() != BudgetStatus.ACTIVE) {
            logger.warn("Skipping task {} for envelope {}: budget is not active", task.getId(), task.getEnvelopeId());
            taskIdsToComplete.add(task.getId());
            return;
        }

        if (isBudgetPastEndDate(budget, now)) {
            logger.warn(
                    "Skipping task {} for envelope {} because budget {} ended on {}",
                    task.getId(),
                    envelope.getId(),
                    budget.getId(),
                    budget.getEndDate()
            );

            taskIdsToComplete.add(task.getId());
            return;
        }

        String userId = budget.getUser().getId().toString();

        switch (task.getTaskType()) {
            case "LIMIT_RESET":
                envelopeService.resetEnvelopeLimits(envelope);
                logger.info("Reset limit for envelope {} at {}", envelope.getId(), now);
                taskIdsToComplete.add(task.getId());
                // CRITICAL: Schedule the NEXT reset
                scheduleNextTask(envelope, "LIMIT_RESET", now);
                break;
    //            case "PRE_DISBURSEMENT_NOTIFICATION_15MIN":
    //            case "PRE_DISBURSEMENT_NOTIFICATION_5MIN":
    //                // ✅ PUBLISH EVENT INSTEAD OF HARDCODED NOTIFICATION
    //                String timeLimit = task.getTaskType().contains("15") ? "15 minutes" : "5 minutes";
    //                Map<String, Object> preParams = new HashMap<>();
    //                preParams.put("amount", formatAmount(envelope.getConditions().get("limit")));
    //                preParams.put("envelopeName", envelope.getName() != null ? envelope.getName() : "Envelope");
    //                preParams.put("time", timeLimit);
    //
    //                eventPublisher.publishEvent(new GenericNotificationEvent(
    //                        this, userId, NotificationType.PRE_DISBURSEMENT, preParams,
    //                        budget.getId(), envelope.getId(), "/envelopes/" + envelope.getId()
    //                ));
    //                taskIdsToDelete.add(task.getId());
    //                break;
            case "DISBURSEMENT":
                processEnvelopeDisbursement(envelope, now.toLocalDate(), envelopesToUpdate, logsToSave, outboxEventsToSave);
                // CRITICAL: Schedule the NEXT disbursement so it happens again tomorrow/next week
//                scheduleNextTask(envelope, "DISBURSEMENT", now);

                taskIdsToComplete.add(task.getId());
                scheduleDynamicTasks(envelope);

                // 🛑 THE FIX: Update the frontend UI date so it doesn't get stuck in the past!
                envelope.setNextDisbursementAt(calculateNextDisbursementTime(envelope));
                envelopesToUpdate.add(envelope);

                if (Boolean.TRUE.equals(envelope.getIsAutomated())) {
                    ScheduledTask autoTask = new ScheduledTask();
                    autoTask.setEnvelopeId(envelope.getId());
                    autoTask.setTaskType("AUTO_TRANSFER");
                    autoTask.setTriggerTime(now);
                    autoTask.setCreatedAt(now);
                    autoTask.setStatus("PENDING");
                    autoTask.setRetryCount(0);
                    scheduledTaskRepository.save(autoTask);
                    logger.info("Scheduled auto-transfer for envelope {} after disbursement", envelope.getId());
                }
                break;
            case "AUTO_TRANSFER":
                try {
                    envelopeAutoTransferService.executeAutoTransfer(envelope.getId());
                } catch (Exception e) {
                    logger.error("Auto-transfer failed for envelope {}: {}", envelope.getId(), e.getMessage());
                    if (task.getRetryCount() < 2) {
                        ScheduledTask retry = new ScheduledTask();
                        retry.setEnvelopeId(envelope.getId());
                        retry.setTaskType("AUTO_TRANSFER");
                        retry.setTriggerTime(now.plusMinutes(30));
                        retry.setStatus("PENDING");
                        retry.setRetryCount(task.getRetryCount() + 1);
                        retry.setCreatedAt(now);
                        scheduledTaskRepository.save(retry);
                        logger.info("Scheduled auto-transfer retry #{} for envelope {} at {}",
                                task.getRetryCount() + 1, envelope.getId(), now.plusMinutes(30));
                    } else {
                        envelopeAutoTransferService.notifyAutoTransferExhausted(envelope);
                        logger.error("Auto-transfer exhausted all retries for envelope {}", envelope.getId());
                    }
                }
                taskIdsToComplete.add(task.getId());
                break;
            default:
                logger.warn("Unknown task type {} for envelope {}", task.getTaskType(), envelope.getId());
                taskIdsToComplete.add(task.getId());
        }
    }

    // ================== YOU NEED TO ADD THIS HELPER METHOD ==========================
    private void scheduleNextTask(Envelope envelope, String taskType, LocalDateTime lastTriggerTime) {
        LocalDateTime nextTime = calculateNextDisbursementTime(envelope); // You already have this logic!

        if (nextTime != null) {
            ScheduledTask newTask = new ScheduledTask();
            newTask.setEnvelopeId(envelope.getId());
            newTask.setTaskType(taskType);
            newTask.setTriggerTime(nextTime);
            newTask.setCreatedAt(LocalDateTime.now());
            newTask.setStatus("PENDING");
            newTask.setRetryCount(0);
            scheduledTaskRepository.save(newTask);
            logger.info("Chained next {} task for envelope {} at {}", taskType, envelope.getId(), nextTime);
        }
    }
//    private void processBudgetExpiry(Budget budget, List<Budget> budgetsToUpdate, List<Envelope> envelopesToUpdate,
//                                     List<TransactionLog> logsToSave) {
//        User user = budget.getUser();
//
//        // 🛡️ DEFENSIVE CHECK 1: Does user exist?
//        if (user == null || user.getId() == null) {
//            throw new IllegalStateException("Budget " + budget.getId() + " has no valid user linked.");
//        }
//
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//        BigDecimal totalRefunded = BigDecimal.ZERO;
//        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budget.getId());
//
//        for (Envelope envelope : envelopes) {
//            BigDecimal remainingAmount = envelope.getTotalRemainingAmount();
//
//            // 🛡️ DEFENSIVE CHECK 2: Ignore negative/zero balances safely
//            if (remainingAmount != null && remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
//
//                try {
//                    // Attempt Refund
//                    walletService.fundWallet(
//                            user.getId(), remainingAmount,
//                            String.format("Refund from '%s' (Budget: %s)", envelope.getName(), budget.getName()), true
//                    );
//                } catch (Exception e) {
//                    // If wallet funding fails, we MUST throw exception to trigger Quarantine
//                    throw new RuntimeException("Wallet funding failed for user " + user.getId() + ": " + e.getMessage(), e);
//                }
//
//                // Log the Refund
//                TransactionLog refundLog = new TransactionLog();
//                refundLog.setUserId(user.getId());
//                refundLog.setBudgetId(budget.getId());
//                refundLog.setSourceEnvelopeId(envelope.getId());
//                refundLog.setAmount(remainingAmount);
//                refundLog.setTransactionType(BUDGET_COMPLETION_REFUND);
//                refundLog.setStatus(COMPLETED);
//                refundLog.setCreatedAt(now);
//                refundLog.setReference("MW-REF-" + UUID.randomUUID().toString());
//                logsToSave.add(refundLog);
//
//                totalRefunded = totalRefunded.add(remainingAmount);
//            }
//
//            // Clear envelope balance
//            envelope.setRemainingAmount(BigDecimal.ZERO);
//            envelope.setTotalRemainingAmount(BigDecimal.ZERO);
//            envelopesToUpdate.add(envelope);
//
//            // Clean tasks
//            scheduledTaskRepository.deleteByEnvelopeId(envelope.getId());
//        }
//
//        // Mark Success
//        budget.setStatus(BudgetStatus.COMPLETED);
//        budget.setRemainingAmount(BigDecimal.ZERO);
//        budgetsToUpdate.add(budget);
//
//        // Notify User
//        if (totalRefunded.compareTo(BigDecimal.ZERO) > 0) {
//
//            Map<String, Object> compParams = new HashMap<>();
//            compParams.put("budgetName", budget.getName() != null ? budget.getName() : "Budget");
//            compParams.put("refunded", String.format("%,.2f", totalRefunded));
//
//            eventPublisher.publishEvent(new GenericNotificationEvent(
//                    this, user.getId().toString(), NotificationType.BUDGET_COMPLETED,
//                    compParams, budget.getId(), null, "/budgets/" + budget.getId()
//            ));
//        } else {
//            notificationService.sendNotification(
//                    user.getId().toString(),
//                    String.format("Your budget '%s' has ended with no unused funds to refund.", budget.getName()),
//                    NotificationType.BUDGET_COMPLETED
//            );
//        }
//
//        logger.info("Budget {} completed for user {}. Refunded ₦{}", budget.getId(), user.getId(), totalRefunded);
//    }

    private void processBudgetExpiry(
            Budget budget,
            List<Budget> budgetsToUpdate,
            List<Envelope> envelopesToUpdate,
            List<TransactionLog> logsToSave
    ) {
        User user = budget.getUser();

        if (user == null || user.getId() == null) {
            throw new IllegalStateException("Budget " + budget.getId() + " has no valid user linked.");
        }

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        BigDecimal totalRefunded = BigDecimal.ZERO;

        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budget.getId());

        for (Envelope envelope : envelopes) {
            scheduledTaskRepository.deleteByEnvelopeId(envelope.getId());

            BigDecimal heldAmount = envelope.getHeldAmount() != null
                    ? envelope.getHeldAmount()
                    : BigDecimal.ZERO;

            if (heldAmount.compareTo(BigDecimal.ZERO) > 0) {
                logger.warn(
                        "Budget {} is completing while envelope {} has heldAmount ₦{}. " +
                                "Held funds will not be refunded as freely available balance.",
                        budget.getId(),
                        envelope.getId(),
                        heldAmount
                );
            }

            BigDecimal refundableAmount = calculateSafeRefundableAmount(envelope);

            if (refundableAmount.compareTo(BigDecimal.ZERO) > 0) {
                TransactionLog refundLog = new TransactionLog();
                refundLog.setUserId(user.getId());
                refundLog.setBudgetId(budget.getId());
                refundLog.setSourceEnvelopeId(envelope.getId());
                refundLog.setAmount(refundableAmount);
                refundLog.setTransactionType(BUDGET_COMPLETION_REFUND);
                refundLog.setStatus(COMPLETED);
                refundLog.setCreatedAt(now);
                refundLog.setReference("MW-REF-" + UUID.randomUUID());
                refundLog.setDescription("Unused envelope balance refunded at budget completion");
                logsToSave.add(refundLog);

                totalRefunded = totalRefunded.add(refundableAmount);
            }

            envelope.setRemainingAmount(BigDecimal.ZERO);
            envelope.setTotalRemainingAmount(BigDecimal.ZERO);
            envelope.setHeldAmount(BigDecimal.ZERO);
            envelope.setNextDisbursementAt(null);
            envelope.setHasMatured(true);

            envelopesToUpdate.add(envelope);
        }

        if (totalRefunded.compareTo(BigDecimal.ZERO) > 0) {
            try {
                String summaryMsg = String.format(
                        "Total refund of ₦%,.2f from ended budget: %s",
                        totalRefunded,
                        budget.getName()
                );

                walletService.fundWallet(
                        user.getId(),
                        totalRefunded,
                        summaryMsg,
                        false
                );
            } catch (Exception e) {
                throw new RuntimeException(
                        "Wallet funding failed for user " + user.getId() + ": " + e.getMessage(),
                        e
                );
            }
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("budgetName", budget.getName() != null ? budget.getName() : "Budget");
        payload.put("refunded", String.format("%,.2f", totalRefunded));

        outboxEventRepository.save(buildOutboxEvent(
                NotificationType.BUDGET_COMPLETED,
                user.getId(),
                budget.getId(),
                null,
                payload
        ));

        try {
            feeReserveService.releaseForBudget(user.getId(), budget.getId());
        } catch (Exception e) {
            logger.warn("[FeeReserve] Failed to release reserve for completed budget {} — non-blocking",
                    budget.getId(), e);
        }

        budget.setStatus(BudgetStatus.COMPLETED);
        budget.setRemainingAmount(BigDecimal.ZERO);
        budgetsToUpdate.add(budget);
        badgeAwardService.awardBudgetCompletionBadgeAfterCommit(user, budget, totalRefunded);

        logger.info(
                "Budget {} completed. Refundable total ₦{} sent to user {}.",
                budget.getId(),
                totalRefunded,
                user.getId()
        );
    }

    private void processEnvelopeDisbursement(Envelope envelope, LocalDate today, List<Envelope> envelopesToUpdate,
                                             List<TransactionLog> logsToSave,
                                             List<OutboxEvent> outboxEventsToSave) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null || !conditions.containsKey("type")) {
            logger.warn("Invalid conditions for envelope {}", envelope.getId());
            return;
        }

        // 🛑 1. GUARD CLAUSE: Has this already run?
        // This stops the infinite loop for daily/weekly envelopes
        if (isSamePeriod(envelope.getConditions(), fetchCurrentDateTimeFromDatabase(), envelope.getLastDisbursedAt())) {
            logger.info("Skipping disbursement for envelope {} - already processed for this period.", envelope.getId());
            return;
        }

        String type = (String) conditions.get("type");
        // FIX: Skip dynamic envelopes to prevent duplicate disbursements
//        if ("dynamic".equals(type)) {
//            return; // Handled by processScheduledTasks
//        }

        LocalDateTime lastDisbursedAt = envelope.getLastDisbursedAt() != null
                ? envelope.getLastDisbursedAt()
                : LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
        String userId = envelope.getBudget().getUser().getId().toString();
        String message = null;
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        switch (type) {

            // The Scheduler already checked the time. Trust the Scheduler.
            case "daily":
            case "weekly":
            case "monthly":
            case "quarterly":
            case "biannual":
            case "dynamic":
//                // 🛑 2. SAFETY CHECK FOR NEW ENVELOPES
//                // If lastDisbursedAt is NULL (The Bug), assume it was created "Just Now" and fix the date
//                // WITHOUT refunding/resetting the money.
//                if (envelope.getLastDisbursedAt() == null) {
//                    logger.info("Fixing NULL lastDisbursedAt for envelope {}", envelope.getId());
//                    envelope.setLastDisbursedAt(now);
//                    envelopesToUpdate.add(envelope);
//                    return; // EXIT. Do not refill.
//                }

                // 1. CHECK FOR UNSPENT MONEY (The "Saver's Reward")
                BigDecimal unspent = envelope.getRemainingAmount();

                if (unspent.compareTo(BigDecimal.ZERO) > 0) {
                    // Move it back to the Vault
//                    envelope.setTotalRemainingAmount(envelope.getTotalRemainingAmount().add(unspent));

                    // Optional: Create a log so the user knows why their vault increased
                    TransactionLog refundLog = new TransactionLog();
                    refundLog.setUserId(Long.valueOf(userId)); // Parse from string
                    refundLog.setBudgetId(envelope.getBudget().getId());
                    refundLog.setSourceEnvelopeId(envelope.getId());
                    refundLog.setAmount(unspent);
                    refundLog.setTransactionType(TransactionType.ROLLOVER_REFUND); // Make sure this Enum exists!
                    refundLog.setReference("ROLLOVER-" + UUID.randomUUID().toString());
                    refundLog.setStatus(TransactionStatus.COMPLETED);
                    refundLog.setDescription("Unspent daily funds returned to vault");
                    refundLog.setCreatedAt(now);
                    logsToSave.add(refundLog);

                    logger.info("Swept unspent ₦{} back to vault for envelope {}", unspent, envelope.getId());
                }

                // 2. NOW IT IS SAFE TO RESET
                envelope.setRemainingAmount(BigDecimal.ZERO);
           try {
                    // We call the service to do the math and update conditions["limit"]
                    envelopeService.triggerRecalculation(envelope);

                } catch (Exception e) {
                    logger.error("Failed to recalculate limit for envelope {}", envelope.getId(), e);
                }
                envelopesToUpdate.add(envelope);

                // 3. PROCEED TO DISBURSE
                disburseEnvelope(envelope, now, envelopesToUpdate, logsToSave, outboxEventsToSave);
                break;
            case "safe_lock":
            case "strict_lock":
                releaseLockedEnvelope(envelope, now, envelopesToUpdate, logsToSave, outboxEventsToSave);
                break;
            case "emergency":
                // No automatic disbursement; handled by user action
                break;

            default:
                logger.warn("Unknown disbursement type {} for envelope {}", type, envelope.getId());
                break;
        }

        // NOTE: Direct sendNotification() call removed. All disbursement notifications
        // are now created as outbox events inside disburseEnvelope() and processed
        // reliably by NotificationOutboxWorker. The `message` variable above is always
        // null in every active code path — this block was dead code and has been removed.
    }

    private void releaseLockedEnvelope(Envelope envelope, LocalDateTime now, List<Envelope> envelopesToUpdate,
                                       List<TransactionLog> logsToSave, List<OutboxEvent> outboxEventsToSave) {
        BigDecimal amountToRelease = availableVaultBalance(envelope);
        envelope.setRemainingAmount(amountToRelease);
        envelope.setLastDisbursedAt(now);
        envelope.setNextDisbursementAt(null);
        envelope.setHasMatured(true);
        envelopesToUpdate.add(envelope);

        if (amountToRelease.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        TransactionLog log = new TransactionLog();
        log.setUserId(envelope.getBudget().getUser().getId());
        log.setBudgetId(envelope.getBudget().getId());
        log.setSourceEnvelopeId(envelope.getId());
        log.setAmount(amountToRelease);
        log.setTransactionType(TransactionType.ENVELOPE_DISBURSEMENT);
        log.setDescription("Locked envelope released");
        log.setStatus(TransactionStatus.COMPLETED);
        log.setReference("LOCK-REL-" + envelope.getId() + "-" + System.currentTimeMillis());
        log.setCreatedAt(now);
        logsToSave.add(log);

        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", String.format("%,.2f", amountToRelease));
        payload.put("envelopeName", envelope.getName() != null ? envelope.getName() : "Envelope");

        outboxEventsToSave.add(buildOutboxEvent(
                NotificationType.DISBURSEMENT_SUCCESS,
                envelope.getBudget().getUser().getId(),
                envelope.getBudget().getId(),
                envelope.getId(),
                payload
        ));

        // Activation journey off-switch: comment out this one invocation to
        // stop the first spend-from-envelope push/email.
        activationJourneyNudgeService.nudgeAfterEnvelopeUnlocked(
                envelope.getBudget().getUser().getId(),
                envelope.getBudget().getId(),
                envelope.getId());
    }

    private void disburseEnvelope(Envelope envelope, LocalDateTime now, List<Envelope> envelopesToUpdate,
                                  List<TransactionLog> logsToSave, List<OutboxEvent> outboxEventsToSave) {

        Budget budget = envelope.getBudget();

        if (budget == null || budget.getStatus() != BudgetStatus.ACTIVE) {
            logger.warn("Skipping disbursement for envelope {} because budget is not active", envelope.getId());
            return;
        }

        if (isBudgetPastEndDate(budget, now)) {
            logger.warn(
                    "Skipping disbursement for envelope {} because budget {} ended on {}",
                    envelope.getId(),
                    budget.getId(),
                    budget.getEndDate()
            );
            return;
        }

        Map<String, Object> conditions = envelope.getConditions();

        if (conditions == null || !conditions.containsKey("limit")) return;

        BigDecimal limit = new BigDecimal(((Number) conditions.get("limit")).doubleValue());
        if (limit.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal amountToDisburse = envelope.getTotalRemainingAmount().min(limit);

        // ✅ NEW ARCHITECTURE: Handle both Success and Empty states
        if (amountToDisburse.compareTo(BigDecimal.ZERO) > 0) {

            // 1. FUNDING LOGIC
            envelope.setRemainingAmount(amountToDisburse);
            envelope.setLastDisbursedAt(now);

            String type = (String) conditions.getOrDefault("type", "");
            if ("safe_lock".equals(type) || "strict_lock".equals(type)) {
                envelope.setHasMatured(true);
            }
            envelopesToUpdate.add(envelope);

            // 2. TRANSACTION LOG
            TransactionLog log = new TransactionLog();
            log.setUserId(envelope.getBudget().getUser().getId());
            log.setBudgetId(envelope.getBudget().getId());
            log.setSourceEnvelopeId(envelope.getId());
            log.setAmount(amountToDisburse);
            log.setTransactionType(TransactionType.ENVELOPE_DISBURSEMENT);
            log.setDescription("Auto-deposit to pocket");
            log.setStatus(TransactionStatus.COMPLETED);
            log.setReference("AUTO-" + envelope.getId() + "-" + System.currentTimeMillis());
            log.setCreatedAt(now);
            logsToSave.add(log);

            // 3. SUCCESS NOTIFICATION
//            eventsToPublish.add(new GenericNotificationEvent(
//                    this,
//                    envelope.getBudget().getUser().getId().toString(),
//                    NotificationType.DISBURSEMENT_SUCCESS,
//                    params,
//                    envelope.getBudget().getId(),
//                    envelope.getId(),
//                    "/envelopes/" + envelope.getId()
//            ));

            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", String.format("%,.2f", amountToDisburse));
            payload.put("envelopeName", envelope.getName() != null ? envelope.getName() : "Envelope");

            outboxEventsToSave.add(buildOutboxEvent(
                    NotificationType.DISBURSEMENT_SUCCESS,
                    envelope.getBudget().getUser().getId(),
                    envelope.getBudget().getId(),
                    envelope.getId(),
                    payload
            ));

            // Activation journey off-switch: comment out this one invocation to
            // stop the first spend-from-envelope push/email.
            activationJourneyNudgeService.nudgeAfterEnvelopeUnlocked(
                    envelope.getBudget().getUser().getId(),
                    envelope.getBudget().getId(),
                    envelope.getId());

                        logger.info("Auto-disbursed ₦{} to envelope {}", amountToDisburse, envelope.getId());

//        } else {
//            // 🛑 NEW: EMPTY VAULT NOTIFICATION
//            // If the cron runs but there is no money left to give, tell the user!
//            Map<String, Object> params = new HashMap<>();
//            params.put("envelopeName", envelope.getName() != null ? envelope.getName() : "Envelope");
//
//            eventPublisher.publishEvent(new GenericNotificationEvent(
//                    this, envelope.getBudget().getUser().getId().toString(),
//                    NotificationType.ENVELOPE_LOW_BALANCE, // Make sure to add handling for this Enum in your NotificationService
//                    params,
//                    envelope.getBudget().getId(), envelope.getId(), "/envelopes/" + envelope.getId()
//            ));
//            logger.warn("Disbursement skipped for envelope {}: Vault is empty.", envelope.getId());
//        }
        } else {
            // 🛑 ADD THIS: Tell the user the vault is empty!
            Map<String, Object> payload = new HashMap<>();
            payload.put("envelopeName", envelope.getName() != null ? envelope.getName() : "Envelope");

            outboxEventsToSave.add(buildOutboxEvent(
                    NotificationType.ENVELOPE_LOW_BALANCE,
                    envelope.getBudget().getUser().getId(),
                    envelope.getBudget().getId(),
                    envelope.getId(),
                    payload
            ));
                    logger.warn("Disbursement skipped for envelope {}: Vault is empty.", envelope.getId());
                }
    }

//    private void disburseEnvelope(Envelope envelope, LocalDateTime now, List<Envelope> envelopesToUpdate,
//                                  List<TransactionLog> logsToSave) {
//        Map<String, Object> conditions = envelope.getConditions();
//
//        if (conditions == null || !conditions.containsKey("limit")) return;
//
//        // 1. Validate Limit
//        BigDecimal limit = new BigDecimal(((Number) conditions.get("limit")).doubleValue());
//        if (limit.compareTo(BigDecimal.ZERO) <= 0) return;
//
//        // 2. Calculate Amount (Cap at what is actually in the Vault)
//        BigDecimal amountToDisburse = envelope.getTotalRemainingAmount().min(limit);
//
//        if (amountToDisburse.compareTo(BigDecimal.ZERO) > 0) {
//
//            envelope.setRemainingAmount(amountToDisburse);
//            envelope.setLastDisbursedAt(now);
//
//            // 4. Handle Locks (Prevent them from locking again immediately)
//            String type = (String) conditions.getOrDefault("type", "");
//            if ("safe_lock".equals(type) || "strict_lock".equals(type)) {
//                envelope.setHasMatured(true);
//            }
//
//            envelopesToUpdate.add(envelope);
//
//            // 5. Create Transaction Log (So user sees "+N2000" in history)
//            TransactionLog log = new TransactionLog();
//            log.setUserId(envelope.getBudget().getUser().getId());
//            log.setBudgetId(envelope.getBudget().getId());
//            log.setSourceEnvelopeId(envelope.getId());
//            log.setAmount(amountToDisburse);
//            log.setTransactionType(TransactionType.ENVELOPE_DISBURSEMENT);
//            log.setDescription("Auto-deposit to pocket");
//            log.setStatus(TransactionStatus.COMPLETED);
//            log.setReference("AUTO-" + envelope.getId() + "-" + System.currentTimeMillis());
//            log.setCreatedAt(now);
//            logsToSave.add(log);
//
//            // ✅ PUBLISH EVENT INSTEAD OF HARDCODED NOTIFICATION
//            Map<String, Object> params = new HashMap<>();
//            params.put("amount", String.format("%,.2f", amountToDisburse));
//            params.put("envelopeName", envelope.getName() != null ? envelope.getName() : "Envelope");
//
//            eventPublisher.publishEvent(new GenericNotificationEvent(
//                    this, envelope.getBudget().getUser().getId().toString(),
//                    NotificationType.DISBURSEMENT_SUCCESS, params,
//                    envelope.getBudget().getId(), envelope.getId(), "/envelopes/" + envelope.getId()
//            ));
//
//            logger.info("Auto-disbursed ₦{} to envelope {}", amountToDisburse, envelope.getId());
//        }
//    }
    private boolean isSamePeriod(Map<String, Object> conditions, LocalDateTime now, LocalDateTime lastDisbursedAt) {
        // 1. Safety Check: If never disbursed, obviously not same period.
        if (lastDisbursedAt == null) return false;

        String type = (String) conditions.getOrDefault("type", "daily");
        LocalDate today = now.toLocalDate();
        LocalDate lastRunDate = lastDisbursedAt.toLocalDate();

        switch (type) {
            // 2. DAILY & DYNAMIC: Both just need to ensure they haven't run TODAY.
            case "daily":
            case "dynamic":
                return today.isEqual(lastRunDate);

            // 3. WEEKLY: Check if we are in the same ISO Week (Monday start)
            case "weekly":
                // Calculate the "Monday" of the current week and the last run week
                LocalDate thisWeekStart = today.minusDays(today.getDayOfWeek().getValue() - 1);
                LocalDate lastWeekStart = lastRunDate.minusDays(lastRunDate.getDayOfWeek().getValue() - 1);

                return thisWeekStart.isEqual(lastWeekStart);

            case "monthly":
                return today.getYear() == lastRunDate.getYear()
                        && today.getMonthValue() == lastRunDate.getMonthValue();

            case "quarterly": {
                int thisQ = (today.getMonthValue() - 1) / 3;
                int lastQ = (lastRunDate.getMonthValue() - 1) / 3;
                return today.getYear() == lastRunDate.getYear() && thisQ == lastQ;
            }

            case "biannual": {
                int thisH = today.getMonthValue() <= 6 ? 1 : 2;
                int lastH = lastRunDate.getMonthValue() <= 6 ? 1 : 2;
                return today.getYear() == lastRunDate.getYear() && thisH == lastH;
            }

            default:
                return false; // Default to "Run It" if type is unknown
        }
    }
    @Scheduled(cron = "0 0 2 * * ?", zone = "Africa/Lagos") // FIX: Added zone for consistency
    public void cleanOldTasks() {
        LocalDateTime threshold = fetchCurrentDateTimeFromDatabase().minusDays(30);
        scheduledTaskRepository.deleteByTriggerTimeBefore(threshold);
        logger.info("Cleaned tasks older than {}", threshold);
    }

    @Scheduled(cron = "0 0 3 * * ?", zone = "Africa/Lagos") // FIX: Added zone for consistency
    public void cleanOldNotifications() {
        LocalDateTime threshold = fetchCurrentDateTimeFromDatabase().minusDays(30);
        int deleted = notificationRepository.deleteByCreatedAtBefore(threshold);
        logger.info("Cleaned {} notifications older than {}", deleted, threshold);
    }
//    @Scheduled(fixedRateString = "${moniewise.scheduler.pending-disbursement.fixed-rate-ms:120000}")
    @Transactional
    public void checkAndHandleMaturedEnvelopes() {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        // Limit to 100 per minute to prevent memory spikes
        Pageable limit = PageRequest.of(0, 100);

        // Note: You must update your repository method to accept Pageable:
        // List<Envelope> findByNextDisbursementAtBefore(LocalDateTime time, Pageable pageable);
        List<Envelope> envelopes = envelopeRepository.findByNextDisbursementAtBefore(now, limit);

        for (Envelope envelope : envelopes) {
            handleMaturedEnvelope(envelope);
        }
    }
    private void handleMaturedEnvelope(Envelope envelope) {
        Map<String, Object> conditions = envelope.getConditions();

        // Default to total amount if no limit exists (unlocks everything)
        BigDecimal limit = conditions != null && conditions.containsKey("limit")
                ? new BigDecimal(((Number) conditions.get("limit")).doubleValue())
                : envelope.getTotalRemainingAmount();

        BigDecimal amountToDisburse = limit.min(envelope.getTotalRemainingAmount());

        // If empty, just reschedule next check and exit
        if (amountToDisburse.compareTo(BigDecimal.ZERO) <= 0) {
            envelope.setNextDisbursementAt(calculateNextDisbursementTime(envelope));
            envelopeRepository.save(envelope);
            return;
        }

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        // 1. Reset Limits (Critical for data integrity)
        envelopeService.resetEnvelopeLimits(envelope);

        // 2. Auto-Deposit to Pocket
        envelope.setRemainingAmount(amountToDisburse);
        envelope.setLastDisbursedAt(now);
        envelope.setHasMatured(true);

        // 3. Schedule Next Check (prevent infinite loop)
        envelope.setNextDisbursementAt(calculateNextDisbursementTime(envelope));

        envelopeRepository.save(envelope);

        // ✅ PUBLISH EVENT INSTEAD OF HARDCODED NOTIFICATION
        Map<String, Object> params = new HashMap<>();
        params.put("amount", String.format("%,.2f", amountToDisburse));
        params.put("envelopeName", envelope.getName() != null ? envelope.getName() : "Envelope");

        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", String.format("%,.2f", amountToDisburse));
        payload.put("envelopeName", envelope.getName() != null ? envelope.getName() : "Envelope");

        outboxEventRepository.save(buildOutboxEvent(
                NotificationType.DISBURSEMENT_SUCCESS,
                envelope.getBudget().getUser().getId(),
                envelope.getBudget().getId(),
                envelope.getId(),
                payload
        ));
    }

    @Scheduled(fixedRateString = "${moniewise.scheduler.pending-disbursement.fixed-rate-ms:120000}")
    @Transactional
    public void refundExpiredPendingDisbursements() {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        Pageable limit = PageRequest.of(0, 100);

        // 1. Find expired items
        List<PendingDisbursement> expiredDisbursements = pendingDisbursementRepository.findByExpiresAtBeforeAndNotifiedUserTrue(now, limit);
        List<PendingDisbursement> disbursementsToDelete = new ArrayList<>();


        for (PendingDisbursement pd : expiredDisbursements) {
            try {
                // 2. Just Notify (No money movement needed)
                // We inform them the window is closed.
                Envelope envelope = envelopeRepository.findById(pd.getEnvelopeId()).orElse(null);
                Long budgetId = (envelope != null) ? envelope.getBudget().getId() : null;

                // ✅ PUBLISH EVENT
                Map<String, Object> params = new HashMap<>();
                params.put("envelopeName", pd.getEnvelopeName() != null ? pd.getEnvelopeName() : "Envelope");

                Map<String, Object> payload = new HashMap<>();

                payload.put("envelopeName", pd.getEnvelopeName() != null ? pd.getEnvelopeName() : "Envelope");


                outboxEventRepository.save(buildOutboxEvent(
                        NotificationType.EXPIRED_DISBURSEMENT,
                        pd.getUserId(),
                        budgetId,
                        pd.getEnvelopeId(),
                        payload
                ));

                disbursementsToDelete.add(pd);

            } catch (Exception e) {
                logger.error("Failed to process expiration for {}: {}", pd.getId(), e.getMessage());
            }
        }

        // 3. Cleanup
        pendingDisbursementRepository.deleteAll(disbursementsToDelete);
    }

    public LocalDateTime calculateNextDisbursementTime(Envelope envelope) {
        Budget budget = envelope.getBudget();

        if (budget == null || budget.getStatus() != BudgetStatus.ACTIVE) {
            return null;
        }

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        if (isBudgetPastEndDate(budget, now)) {
            return null;
        }

        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null || !conditions.containsKey("type")) return null;

        String type = (String) conditions.get("type");
        LocalDateTime last = envelope.getLastDisbursedAt() != null
                ? envelope.getLastDisbursedAt()
                : envelope.getCreatedAt();
        LocalDate budgetStart = envelope.getBudget().getStartDate();
        LocalDate budgetEnd = envelope.getBudget().getEndDate();

//        switch (type) {
//            case "daily":
//                String disbursementTime = (String) conditions.getOrDefault("disbursementTime", "00:00");
//                LocalTime time;
//                try {
//                    time = LocalTime.parse(disbursementTime);
//                } catch (DateTimeParseException e) {
//                    logger.error("Invalid disbursementTime format for daily envelope {}: {}, defaulting to 00:00", envelope.getId(), disbursementTime, e);
//                    time = LocalTime.of(0, 0);
//                }
//                LocalDateTime next = last.toLocalDate().atTime(time);
//                if (next.isBefore(now) || next.isBefore(budgetStart.atStartOfDay())) {
//                    next = (now.toLocalDate().isBefore(budgetStart) ? budgetStart : now.toLocalDate()).atTime(time);
//                    while (next.isBefore(now)) {
//                        next = next.plusDays(1);
//                    }
//                }
//                if (next.isAfter(budgetEnd.atTime(23, 59, 59))) {
//                    return null;
//                }
//                return next;
//
//            case "weekly":
//                LocalDate nextWeekStart = last.toLocalDate()
//                        .plusWeeks(1)
//                        .with(TemporalAdjusters.next(DayOfWeek.MONDAY));
//
//                LocalDateTime targetTime1 = nextWeekStart.atStartOfDay();
//
//                // 🛑 THE GHOST TRAIN FIX:
//                // If midnight has already passed today, force it to next Monday!
//                while (targetTime1.isBefore(now)) {
//                    targetTime1 = targetTime1.plusWeeks(1);
//                }
//
//                // Make sure we don't schedule past the budget end date
//                if (targetTime1.isAfter(budgetEnd.atTime(23, 59, 59))) {
//                    return null;
//                }
//
//                return targetTime1;
//
//            case "dynamic":
//                // 1. Safety Check
//                if (!conditions.containsKey("days") || !conditions.containsKey("disbursementTime")) {
//                    return null;
//                }
//
//                try {
//                    // 2. Get the target time (e.g., 1:00 PM)
//                    String timeStr = (String) conditions.get("disbursementTime");
//                    LocalTime targetTime = LocalTime.parse(timeStr);
//
//                    // 3. Get Allowed Days (e.g., [MONDAY, WEDNESDAY, FRIDAY])
//                    List<String> allowedDays = ((List<String>) conditions.get("days")).stream()
//                            .map(String::toUpperCase)
//                            .toList();
//
//                    // 4. Start checking from TODAY at the target time
//                    // Example: Wednesday Jan 28 @ 1:00 PM
//                    LocalDateTime candidate = now.toLocalDate().atTime(targetTime);
//
//                    // 5. THE FIX: If today's time has passed (11:21 PM > 1:00 PM),
//                    // effectively start looking from TOMORROW.
//                    if (candidate.isBefore(now)) {
//                        candidate = candidate.plusDays(1);
//                        // Now candidate is Thursday Jan 29 @ 1:00 PM
//                    }
//
//                    // 6. THE SEARCH LOOP (Find the next matching day)
//                    // We check up to 14 days into the future
//                    for (int i = 0; i < 14; i++) {
//                        String dayName = candidate.getDayOfWeek().name(); // e.g., "THURSDAY"
//
//                        // CHECK: Is "THURSDAY" in [MONDAY, WEDNESDAY, FRIDAY]?
//                        if (allowedDays.contains(dayName)) {
//
//                            // YES! We found a match (e.g., when loop reaches FRIDAY)
//
//                            // Check bounds (Budget Start/End)
//                            if (candidate.toLocalDate().isAfter(budgetEnd)) return null;
//                            if (candidate.toLocalDate().isBefore(budgetStart)) {
//                                candidate = candidate.plusDays(1);
//                                continue;
//                            }
//
//                            // Return this valid future time
//                            return candidate;
//                        }
//
//                        // NO: Thursday is NOT in the list.
//                        // So we add 1 day and loop again (Candidate becomes FRIDAY)
//                        candidate = candidate.plusDays(1);
//                    }
//                } catch (Exception e) {
//                    logger.error("Error calculating dynamic time for envelope {}", envelope.getId(), e);
//                }
//                return null;

        switch (type) {
            case "daily":
                String disbursementTime = (String) conditions.getOrDefault("disbursementTime", "00:00");
                LocalTime time;
                try {
                    time = LocalTime.parse(disbursementTime);
                } catch (DateTimeParseException e) {
                    logger.error("Invalid disbursementTime format for daily envelope {}: {}, defaulting to 00:00", envelope.getId(), disbursementTime, e);
                    time = LocalTime.of(0, 0);
                }
                LocalDateTime next = last.toLocalDate().atTime(time);

                // ✅ CHANGED TO !next.isAfter(now)
                if (!next.isAfter(now) || next.isBefore(budgetStart.atStartOfDay())) {
                    next = (now.toLocalDate().isBefore(budgetStart) ? budgetStart : now.toLocalDate()).atTime(time);
                    while (!next.isAfter(now)) {
                        next = next.plusDays(1);
                    }
                }
                if (next.isAfter(budgetEnd.atTime(23, 59, 59))) {
                    return null;
                }
                return next;

            case "weekly":
                LocalDate nextWeekStart = last.toLocalDate()
                        .plusWeeks(1)
                        .with(TemporalAdjusters.next(DayOfWeek.MONDAY));

                LocalDateTime targetTime1 = nextWeekStart.atStartOfDay();

                // ✅ CHANGED TO !targetTime1.isAfter(now)
                while (!targetTime1.isAfter(now)) {
                    targetTime1 = targetTime1.plusWeeks(1);
                }
                if (budgetStart != null && targetTime1.toLocalDate().isBefore(budgetStart)) {
                    targetTime1 = budgetStart.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atStartOfDay();
                    while (!targetTime1.isAfter(now)) {
                        targetTime1 = targetTime1.plusWeeks(1);
                    }
                }

                if (targetTime1.isAfter(budgetEnd.atTime(23, 59, 59))) {
                    return null;
                }
                return targetTime1;

            case "dynamic":
                if (!conditions.containsKey("days") || !conditions.containsKey("disbursementTime")) {
                    return null;
                }

                try {
                    String timeStr = (String) conditions.get("disbursementTime");
                    LocalTime targetTime = LocalTime.parse(timeStr);

                    List<String> allowedDays = ((List<String>) conditions.get("days")).stream()
                            .map(String::toUpperCase)
                            .toList();

                    LocalDate searchDate = now.toLocalDate();
                    if (budgetStart != null && searchDate.isBefore(budgetStart)) {
                        searchDate = budgetStart;
                    }
                    LocalDateTime candidate = searchDate.atTime(targetTime);

                    // ✅ CHANGED TO !candidate.isAfter(now)
                    if (!candidate.isAfter(now)) {
                        candidate = candidate.plusDays(1);
                    }

                    for (int i = 0; i < 14; i++) {
                        String dayName = candidate.getDayOfWeek().name();
                        if (allowedDays.contains(dayName)) {
                            if (candidate.toLocalDate().isAfter(budgetEnd)) return null;
                            if (candidate.toLocalDate().isBefore(budgetStart)) {
                                candidate = candidate.plusDays(1);
                                continue;
                            }
                            return candidate;
                        }
                        candidate = candidate.plusDays(1);
                    }
                } catch (Exception e) {
                    logger.error("Error calculating dynamic time for envelope {}", envelope.getId(), e);
                }
                return null;

            case "monthly": {
                LocalDate nextMonth = last.toLocalDate().plusMonths(1).withDayOfMonth(1);
                LocalDateTime target = nextMonth.atStartOfDay();
                while (!target.isAfter(now)) {
                    target = target.plusMonths(1).withDayOfMonth(1);
                }
                if (budgetStart != null && target.toLocalDate().isBefore(budgetStart)) {
                    target = budgetStart.withDayOfMonth(1).atStartOfDay();
                    if (!target.isAfter(now)) target = target.plusMonths(1).withDayOfMonth(1);
                }
                if (target.isAfter(budgetEnd.atTime(23, 59, 59))) return null;
                return target;
            }

            case "quarterly": {
                LocalDate lastDate = last.toLocalDate();
                int qMonth = ((lastDate.getMonthValue() - 1) / 3) * 3 + 1;
                LocalDate nextQ = LocalDate.of(lastDate.getYear(), qMonth, 1).plusMonths(3);
                LocalDateTime target = nextQ.atStartOfDay();
                while (!target.isAfter(now)) {
                    target = target.plusMonths(3);
                }
                if (target.isAfter(budgetEnd.atTime(23, 59, 59))) return null;
                return target;
            }

            case "biannual": {
                LocalDate lastDate = last.toLocalDate();
                int hStart = lastDate.getMonthValue() <= 6 ? 7 : 1;
                int hYear = lastDate.getMonthValue() <= 6 ? lastDate.getYear() : lastDate.getYear() + 1;
                LocalDateTime target = LocalDate.of(hYear, hStart, 1).atStartOfDay();
                while (!target.isAfter(now)) {
                    target = target.plusMonths(6);
                }
                if (target.isAfter(budgetEnd.atTime(23, 59, 59))) return null;
                return target;
            }

        case "safe_lock":
            case "strict_lock":
                if (conditions.containsKey("lockStartDate") && conditions.containsKey("lockDurationDays")) {
                    LocalDate lockStart = LocalDate.parse((String) conditions.get("lockStartDate"));
                    int lockDays = Integer.parseInt(conditions.get("lockDurationDays").toString());

                    // 👇 ADD THIS TO KILL THE GHOST TRAIN 👇
                    LocalDateTime unlockTime = lockStart.plusDays(lockDays).atStartOfDay();
                    if (unlockTime.isBefore(now)) {
                        return null; // The lock is already done. Kill the task.
                    }
                    return unlockTime;
                }
                return null;

            default:
                return null; // emergency or unsupported types
        }
    }

    private String formatAmount(Object obj) {
        if(obj == null) return "0";
        return obj.toString();
    }

    private OutboxEvent buildOutboxEvent(
            NotificationType type,
            Long userId,
            Long budgetId,
            Long envelopeId,
            Map<String, Object> payload
    ) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(type.name());
        event.setUserId(userId);
        event.setBudgetId(budgetId);
        event.setEnvelopeId(envelopeId);
        event.setPayload(payload);
        event.setStatus("PENDING");
        event.setRetryCount(0);
        event.setCreatedAt(LocalDateTime.now());
        event.setTtlSeconds(computeOutboxTtlSeconds(type));
        return event;
    }

    /**
     * Per-type TTL for outbox events (matches the FCM TTL strategy in NotificationService).
     * The outbox worker will mark an event STALE and skip delivery once
     * {@code createdAt + ttlSeconds < now}.
     *
     * <ul>
     *   <li>PRE_DISBURSEMENT / DISBURSEMENT_REMINDER → 45 min (highly time-sensitive nudge)</li>
     *   <li>DISBURSEMENT_SUCCESS / credits / transfers → 72 h  (financial record; must arrive)</li>
     *   <li>BUDGET_END_SOON / warnings                → 24 h  (informational)</li>
     *   <li>ENVELOPE_LOW_BALANCE / LOW_BALANCE         → 6 h   (actionable but not critical)</li>
     *   <li>null (default)                             → 24 h  (safe fallback)</li>
     * </ul>
     */
    private Long computeOutboxTtlSeconds(NotificationType type) {
        if (type == null) return 86_400L; // 24 h default
        return switch (type) {
            // Transient nudges — stale fast
            case PRE_DISBURSEMENT, DISBURSEMENT_REMINDER -> 2_700L; // 45 min

            // Financial events — never drop; hold for 72 h
            case DISBURSEMENT_SUCCESS, DISBURSEMENT_READY, DISBURSEMENT,
                 WALLET_FUNDED, WALLET_DEPOSIT, EXTERNAL_TRANSFER,
                 ENVELOPE_TRANSFER, REFUND_ISSUED, DISBURSEMENT_REFUNDED,
                 BUDGET_UNALLOCATED_REFUNDED, BUDGET_SCHEDULED,
                 BUDGET_ACTIVATED -> 259_200L; // 72 h

            // Important but not financial — 24 h
            case EXPIRED_DISBURSEMENT, DISBURSEMENT_FAILED,
                 BUDGET_END_SOON, BUDGET_ENDING_SOON, BUDGET_COMPLETED,
                 INSUFFICIENT_BALANCE, LIMIT_REACHED, BUDGET_LIMIT_WARNING -> 86_400L; // 24 h

            // Low-balance alerts — still useful within a few hours
            case LOW_BALANCE_WARNING, ENVELOPE_LOW_BALANCE -> 21_600L; // 6 h

            // Auto-transfer results — financial; hold for 72 h
            case AUTO_TRANSFER_SUCCESS -> 259_200L; // 72 h
            case AUTO_TRANSFER_FAILED, AUTO_TRANSFER_INSUFFICIENT_FUNDS -> 86_400L; // 24 h

            default -> 86_400L; // 24 h safe fallback
        };
    }

    private boolean isBudgetPastEndDate(Budget budget, LocalDateTime now) {
        if (budget == null || budget.getEndDate() == null) {
            return false;
        }

        return budget.getEndDate().isBefore(now.toLocalDate());
    }


    private BigDecimal calculateSafeRefundableAmount(Envelope envelope) {
        BigDecimal storedTotalRemaining = envelope.getTotalRemainingAmount() != null
                ? envelope.getTotalRemainingAmount()
                : BigDecimal.ZERO;

        BigDecimal storedHeldAmount = envelope.getHeldAmount() != null
                ? envelope.getHeldAmount()
                : BigDecimal.ZERO;

        BigDecimal storedAvailable = storedTotalRemaining.subtract(storedHeldAmount);

        if (storedAvailable.compareTo(BigDecimal.ZERO) < 0) {
            storedAvailable = BigDecimal.ZERO;
        }

        BigDecimal baseAllocation = envelope.getAmount() != null
                ? envelope.getAmount()
                : BigDecimal.ZERO;

        List<TransactionStatus> activeStatuses = List.of(
                TransactionStatus.COMPLETED,
                TransactionStatus.PROCESSING,
                TransactionStatus.PENDING
        );

        BigDecimal movedOutToOtherEnvelopes =
                transactionLogRepository.sumAbsAmountBySourceEnvelopeAndTypesAndStatuses(
                        envelope.getId(),
                        List.of(TransactionType.ENVELOPE_TO_ENVELOPE),
                        activeStatuses
                );

        BigDecimal movedIntoThisEnvelope =
                transactionLogRepository.sumAbsAmountByTargetEnvelopeAndTypesAndStatuses(
                        envelope.getId(),
                        List.of(TransactionType.ENVELOPE_TO_ENVELOPE),
                        activeStatuses
                );

        // NOTE: ENVELOPE_EXTERNAL_TRANSFER_FEE is intentionally excluded — transfer
        // fees are deducted from the wallet, not the envelope.  Including the FEE
        // companion log would understate the ledger balance and therefore reduce the
        // refundable amount at budget completion by the fee value.
        BigDecimal moneyThatLeftBudget =
                transactionLogRepository.sumAbsAmountBySourceEnvelopeAndTypesAndStatuses(
                        envelope.getId(),
                        List.of(
                                TransactionType.ENVELOPE_TO_EXTERNAL,
                                TransactionType.ENVELOPE_TO_USER
                        ),
                        activeStatuses
                );

        BigDecimal ledgerAvailable = baseAllocation
                .add(movedIntoThisEnvelope)
                .subtract(movedOutToOtherEnvelopes)
                .subtract(moneyThatLeftBudget);

        if (ledgerAvailable.compareTo(BigDecimal.ZERO) < 0) {
            ledgerAvailable = BigDecimal.ZERO;
        }

        BigDecimal refundableAmount = storedAvailable.min(ledgerAvailable);

        logger.info(
                "Budget completion refund calculation for envelope {}: storedAvailable={}, ledgerAvailable={}, finalRefundable={}",
                envelope.getId(),
                storedAvailable,
                ledgerAvailable,
                refundableAmount
        );

        return refundableAmount;
    }

    /**
     * Refunds each envelope's safe unused balance to the user's wallet and
     * returns the total refunded. Reuses the same
     * {@link #calculateSafeRefundableAmount} + wallet-credit + refund-log path
     * that runs at natural budget completion, so deleting a budget returns
     * allocated-but-unspent money instead of silently losing it (creating a
     * budget debits the wallet).
     *
     * <p>Also zeroes each envelope's balances (like natural completion), so the
     * caller can safely soft-delete the budget without the money being counted
     * in both the envelope and the wallet. Call only for funded (ACTIVE) budgets
     * — a DRAFT was never debited and a COMPLETED budget was already refunded at
     * completion, so refunding either would mint money.
     */
    @Transactional
    public BigDecimal refundUnusedBudgetBalance(Budget budget, User user) {
        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budget.getId());
        LocalDateTime now = LocalDateTime.now();
        BigDecimal totalRefunded = BigDecimal.ZERO;
        List<TransactionLog> logs = new ArrayList<>();

        for (Envelope envelope : envelopes) {
            scheduledTaskRepository.deleteByEnvelopeId(envelope.getId());

            BigDecimal refundable = calculateSafeRefundableAmount(envelope);
            if (refundable.compareTo(BigDecimal.ZERO) > 0) {
                TransactionLog refundLog = new TransactionLog();
                refundLog.setUserId(user.getId());
                refundLog.setBudgetId(budget.getId());
                refundLog.setSourceEnvelopeId(envelope.getId());
                refundLog.setAmount(refundable);
                refundLog.setTransactionType(BUDGET_COMPLETION_REFUND);
                refundLog.setStatus(COMPLETED);
                refundLog.setCreatedAt(now);
                refundLog.setReference("MW-REF-" + UUID.randomUUID());
                refundLog.setDescription("Unused envelope balance refunded on budget deletion");
                logs.add(refundLog);
                totalRefunded = totalRefunded.add(refundable);
            }
            // Empty the envelope — its money has moved to the wallet. Zeroing here
            // (as natural completion does) is what makes the caller's soft-delete
            // safe: otherwise the same balance would sit in BOTH the envelope and
            // the wallet, double-counting the money.
            envelope.setRemainingAmount(BigDecimal.ZERO);
            envelope.setTotalRemainingAmount(BigDecimal.ZERO);
            envelope.setHeldAmount(BigDecimal.ZERO);
            envelope.setNextDisbursementAt(null);
            envelope.setHasMatured(true);
        }
        envelopeRepository.saveAll(envelopes);

        if (totalRefunded.compareTo(BigDecimal.ZERO) > 0) {
            transactionLogRepository.saveAll(logs);
            walletService.fundWallet(
                    user.getId(),
                    totalRefunded,
                    String.format("Refund of ₦%,.2f from deleted budget: %s",
                            totalRefunded, budget.getName()),
                    false
            );
        }

        try {
            feeReserveService.releaseForBudget(user.getId(), budget.getId());
        } catch (Exception e) {
            logger.warn("[FeeReserve] Failed to release reserve for cancelled budget {} — non-blocking",
                    budget.getId(), e);
        }

        return totalRefunded;
    }

}
