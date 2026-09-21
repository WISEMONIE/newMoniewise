package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.config.BudgetLifeCycleManager;
import com.moniewise.moniewise_backend.config.GenericNotificationEvent;
import com.moniewise.moniewise_backend.dto.request.BudgetRequest;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.BudgetResponse;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.exception.InsufficientFundsException;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.*;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.moniewise.moniewise_backend.enums.TransactionType.*;

@Service
public class BudgetService {

    private final EnvelopeRepository envelopeRepository;
    private final BudgetRepository budgetRepository;
    private final RevenueLogRepository revenueLogRepository;

    private final WalletRepository walletRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionLogRepository transactionLogRepository;
    private final NotificationService notificationService;
    private final ActivationJourneyNudgeService activationJourneyNudgeService;
    private final WalletService walletService;

    private final ScheduledTaskRepository scheduledTaskRepository;
    private final EnvelopeService envelopeService;

    private final BudgetLifeCycleManager budgetLifeCycleManager;

    private final ApplicationEventPublisher eventPublisher;

    private final SavingsService savingsService;
    private final SavingsGoalRepository savingsGoalRepository;

    private final SystemConfigService systemConfig;
    private final MonnieCacheInvalidationService monnieCacheInvalidationService;
    private final RedisTemplate<String, String> redisTemplate;

    /** Short-lived cache for read-heavy GET /budgets/{id}/envelopes calls. */
    private static final String ENVELOPES_CACHE_PREFIX = "envelopes:";
    private static final long ENVELOPES_CACHE_TTL_SECS = 30;
    private static final int MAX_BUDGET_START_YEARS = 2;

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    public BudgetService(
            EnvelopeRepository envelopeRepository,
            BudgetRepository budgetRepository,
            RevenueLogRepository revenueLogRepository,
            WalletRepository walletRepository, UserService userService,
            TransactionLogRepository transactionLogRepository,
            NotificationService notificationService,
            ActivationJourneyNudgeService activationJourneyNudgeService,
            WalletService walletService, ScheduledTaskRepository scheduledTaskRepository,
            @Lazy EnvelopeService envelopeService, BudgetLifeCycleManager budgetLifeCycleManager,
            ApplicationEventPublisher eventPublisher, SavingsService savingsService,
            SavingsGoalRepository savingsGoalRepository,
            SystemConfigService systemConfig,
            MonnieCacheInvalidationService monnieCacheInvalidationService,
            RedisTemplate<String, String> redisTemplate) {
        this.envelopeRepository = envelopeRepository;
        this.budgetRepository = budgetRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.walletRepository = walletRepository;
        this.userService = userService;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.activationJourneyNudgeService = activationJourneyNudgeService;
        this.walletService = walletService;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.envelopeService = envelopeService;
        this.budgetLifeCycleManager = budgetLifeCycleManager;
        this.eventPublisher = eventPublisher;
        this.savingsService = savingsService;
        this.savingsGoalRepository = savingsGoalRepository;
        this.systemConfig = systemConfig;
        this.monnieCacheInvalidationService = monnieCacheInvalidationService;
        this.redisTemplate = redisTemplate;
    }

    private static final Logger logger = LoggerFactory.getLogger(BudgetService.class);
    private static final String LATEST_TNC_VERSION = "2.0";
    private static final String LATEST_TNC_CONTENT = "MonieWise helps you budget... (your terms here)";

    @PostConstruct
    public void init() {
        System.out.println("Revenue Wallet User ID: " + revenueWalletUserId);
    }

    private LocalDateTime fetchCurrentDateTimeFromDatabase() {
        return ZonedDateTime.now(ZoneId.of("Africa/Lagos")).toLocalDateTime();
    }

    private void normalizeAndValidateBudgetDates(BudgetRequest request, LocalDate today) {
        if (request.getStartDate() == null) {
            request.setStartDate(today);
        }
        if (request.getEndDate() == null) {
            throw new IllegalArgumentException("End date is required");
        }

        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();

        if (startDate.isBefore(today)) {
            throw new IllegalArgumentException("Budget start date cannot be in the past");
        }
        if (startDate.isAfter(today.plusYears(MAX_BUDGET_START_YEARS))) {
            throw new IllegalArgumentException("Budget start date cannot be more than 2 years from today");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Budget start date cannot be after end date");
        }
    }

    private BudgetStatus resolveInitialBudgetStatus(BudgetRequest request, LocalDate today) {
        if (request.getStartDate().isAfter(today)) {
            return BudgetStatus.SCHEDULED;
        }

        BudgetStatus requestedStatus = request.getStatus();
        if (requestedStatus == null || requestedStatus == BudgetStatus.SCHEDULED || requestedStatus == BudgetStatus.ACTIVE) {
            return BudgetStatus.ACTIVE;
        }

        throw new IllegalArgumentException("Budget status must be ACTIVE for creation");
    }

    private boolean isFundedBudgetStatus(BudgetStatus status) {
        return status == BudgetStatus.ACTIVE || status == BudgetStatus.SCHEDULED;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewBudgetFunding(BudgetRequest request, String email) {
        User user = userService.findByEmail(email);
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        normalizeAndValidateBudgetDates(request, now.toLocalDate());
        validateSavingsSweepTargets(request, user);
        validatePositiveBudgetTotal(request);
        validateMinimumBudgetAmount(request);

        long durationDays = calculateBudgetDurationDays(request);
        validateBudgetDuration(durationDays);
        validateEnvelopeCount(request);

        BigDecimal originalAmount = request.getTotalAmount();
        BigDecimal creationFee = calculateBudgetCreationFee(durationDays, user.getId()).setScale(2, RoundingMode.HALF_UP);
        Map<EnvelopeRequest, BigDecimal> finalAmounts = calculateFinalEnvelopeAmounts(request, originalAmount);
        validateEnvelopePercentages(request);

        BigDecimal envelopeTotal = finalAmounts.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalNeeded = envelopeTotal.add(creationFee).setScale(2, RoundingMode.HALF_UP);
        BigDecimal walletBalance = walletService.checkBalance(user.getId()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal shortfall = totalNeeded.subtract(walletBalance)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> details = budgetFundingDetails(
                envelopeTotal,
                creationFee,
                totalNeeded,
                walletBalance,
                shortfall,
                durationDays
        );
        details.put("budgetAmount", originalAmount.setScale(2, RoundingMode.HALF_UP));
        details.put("canCreateBudget", walletBalance.compareTo(totalNeeded) >= 0);
        return details;
    }

    private void validatePositiveBudgetTotal(BudgetRequest request) {
        if (request.getTotalAmount() == null || request.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Budget amount must be positive");
        }
    }

    private void validateMinimumBudgetAmount(BudgetRequest request) {
        BigDecimal minAmount = systemConfig.getBudgetMinAmount();
        if (request.getTotalAmount().compareTo(minAmount) < 0) {
            throw new IllegalArgumentException(
                    String.format(Locale.US, "Minimum budget amount is ₦%,.2f", minAmount));
        }
    }

    private long calculateBudgetDurationDays(BudgetRequest request) {
        long durationDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        return durationDays <= 0 ? 1 : durationDays;
    }

    private void validateBudgetDuration(long durationDays) {
        int maxDurationDays = systemConfig.getInt(SystemConfigService.BUDGET_MAX_DURATION_DAYS, 730);
        if (durationDays > maxDurationDays) {
            throw new IllegalArgumentException(
                    "Budget duration cannot exceed " + maxDurationDays + " days");
        }
    }

    private void validateEnvelopeCount(BudgetRequest request) {
        int minEnvelopes = systemConfig.getInt(SystemConfigService.BUDGET_MIN_ENVELOPES, 1);
        int maxEnvelopes = systemConfig.getInt(SystemConfigService.BUDGET_MAX_ENVELOPES, 15);
        int envelopeCount = request.getEnvelopes() == null ? 0 : request.getEnvelopes().size();

        if (envelopeCount < minEnvelopes) {
            throw new IllegalArgumentException(
                    "A budget needs at least " + minEnvelopes + " envelopes to be meaningful.");
        }
        if (envelopeCount > maxEnvelopes) {
            throw new IllegalArgumentException(
                    "Maximum " + maxEnvelopes + " envelopes per budget. " +
                            "More than that makes budgets harder to stick to.");
        }
    }

    private BigDecimal calculateBudgetCreationFee(long durationDays, Long userId) {
        if (budgetRepository.countByUserId(userId) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal baseFee = systemConfig.getBigDecimal(SystemConfigService.BUDGET_CREATION_FEE, BigDecimal.ZERO);
        if (baseFee.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return baseFee.multiply(BigDecimal.valueOf(calculateBudgetCreationFeeIntervals(durationDays)));
    }

    private int calculateBudgetCreationFeeIntervals(long durationDays) {
        return (int) Math.ceil((double) Math.max(1, durationDays) / 30);
    }

    private Map<EnvelopeRequest, BigDecimal> calculateFinalEnvelopeAmounts(
            BudgetRequest request,
            BigDecimal originalAmount) {
        List<EnvelopeRequest> envelopeRequests = request.getEnvelopes();
        Map<EnvelopeRequest, BigDecimal> finalAmounts = new LinkedHashMap<>();
        BigDecimal sumOfRoundedAmounts = BigDecimal.ZERO;

        long emergencyCount = envelopeRequests.stream()
                .filter(e -> {
                    Map<String, Object> conditions = e.getConditions();
                    Object type = conditions != null ? conditions.get("type") : null;
                    return "emergency".equalsIgnoreCase(type != null ? type.toString() : "");
                })
                .count();

        if (emergencyCount > 1) {
            throw new IllegalArgumentException("You can only have ONE 'Emergency' envelope per budget.");
        }

        for (EnvelopeRequest env : envelopeRequests) {
            BigDecimal rounded;
            BigDecimal exact = env.getExactAmount();
            if (exact != null && exact.compareTo(BigDecimal.ZERO) > 0) {
                rounded = exact.setScale(2, RoundingMode.HALF_UP);
            } else {
                BigDecimal calculated = originalAmount
                        .multiply(env.getPercentage())
                        .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP);
                rounded = calculated.setScale(2, RoundingMode.HALF_UP);
            }
            finalAmounts.put(env, rounded);
            sumOfRoundedAmounts = sumOfRoundedAmounts.add(rounded);
        }

        BigDecimal roundingError = originalAmount.subtract(sumOfRoundedAmounts);
        if (roundingError.compareTo(BigDecimal.ZERO) != 0) {
            EnvelopeRequest largest = envelopeRequests.stream()
                    .max(Comparator.comparing(EnvelopeRequest::getPercentage))
                    .orElse(envelopeRequests.get(0));

            BigDecimal oldAmount = finalAmounts.get(largest);
            finalAmounts.put(largest, oldAmount.add(roundingError));
        }
        return finalAmounts;
    }

    private void validateEnvelopePercentages(BudgetRequest request) {
        BigDecimal totalPercentage = request.getEnvelopes().stream()
                .map(EnvelopeRequest::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPercentage.compareTo(new BigDecimal("50")) < 0) {
            throw new IllegalArgumentException("Envelope percentages must sum to at least 50%");
        }
        if (totalPercentage.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Envelope percentages cannot exceed 100%");
        }
    }

    private Map<String, Object> budgetFundingDetails(
            BigDecimal envelopeTotal,
            BigDecimal creationFee,
            BigDecimal totalNeeded,
            BigDecimal walletBalance,
            BigDecimal shortfall,
            long durationDays) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currency", "NGN");
        details.put("durationDays", durationDays);
        details.put("feeIntervals", calculateBudgetCreationFeeIntervals(durationDays));
        details.put("envelopeTotal", envelopeTotal);
        details.put("allocationTotal", envelopeTotal);
        details.put("creationFee", creationFee);
        details.put("budgetCreationFee", creationFee);
        details.put("feeAmount", creationFee);
        details.put("totalNeeded", totalNeeded);
        details.put("totalRequired", totalNeeded);
        details.put("requiredAmount", totalNeeded);
        details.put("walletBalance", walletBalance);
        details.put("shortfall", shortfall);
        details.put("amountToFund", shortfall);
        return details;
    }

    private void validateSavingsSweepTargets(BudgetRequest request, User user) {
        if (request.getEnvelopes() == null) {
            return;
        }

        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
            Map<String, Object> conditions = envelopeRequest.getConditions();
            if (conditions == null || !"savings_sweep".equalsIgnoreCase(String.valueOf(conditions.get("type")))) {
                continue;
            }

            Long targetSavingsGoalId = parseLong(conditions.get("targetSavingsGoalId"));
            if (targetSavingsGoalId == null) {
                throw new IllegalArgumentException("'" + envelopeRequest.getName() + "' must select a valid savings pot.");
            }

            SavingsGoal goal = savingsGoalRepository.findById(targetSavingsGoalId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "'" + envelopeRequest.getName() + "' points to a savings pot that no longer exists."));

            if (goal.getUser() == null || goal.getUser().getId() == null || !goal.getUser().getId().equals(user.getId())) {
                throw new SecurityException("'" + envelopeRequest.getName() + "' points to a savings pot outside your account.");
            }
            if (goal.getStatus() != SavingsStatus.ACTIVE) {
                throw new IllegalArgumentException("'" + envelopeRequest.getName() + "' cannot sweep into an inactive savings pot.");
            }
            if (goal.getMaturityDate() == null || !request.getStartDate().isBefore(goal.getMaturityDate())) {
                throw new IllegalArgumentException("'" + envelopeRequest.getName()
                        + "' cannot sweep into '" + goal.getName()
                        + "' because that savings pot matures on " + goal.getMaturityDate()
                        + ". Choose a savings pot that matures after the budget start date, or remove the sweep.");
            }
        }
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

    // In BudgetService.java, replace lines 118–170 with:
//    @Transactional
//    public BudgetResponse createBudget(BudgetRequest request, String email) {
//        User user = userService.findByEmail(email);
//        logger.debug("Starting budget creation for {}", email);
//        logger.debug("User found: {}", user.getId());
//
//        // 0. CHECK ACTIVE BUDGET LIMIT (Max 5 Concurrent)
//        List<Budget> userBudgets = budgetRepository.findByUserId(user.getId());
//        long activeBudgetCount = userBudgets.stream()
//                .filter(b -> b.getStatus() == BudgetStatus.ACTIVE)
//                .count();
//
//        if (activeBudgetCount >= 10) {
//            throw new IllegalStateException("Limit reached: You can have a maximum of 10 active budgets. Please complete or delete an existing budget to create a new one.");
//        }
//        // 👆 END INSERT 👆
//
//        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
//
//        // Validate dates
//        if (request.getStartDate().isAfter(request.getEndDate())) {
//            throw new IllegalArgumentException("Start date must be before end date");
//        }
//
//        // 👇 ADD THIS BLOCK HERE 👇
//        BigDecimal minAmount = new BigDecimal("5000");
//        if (request.getTotalAmount().compareTo(minAmount) < 0) {
//            // Throw clearer error for UI to display
//            throw new IllegalArgumentException("Minimum budget amount is ₦5,000.00");
//        }
//        // 👆 END INSERT 👆
//
//        // 2. 🛑 MAXIMUM CHECK (₦50,000,000)
//        // Best Practice: Prevent integer overflows, UI breaks, and extreme laundering attempts.
//        BigDecimal maxAmount = new BigDecimal("50000000");
//
//        long durationDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate());
//        if (durationDays <= 0) durationDays = 1;
//
//        // Validate duration
//        if (durationDays > 90) {
//            notificationService.sendNotification(user.getId().toString(),
//                    "Budget creation failed: Duration cannot exceed 90 days.", NotificationType.BUDGET_CREATION);
//            throw new IllegalArgumentException("Budget duration must be between 1 and 90 days");
//        }
//
//        // Calculate fee and budget amounts
//        int feeIntervals = (int) Math.ceil((double) durationDays / 30);
//        BigDecimal fee = new BigDecimal("200").multiply(BigDecimal.valueOf(feeIntervals));
//        BigDecimal originalAmount = request.getTotalAmount();
//
//        BigDecimal actualBudgetAmount = originalAmount.subtract(fee);
//
//        // === ADD THIS NEW BLOCK (Option 3 implementation) ===
//        List<EnvelopeRequest> envelopeRequests = request.getEnvelopes();
//        Map<EnvelopeRequest, BigDecimal> finalAmounts = new LinkedHashMap<>();
//
//        BigDecimal sumOfRoundedAmounts = BigDecimal.ZERO;
//
//        long emergencyCount = request.getEnvelopes().stream()
//                .filter(e -> "emergency".equalsIgnoreCase((String) e.getConditions().getOrDefault("type", "")))
//                .count();
//
//        if (emergencyCount > 1) {
//            throw new IllegalArgumentException("Strict Rule: You can only have ONE 'Emergency' envelope per budget. Use Standard envelopes for specific savings (e.g., 'Car Repair').");
//        }
//
//        for (EnvelopeRequest env : envelopeRequests) {
//            BigDecimal percentage = env.getPercentage();
//            BigDecimal calculated = actualBudgetAmount
//                    .multiply(percentage)
//                    .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP); // keep precision
//
//            BigDecimal rounded = calculated.setScale(2, RoundingMode.HALF_UP);
//            finalAmounts.put(env, rounded);
//            sumOfRoundedAmounts = sumOfRoundedAmounts.add(rounded);
//        }
//
//        // Calculate the rounding error (usually between -0.99 and +0.99)
//        BigDecimal roundingError = actualBudgetAmount.subtract(sumOfRoundedAmounts);
//
//        // Distribute the error to the largest envelope(s) – this makes sum EXACT
//        if (roundingError.compareTo(BigDecimal.ZERO) != 0) {
//            // Strategy: give all the difference to the envelope with highest percentage
//            EnvelopeRequest largest = envelopeRequests.stream()
//                    .max(Comparator.comparing(EnvelopeRequest::getPercentage))
//                    .orElse(envelopeRequests.get(0));
//
//            BigDecimal oldAmount = finalAmounts.get(largest);
//            BigDecimal newAmount = oldAmount.add(roundingError);
//            finalAmounts.put(largest, newAmount);
//
//            logger.info("Adjusted envelope '{}' by ₦{} due to rounding. New amount: ₦{}",
//                    largest.getName(), roundingError, newAmount);
//        }
//
//        // Validate envelope percentages
//        BigDecimal totalPercentage = request.getEnvelopes().stream()
//                .map(EnvelopeRequest::getPercentage)
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//        if (totalPercentage.compareTo(new BigDecimal("50")) < 0) {
//            throw new IllegalArgumentException("Envelope percentages must sum to at least 50%");
//        }
//        if (totalPercentage.compareTo(new BigDecimal("100")) > 0) {
//            throw new IllegalArgumentException("Envelope percentages cannot exceed 100%");
//        }
//
//        // Calculate allocation sum
//        BigDecimal allocationSum = request.getEnvelopes().stream()
//                .map(envelope -> actualBudgetAmount.multiply(envelope.getPercentage())
//                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP))
//                .reduce(BigDecimal.ZERO, BigDecimal::add);
//
//
//        // Validate allocation
//        BigDecimal minimumAllocation = actualBudgetAmount.multiply(new BigDecimal("50"))
//                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
//        if (allocationSum.compareTo(minimumAllocation) < 0) {
//            throw new IllegalStateException(
//                    "Envelope allocations (₦" + allocationSum + ") must be at least 50% of budget total (₦" + minimumAllocation + ")"
//            );
//        }
//        BigDecimal tolerance = new BigDecimal("0.01");
//        if (allocationSum.compareTo(actualBudgetAmount) > 0 &&
//                allocationSum.subtract(actualBudgetAmount).abs().compareTo(tolerance) > 0) {
//            throw new IllegalStateException(
//                    "Envelope allocations (₦" + allocationSum + ") cannot exceed budget total (₦" + actualBudgetAmount + ")"
//            );
//        }
//
//        // ADD THIS LOOP: VALIDATE EACH ENVELOPE LIMIT
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//            BigDecimal envelopeAmount = finalAmounts.get(envelopeRequest); // ← THIS IS NOW GUARANTEED TO SUM CORRECTLY
//
//            validateEnvelopeLimit(envelopeRequest, envelopeAmount, request.getStartDate(), request.getEndDate());
//        }
//
//        // Check wallet balance for allocation sum
//        BigDecimal walletBalance = walletService.checkBalance(user.getId());
//        if (walletBalance.compareTo(allocationSum) < 0) {
//            String message = String.format(
//                    "Transaction failed: Your wallet has insufficient funds. At least ₦%.2f is required, but you have ₦%.2f.",
//                    allocationSum, walletBalance
//            );
//
//            // ❌ DO NOT PUBLISH SUCCESS EVENT HERE
//            // ✅ DO THROW THE EXCEPTION
//            throw new IllegalArgumentException(message);
//        }
//        // Charge the fee
//        deductBudgetCreationFee(user.getId());
//
//        // Create and save budget entity
//        Budget budget = new Budget();
//        budget.setUser(user);
//        budget.setName(request.getName());
//        budget.setOriginalAmount(originalAmount);
//        budget.setFeeAmount(fee);
//        budget.setTotalAmount(actualBudgetAmount);
//        budget.setAllocatedAmount(allocationSum);
//        budget.setStartDate(request.getStartDate());
//        budget.setEndDate(request.getEndDate());
//        budget.setDurationDays((int) durationDays);
//        budget.setStatus(request.getStatus());
//        budget.setCreatedAt(now);
//        budget.setRemainingAmount(request.getTotalAmount().subtract(budget.getFeeAmount()));
//        Budget savedBudget = budgetRepository.save(budget);
//
//        // Create envelopes using EnvelopeService
//        List<Envelope> envelopes = new ArrayList<>();
//        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
//
//            BigDecimal correctAmount = finalAmounts.get(envelopeRequest);
//            envelopeRequest.setExactAmount(correctAmount);
//
//            envelopeRequest.setBudgetId(savedBudget.getId()); // Set budget ID
//
//            EnvelopeResponse envelopeResponse = envelopeService.createEnvelope(envelopeRequest, email, true);
//            Envelope envelope = envelopeRepository.findById(envelopeResponse.getId())
//                    .orElseThrow(() -> new IllegalStateException("Failed to retrieve created envelope"));
//            // 🟢 THE SAVINGS SWEEP INTERCEPTOR 🟢
//            Map<String, Object> conditions = envelope.getConditions();
//            if (conditions != null && "savings_sweep".equalsIgnoreCase((String) conditions.getOrDefault("type", ""))) {
//                try {
//                    Long targetSavingsId = Long.valueOf(conditions.get("targetSavingsGoalId").toString());
//
//                    // 1. Send the money to the Pot!
//                    savingsService.sweepEnvelopeToSavings(user.getId(), targetSavingsId, correctAmount, envelope.getName());
//
//                    // 2. Turn this Envelope into an empty "Receipt"
//                    envelope.setRemainingAmount(BigDecimal.ZERO);
//                    envelope.setTotalRemainingAmount(BigDecimal.ZERO);
//                    envelope.setHasMatured(true);
//                    envelopeRepository.save(envelope);
//                } catch (Exception e) {
//                    logger.error("Failed to sweep envelope to savings for user {}", user.getId(), e);
//                    throw new IllegalStateException("Failed to process savings sweep for envelope: " + envelope.getName());
//                }
//            }
//
//            envelopes.add(envelope);
//        }
//
//        savedBudget.clearEnvelopes();
//        savedBudget.addAllEnvelopes(envelopes);
//
//        // Deduct allocation from user wallet
//        walletService.deductBalance(user.getId(), allocationSum);
//
//        // Transfer fee to revenue wallet
////        walletService.fundWallet(revenueWalletUserId, fee,
////                String.format("₦%.2f received as budget creation fee.", fee));
//
//        Wallet revenueWallet = walletRepository.findByIsRevenueWalletTrue()
//                .orElseThrow();
//        revenueWallet.setBalance(revenueWallet.getBalance().add(fee));
//        walletRepository.save(revenueWallet);
//
//        // Refund unallocated amount
////        BigDecimal unallocatedAmount = actualBudgetAmount.subtract(allocationSum);
////        if (unallocatedAmount.compareTo(BigDecimal.ZERO) > 0) {
////            walletService.fundWallet(user.getId(), unallocatedAmount,
////                    String.format("₦%.2f refunded to wallet from unallocated budget funds.", unallocatedAmount));
////            TransactionLog refundLog = new TransactionLog();
////            refundLog.setUserId(user.getId());
////            refundLog.setBudgetId(savedBudget.getId());
////            refundLog.setAmount(unallocatedAmount);
////            refundLog.setTransactionType(BUDGET_UNALLOCATED_REFUNDED);
////            refundLog.setStatus(TransactionStatus.SUCCESS);
////            refundLog.setCreatedAt(now);
////
////            // 👇 ADD THIS LINE (Generate a unique reference)
////            refundLog.setReference("REF-" + System.currentTimeMillis() + "-" + user.getId());
////
////            transactionLogRepository.save(refundLog);
////        }
//
//        // ——————— TRANSACTION LOGS ———————
//        // 1. Budget allocation deduction
//        TransactionLog allocationLog = new TransactionLog();
//        allocationLog.setUserId(user.getId());
//        allocationLog.setBudgetId(savedBudget.getId());
//        allocationLog.setAmount(allocationSum.negate());  // Negative = money left wallet
//        allocationLog.setFee(fee);
//        allocationLog.setTransactionType(BUDGET_ALLOCATION);
//        // 👇 ADD THIS
//        allocationLog.setReference("BUD-ALL-" + savedBudget.getId() + "-" + System.currentTimeMillis());
//
//        allocationLog.setDescription("Allocated to budget envelopes");
//        allocationLog.setStatus(TransactionStatus.COMPLETED);
//        allocationLog.setCreatedAt(now);
//        transactionLogRepository.save(allocationLog);
//
//        // 2. Budget creation fee deduction
//        TransactionLog feeLog = new TransactionLog();
//        feeLog.setUserId(user.getId());
//        feeLog.setBudgetId(savedBudget.getId());
//        feeLog.setAmount(fee.negate());  // ← NEGATIVE = deduction
//        feeLog.setFee(BigDecimal.ZERO);
//        feeLog.setTransactionType(BUDGET_CREATION_FEE);
//        // 👇 ADD THIS
//        feeLog.setReference("BUD-FEE-" + savedBudget.getId() + "-" + System.currentTimeMillis());
//
//        feeLog.setDescription("Budget creation fee");
//        feeLog.setStatus(TransactionStatus.COMPLETED);
//        feeLog.setCreatedAt(now);
//        transactionLogRepository.save(feeLog);
//
//        // ——————— REVENUE LOG ———————
//        RevenueLog revenueLog = new RevenueLog();
//
//        // ✅ GOOD: Uses the actual ID from the database wallet we fetched earlier
//        revenueLog.setUserId(revenueWallet.getUser().getId());
//
////        revenueLog.setUserId(revenueWalletUserId);
//        revenueLog.setType("budget_creation_fee");
//        revenueLog.setAmount(fee);
//        revenueLog.setDescription("Budget fee for " + durationDays + " days");
//        revenueLog.setCreatedAt(now);
//        revenueLogRepository.save(revenueLog);
//
//        // ——————— NOTIFICATION ———————
////        String message = String.format(
////                "Budget '%s' created successfully! " +
////                        "₦%.2f allocated • ₦%.2f fee deducted%s",
////                savedBudget.getName(),
////                allocationSum,
////                fee,
////                unallocatedAmount.compareTo(BigDecimal.ZERO) > 0
////                        ? " • ₦" + unallocatedAmount + " refunded to wallet"
////                        : ""
////        );
////
////        notificationService.sendNotification(
////                user.getId().toString(),
////                message,
////                NotificationType.BUDGET_CREATION,
////                savedBudget.getId(),
////                null,
////                "VIEW_BUDGET",                                      // <--- The Command
////                "/budgets/" + budget.getId() + "/envelopes" // Navigation URL
////        );
//
//        // 🛑 FIXED: PUBLISH SUCCESS EVENT HERE (AT THE VERY END)
////        Map<String, Object> params = new HashMap<>();
////        params.put("budgetName", savedBudget.getName());
////        params.put("allocated", String.format("%,.2f", allocationSum));
////        params.put("fee", String.format("%,.2f", fee));
////
//////        if (unallocatedAmount.compareTo(BigDecimal.ZERO) > 0) {
//////            params.put("refunded", String.format("%,.2f", unallocatedAmount));
//////        }
////
////        // Publish the event!
////        eventPublisher.publishEvent(new GenericNotificationEvent(
////                this,
////                user.getId().toString(),
////                NotificationType.BUDGET_CREATION,
////                params,
////                savedBudget.getId(),
////                null,
////                "/budgets/" + savedBudget.getId()
////        ));
//
//        Map<String, Object> params = new HashMap<>();
//        params.put("budgetName", budget.getName());
//        params.put("allocated", budget.getTotalAmount());
//        params.put("fee", fee);
//        params.put("envelopeCount", request.getEnvelopes().size());
//
//        eventPublisher.publishEvent(new GenericNotificationEvent(
//                this, user.getId().toString(), NotificationType.BUDGET_CREATION,
//                params, budget.getId(), null, "/budgets/" + budget.getId()
//        ));
//
//        return new BudgetResponse(
//                savedBudget.getId(),
//                savedBudget.getName(),
//                savedBudget.getTotalAmount(),
//                savedBudget.getAllocatedAmount(),
//                savedBudget.getDurationDays(),
//                savedBudget.getStartDate(),
//                savedBudget.getEndDate(),
//                savedBudget.getStatus(),
//                savedBudget.getCreatedAt(),
//                user.getId(),
//                savedBudget.getLastTopupTime(),
//                savedBudget.getEnvelopes().stream()
//                        .map(e -> new EnvelopeResponse(
//                                e.getId(),
//                                savedBudget.getId(),
//                                e.getName(),
//                                e.getAmount(),
//                                e.getRemainingAmount(),
//
//                                e.getAmount(),
//                                e.getTotalRemainingAmount(),
//                                e.getRemainingAmount(),
////                                getLimitFromConditions(e),
//                                calculatePeriodLimit(e, savedBudget),
//                                getUsedThisPeriod(e),
//
//                                e.getConditions(),
//                                e.getCreatedAt(),
//                                e.getLastDisbursedAt(),
//                                e.getNextDisbursementAt()
//                        ))
//                        .collect(Collectors.toList()),
//                savedBudget.getOriginalAmount(),
//                savedBudget.getFeeAmount()
//        );
//    }


    @Transactional
    public BudgetResponse createBudget(BudgetRequest request, String email) {
        User user = userService.findByEmail(email);
        logger.debug("Starting budget creation for {}", email);

        if (request.getTermsAccepted() != null && request.getTermsAccepted()
                && !Boolean.TRUE.equals(user.getTncAccepted())) {
            userService.acceptTnc(email, true);
        }

        // 0. CHECK ACTIVE BUDGET LIMIT (Max 10)
        List<Budget> userBudgets = budgetRepository.findByUserId(user.getId());
        long fundedBudgetCount = userBudgets.stream()
                .filter(b -> isFundedBudgetStatus(b.getStatus()))
                .count();

        if (fundedBudgetCount >= 10) {
            throw new IllegalStateException("Limit reached: You can have a maximum of 10 active or scheduled budgets.");
        }

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();

        normalizeAndValidateBudgetDates(request, now.toLocalDate());
        BudgetStatus initialStatus = resolveInitialBudgetStatus(request, now.toLocalDate());
        validateSavingsSweepTargets(request, user);

        // Minimum budget amount is product-configurable via system_config.
        validatePositiveBudgetTotal(request);
        BigDecimal minAmount = systemConfig.getBudgetMinAmount();
        if (request.getTotalAmount().compareTo(minAmount) < 0) {
            throw new IllegalArgumentException(
                    String.format(Locale.US, "Minimum budget amount is ₦%,.2f", minAmount));
        }

        // Duration — read max from system_config so it can be changed without a deploy.
        // Default 730 days (2 years) supports goal budgets and annual savings plans.
        // +1: the Flutter date picker counts the start day as day 1 (today -> tomorrow
        // = 2 days), but ChronoUnit.DAYS.between() is exclusive (1 day) - without the
        // +1 this disagreed with what the user picked and under-counted the fee.
        long durationDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        if (durationDays <= 0) durationDays = 1;

        int maxDurationDays = systemConfig.getInt(SystemConfigService.BUDGET_MAX_DURATION_DAYS, 730);
        if (durationDays > maxDurationDays) {
            throw new IllegalArgumentException(
                    "Budget duration cannot exceed " + maxDurationDays + " days");
        }

        // Envelope count — server-side guard matching frontend soft/hard limits
        int minEnvelopes = systemConfig.getInt(SystemConfigService.BUDGET_MIN_ENVELOPES, 1);
        int maxEnvelopes = systemConfig.getInt(SystemConfigService.BUDGET_MAX_ENVELOPES, 15);
        int envelopeCount = request.getEnvelopes() == null ? 0 : request.getEnvelopes().size();

        if (envelopeCount < minEnvelopes) {
            throw new IllegalArgumentException(
                    "A budget needs at least " + minEnvelopes + " envelopes to be meaningful.");
        }
        if (envelopeCount > maxEnvelopes) {
            throw new IllegalArgumentException(
                    "Maximum " + maxEnvelopes + " envelopes per budget. " +
                    "More than that makes budgets harder to stick to.");
        }

        // === CALCULATE FEE (read from system_config — never hardcoded) ===
        // budget.creation.fee = 0 means no fee charged. Positive value = fee per 30-day interval.
        BigDecimal fee = calculateBudgetCreationFee(durationDays, user.getId());
        BigDecimal originalAmount = request.getTotalAmount();   // This is what goes to envelopes

        // === ENVELOPE PROCESSING & ROUNDING (unchanged logic) ===
        List<EnvelopeRequest> envelopeRequests = request.getEnvelopes();
        Map<EnvelopeRequest, BigDecimal> finalAmounts = calculateFinalEnvelopeAmounts(request, originalAmount);

        // Validate percentages
        BigDecimal totalPercentage = request.getEnvelopes().stream()
                .map(EnvelopeRequest::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPercentage.compareTo(new BigDecimal("50")) < 0) {
            throw new IllegalArgumentException("Envelope percentages must sum to at least 50%");
        }
        if (totalPercentage.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Envelope percentages cannot exceed 100%");
        }

        BigDecimal allocationSum = finalAmounts.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // === KEY CHANGE: SINGLE BALANCE CHECK FOR BUDGET + FEE ===
        BigDecimal totalRequired = allocationSum.add(fee);
        BigDecimal walletBalance = walletService.checkBalance(user.getId());

        if (walletBalance.compareTo(totalRequired) < 0) {
            BigDecimal envelopeTotal = allocationSum.setScale(2, RoundingMode.HALF_UP);
            BigDecimal creationFee = fee.setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalNeeded = totalRequired.setScale(2, RoundingMode.HALF_UP);
            BigDecimal availableBalance = walletBalance.setScale(2, RoundingMode.HALF_UP);
            BigDecimal shortfall = totalRequired.subtract(walletBalance)
                    .max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);

            Map<String, Object> details = budgetFundingDetails(
                    envelopeTotal,
                    creationFee,
                    totalNeeded,
                    availableBalance,
                    shortfall,
                    durationDays
            );
            details.put("canCreateBudget", false);

            throw new InsufficientFundsException(
                    String.format(Locale.US,
                            "Insufficient funds to create budget. Envelopes: ₦%,.2f. " +
                                    "Budget creation fee: ₦%,.2f. Total needed: ₦%,.2f. " +
                                    "Wallet balance: ₦%,.2f. Add at least ₦%,.2f to continue.",
                            envelopeTotal, creationFee, totalNeeded, availableBalance, shortfall),
                    details
            );
        }

        // === SAVE BUDGET & ENVELOPES FIRST (as requested) ===
        Budget budget = new Budget();
        budget.setUser(user);
        budget.setName(request.getName());
        budget.setOriginalAmount(originalAmount);
        budget.setFeeAmount(fee);
        budget.setTotalAmount(originalAmount);           // Full amount goes to budget (fee is extra)
        budget.setAllocatedAmount(allocationSum);
        budget.setStartDate(request.getStartDate());
        budget.setEndDate(request.getEndDate());
        budget.setDurationDays((int) durationDays);
        budget.setStatus(initialStatus);
        budget.setCreatedAt(now);
        budget.setRemainingAmount(originalAmount);       // Initially full amount

        Budget savedBudget = budgetRepository.save(budget);

        // Create envelopes — deferScheduling=true so we batch-create scheduled tasks below
        List<Envelope> envelopes = new ArrayList<>();
        for (EnvelopeRequest envelopeRequest : request.getEnvelopes()) {
            BigDecimal correctAmount = finalAmounts.get(envelopeRequest);
            envelopeRequest.setExactAmount(correctAmount);
            envelopeRequest.setBudgetId(savedBudget.getId());

            Envelope envelope = envelopeService.createEnvelopeEntity(envelopeRequest, savedBudget, email, true, true);

            Map<String, Object> conditions = envelope.getConditions();
            if (conditions != null && "savings_sweep".equalsIgnoreCase((String) conditions.getOrDefault("type", ""))) {
                if (savedBudget.getStatus() == BudgetStatus.SCHEDULED) {
                    conditions.put("savingsSweepStatus", "PENDING_ACTIVATION");
                    envelope.setConditions(conditions);
                    envelopeRepository.save(envelope);
                } else {
                    try {
                        Long targetSavingsId = Long.valueOf(conditions.get("targetSavingsGoalId").toString());
                        savingsService.sweepEnvelopeToSavings(user.getId(), targetSavingsId, correctAmount,
                                envelope.getName(), savedBudget.getId(), envelope.getId());

                        conditions.put("savingsSweepStatus", "COMPLETED");
                        envelope.setConditions(conditions);
                        envelope.setRemainingAmount(BigDecimal.ZERO);
                        envelope.setTotalRemainingAmount(BigDecimal.ZERO);
                        envelope.setHasMatured(true);
                        envelopeRepository.save(envelope);
                    } catch (Exception e) {
                        logger.error("Failed to sweep envelope to savings for user {}", user.getId(), e);
                        throw new IllegalStateException("Failed to process savings sweep");
                    }
                }
            }

            envelopes.add(envelope);
        }

        // Batch-create disbursement tasks (replaces per-envelope scheduleDynamicTasks calls)
        List<ScheduledTask> disbursementTasks = new ArrayList<>();
        for (Envelope envelope : envelopes) {
            LocalDateTime nextDisb = envelope.getNextDisbursementAt();
            if (nextDisb != null && savedBudget.getStatus() == BudgetStatus.ACTIVE) {
                disbursementTasks.add(new ScheduledTask(envelope.getId(), "DISBURSEMENT", nextDisb));
            }
        }
        if (!disbursementTasks.isEmpty()) {
            scheduledTaskRepository.saveAll(disbursementTasks);
        }

        savedBudget.clearEnvelopes();
        savedBudget.addAllEnvelopes(envelopes);
        BigDecimal remainingInBudget = envelopes.stream()
                .map(envelope -> envelope.getTotalRemainingAmount() != null
                        ? envelope.getTotalRemainingAmount()
                        : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        savedBudget.setRemainingAmount(remainingInBudget);
        budgetRepository.save(savedBudget);

        // === NOW DEDUCT FEE USING PRIVATE HELPER (skipped when fee = 0) ===
        deductBudgetCreationFee(user.getId(), fee);

        // === DEDUCT ALLOCATION FROM WALLET ===
        walletService.deductBalance(user.getId(), allocationSum);

        // === CREDIT REVENUE WALLET (only when fee > 0) ===
        Wallet revenueWallet = null;
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            revenueWallet = walletRepository.findByRevenueWalletTrue()
                    .orElseThrow(() -> new RuntimeException("Revenue wallet not found"));
            revenueWallet.setBalance(revenueWallet.getBalance().add(fee));
            walletRepository.save(revenueWallet);
        }

        // === TRANSACTION LOGS ===
        // 1. Budget allocation log
        TransactionLog allocationLog = new TransactionLog();
        allocationLog.setUserId(user.getId());
        allocationLog.setBudgetId(savedBudget.getId());
        allocationLog.setAmount(allocationSum.negate());
        allocationLog.setFee(fee);
        allocationLog.setTransactionType(BUDGET_ALLOCATION);
        allocationLog.setReference("BUD-ALL-" + savedBudget.getId() + "-" + System.currentTimeMillis());
        allocationLog.setDescription(savedBudget.getStatus() == BudgetStatus.SCHEDULED
                ? "Reserved for scheduled budget envelopes"
                : "Allocated to budget envelopes");
        allocationLog.setStatus(TransactionStatus.COMPLETED);
        allocationLog.setCreatedAt(now);
        transactionLogRepository.save(allocationLog);

        // 2. Budget creation fee log (only when a fee was actually charged)
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            TransactionLog feeLog = new TransactionLog();
            feeLog.setUserId(user.getId());
            feeLog.setBudgetId(savedBudget.getId());
            feeLog.setAmount(fee.negate());
            feeLog.setFee(BigDecimal.ZERO);
            feeLog.setTransactionType(BUDGET_CREATION_FEE);
            String feeReference = "BUD-FEE-" + savedBudget.getId() + "-" + System.currentTimeMillis();
            feeLog.setReference(feeReference);
            feeLog.setDescription("Budget creation fee");
            feeLog.setStatus(TransactionStatus.COMPLETED);
            feeLog.setCreatedAt(now);
            transactionLogRepository.save(feeLog);

            // === REVENUE LOG ===
            if (revenueWallet != null) {
                RevenueLog revenueLog = new RevenueLog();
                revenueLog.setUserId(revenueWallet.getUser().getId());
                revenueLog.setType("budget_creation_fee");
                revenueLog.setAmount(fee);
                revenueLog.setDescription("Budget fee for " + durationDays + " days");
                revenueLog.setCreatedAt(now);
                revenueLogRepository.save(revenueLog);
            }

            collectRubiesBudgetCreationFeeAfterCommit(user.getId(), fee, feeReference);
        }

        // === NOTIFICATION ===
        Map<String, Object> params = new HashMap<>();
        params.put("budgetName", savedBudget.getName());
        params.put("allocated", allocationSum);
        params.put("fee", fee);
        params.put("envelopeCount", request.getEnvelopes().size());
        params.put("startDate", savedBudget.getStartDate().toString());

        eventPublisher.publishEvent(new GenericNotificationEvent(
                this, user.getId().toString(),
                savedBudget.getStatus() == BudgetStatus.SCHEDULED
                        ? NotificationType.BUDGET_SCHEDULED
                        : NotificationType.BUDGET_CREATION,
                params, savedBudget.getId(), null, "/budgets/" + savedBudget.getId()
        ));

        // Activation journey off-switch: comment out this one invocation to
        // stop the post-first-budget review push/email.
        activationJourneyNudgeService.nudgeAfterBudgetCreated(user.getId(), savedBudget.getId());

        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
        return mapToResponse(savedBudget);
    }

    // Helper classes to calculate period
    public BigDecimal getLimitFromConditions(Envelope e){
        Map<String, Object> cond = e.getConditions();
        if(cond == null || !cond.containsKey("limit")) return BigDecimal.ZERO;
        Object limit = cond.get("limit");
        return limit instanceof Number ? new BigDecimal(((Number) limit).doubleValue()) : BigDecimal.ZERO;
    }

    // Helper classes to calculate how much has been used in the condition limit
    public BigDecimal getUsedThisPeriod(Envelope e){
        BigDecimal limit = getLimitFromConditions(e);
        return limit.subtract(e.getRemainingAmount()).max(BigDecimal.ZERO);
    }

    // New: Get a single Budget by ID
    public BudgetResponse getBudgetById(Long budgetId, String email) {
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to view this budget");
        }
        return mapToResponse(budget);
    }

    // New: Activate a Budget
    @Transactional
    public BudgetResponse activateBudget(Long budgetId, String email) {
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findByIdForUpdate(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to activate this budget");
        }
        if (budget.getStatus() == BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget is already active");
        }
        if (budget.getStatus() == BudgetStatus.COMPLETED
                || budget.getStatus() == BudgetStatus.CANCELLED
                || budget.getStatus() == BudgetStatus.FAILED_PROCESSING) {
            throw new IllegalArgumentException("This budget cannot be activated");
        }
        if (budget.getStatus() == BudgetStatus.SCHEDULED) {
            LocalDate today = fetchCurrentDateTimeFromDatabase().toLocalDate();
            if (budget.getStartDate() != null && budget.getStartDate().isAfter(today)) {
                throw new IllegalArgumentException("Budget is scheduled to start on " + budget.getStartDate());
            }
            Budget activatedBudget = budgetLifeCycleManager.activateScheduledBudget(budgetId);
            monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
            return mapToResponse(activatedBudget);
        }
        budget.setStatus(BudgetStatus.ACTIVE);
        Budget updatedBudget = budgetRepository.save(budget);
        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
        return mapToResponse(updatedBudget);
    }

    @Transactional
    public BudgetResponse cancelScheduledBudget(Long budgetId, String email) {
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findByIdForUpdate(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));

        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to cancel this budget");
        }
        if (budget.getStatus() == BudgetStatus.CANCELLED) {
            return mapToResponse(budget);
        }
        if (budget.getStatus() != BudgetStatus.SCHEDULED) {
            throw new IllegalStateException("Only scheduled budgets can be cancelled here");
        }

        BigDecimal refunded = budgetLifeCycleManager.refundUnusedBudgetBalance(budget, user);

        budget.setStatus(BudgetStatus.CANCELLED);
        budget.setRemainingAmount(BigDecimal.ZERO);
        Budget cancelledBudget = budgetRepository.save(budget);

        Map<String, Object> params = new HashMap<>();
        params.put("budgetName", cancelledBudget.getName());
        params.put("refunded", refunded);
        params.put("__message", String.format(
                "Your scheduled budget '%s' has been cancelled. \u20A6%,.2f has been returned to your wallet.",
                cancelledBudget.getName(),
                refunded
        ));

        eventPublisher.publishEvent(new GenericNotificationEvent(
                this,
                user.getId().toString(),
                NotificationType.BUDGET_UPDATED,
                params,
                cancelledBudget.getId(),
                null,
                "/budgets"
        ));

        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
        return mapToResponse(cancelledBudget);
    }

    // New: Delete a Budget
    @Transactional
    public void deleteBudget(Long budgetId, String email) {
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findByIdForUpdate(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to delete this budget");
        }
        if (budget.getStatus() == BudgetStatus.CANCELLED) {
            return; // already dissolved — idempotent, nothing to do
        }
        // Creating a funded budget debits the wallet, so ACTIVE and SCHEDULED
        // budgets must refund allocated-but-unspent envelope balance before
        // soft-cancel. DRAFT was never debited; COMPLETED was already refunded.
        if (isFundedBudgetStatus(budget.getStatus())) {
            budgetLifeCycleManager.refundUnusedBudgetBalance(budget, user);
        }
        // Soft-delete rather than hard-delete: keep the budget, its envelopes, and
        // the transaction history for audit / regulatory retention. Seven audit
        // tables FK-reference the envelopes, so a hard delete violates those
        // constraints anyway (transaction_logs_source_envelope_id_fkey, etc.).
        // The scheduler ignores non-ACTIVE budgets and user-facing lists exclude
        // CANCELLED, so it disappears from the user's view.
        budget.setStatus(BudgetStatus.CANCELLED);
        budget.setRemainingAmount(BigDecimal.ZERO);
        budgetRepository.save(budget);
        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
    }

    /**
     * Admin/support path: dissolves any user's budget <em>on their behalf</em>.
     * Delegates to {@link #deleteBudget} with the budget OWNER's identity, so
     * the ownership check passes and — critically — the unspent-balance refund
     * lands in the owner's wallet, never the admin's. The ADMIN role gate lives
     * on the controller; there is no user-facing path to this.
     */
    @Transactional
    public void deleteBudgetAsAdmin(Long budgetId) {
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        deleteBudget(budgetId, budget.getUser().getEmail());
    }


    // New: Fetch all Budgets for a user
    public List<BudgetResponse> getBudgets(String email) {
        User user = userService.findByEmail(email);
        return budgetRepository.findByUserId(user.getId())
                .stream()
                // Hide soft-deleted (dissolved) budgets — the rows are kept only
                // for audit/retention, never shown back to the user.
                .filter(b -> b.getStatus() != BudgetStatus.CANCELLED)
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

//    public List<EnvelopeResponse> getEnvelopesByBudget(Long budgetId, String email) {
//        System.out.println("Fetching envelopes for budgetId: " + budgetId + ", email: " + email);
//        User user = userService.findByEmail(email);
//
//        Budget budget = budgetRepository.findById(budgetId)
//                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
//        System.out.println("Found budget: " + budget.getId() + ", userId: " + budget.getUser().getId());
//        if (!budget.getUser().getId().equals(user.getId())) {
//            throw new SecurityException("You do not have permission to view this budget");
//        }
//        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);
//        System.out.println("Found " + envelopes.size() + " envelopes");
//        return envelopes.stream()
//                .map(envelope -> new EnvelopeResponse(
//                        envelope.getId(),
//                        envelope.getBudget().getId(),
//                        envelope.getName(),
//                        envelope.getAmount(),
//                        // New fields
//                        envelope.getAmount(),                    // ← initialAmount
//                        envelope.getTotalRemainingAmount(),      // ← totalRemaining
//                        envelope.getRemainingAmount(),           // ← periodRemaining
////                        getPeriodLimit(envelope),                // ← periodLimit
//                        calculatePeriodLimit(envelope, budget),
//                        getUsedThisPeriod(envelope),
//
//                        envelope.getRemainingAmount(),
//                        envelope.getConditions(),
//                        envelope.getCreatedAt(),
//                        envelope.getLastDisbursedAt(),
//                        envelope.getNextDisbursementAt()
//                ))
//                .collect(Collectors.toList());
//    }

    public List<EnvelopeResponse> getEnvelopesByBudget(Long budgetId, String email) {
        User user = userService.findByEmail(email);

        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));

        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to view this budget");
        }

        String cacheKey = ENVELOPES_CACHE_PREFIX + budgetId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, EnvelopeResponse.class));
            }
        } catch (Exception e) {
            logger.debug("[EnvelopeCache] Cache miss or read error for budgetId={}: {}", budgetId, e.getMessage());
        }

        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);

        List<EnvelopeResponse> responses = envelopes.stream()
                .map(envelope -> mapEnvelopeToResponse(envelope, budget, email))
                .collect(Collectors.toList());

        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(responses),
                    ENVELOPES_CACHE_TTL_SECS, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("[EnvelopeCache] Failed to write cache for budgetId={}: {}", budgetId, e.getMessage());
        }

        return responses;
    }

    /** Evict the cached envelope list after operations that change envelope state. */
    private void evictEnvelopeCache(Long budgetId) {
        try {
            redisTemplate.delete(ENVELOPES_CACHE_PREFIX + budgetId);
        } catch (Exception e) {
            logger.debug("[EnvelopeCache] Failed to evict cache for budgetId={}: {}", budgetId, e.getMessage());
        }
    }

    @Transactional
    public void topUpBudget(Long budgetId, Double amount, String email) {
        BigDecimal topupAmount = BigDecimal.valueOf(amount);
        BigDecimal minimumTopUp = new BigDecimal("100");

        if (topupAmount.compareTo(minimumTopUp) < 0) {
            throw new IllegalArgumentException("Minimum top-up amount is ₦100");
        }

        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found"));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to top up this budget");
        }
        if (budget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget must be active to top up");
        }

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        LocalDate today = now.toLocalDate();
        long remainingDays = java.time.temporal.ChronoUnit.DAYS.between(today, budget.getEndDate());
        if (remainingDays < 2) {
            throw new IllegalArgumentException("Budget must have at least 2 days remaining to top up");
        }

        LocalDateTime lastTopup = budget.getLastTopupTime();
        if (lastTopup != null && now.isBefore(lastTopup.plusDays(1))) {
            throw new IllegalArgumentException(
                    "Top-up allowed once per day. Try again after " + lastTopup.plusDays(1).toLocalDate());
        }

        walletService.deductBalance(user.getId(), topupAmount);

        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);
        if (envelopes.isEmpty()) {
            throw new IllegalArgumentException("No envelopes found in this budget");
        }

        BigDecimal totalInitial = envelopes.stream()
                .map(Envelope::getInitialAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalInitial.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Cannot determine envelope allocation ratios");
        }

        BigDecimal distributed = BigDecimal.ZERO;
        for (int i = 0; i < envelopes.size(); i++) {
            Envelope envelope = envelopes.get(i);
            BigDecimal share;
            if (i == envelopes.size() - 1) {
                share = topupAmount.subtract(distributed);
            } else {
                BigDecimal ratio = envelope.getInitialAmount()
                        .divide(totalInitial, 6, RoundingMode.HALF_UP);
                share = topupAmount.multiply(ratio)
                        .setScale(2, RoundingMode.HALF_UP);
                distributed = distributed.add(share);
            }
            envelope.setAmount(envelope.getAmount().add(share));
            envelope.setTotalRemainingAmount(envelope.getTotalRemainingAmount().add(share));
            envelope.setInitialAmount(envelope.getInitialAmount().add(share));
            envelopeRepository.save(envelope);
        }

        evictEnvelopeCache(budgetId);

        budget.setTotalAmount(budget.getTotalAmount().add(topupAmount));
        budget.setAllocatedAmount(budget.getAllocatedAmount().add(topupAmount));
        budget.setRemainingAmount(budget.getRemainingAmount().add(topupAmount));
        budget.setLastTopupTime(now);
        budgetRepository.save(budget);

        TransactionLog log = new TransactionLog(
                user.getId(),
                budgetId,
                null,
                null,
                null,
                topupAmount,
                BigDecimal.ZERO,
                BUDGET_TOP_UP,
                "Budget top-up: " + budget.getName()
        );
        log.setCreatedAt(now);
        transactionLogRepository.save(log);
        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
    }

    // New: Extend Budget
    @Transactional
    public void extendBudget(Long budgetId, String newName, LocalDate newEndDate, String email) {
        // Step 1: Validate inputs and ownership
        if (newEndDate == null || newEndDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("New end date must be in the future");
        }
        User user = userService.findByEmail(email);
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Budget not found with ID: " + budgetId));
        if (!budget.getUser().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to extend this budget");
        }
        if (budget.getStatus() == BudgetStatus.COMPLETED) {
            throw new IllegalArgumentException("Cannot extend an ended budget. Create a new budget instead.");
        }
        if (budget.getStatus() == BudgetStatus.SCHEDULED) {
            throw new IllegalArgumentException("Scheduled budgets cannot be extended. Cancel it and create a new schedule.");
        }
        if (budget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Only active budgets can be extended");
        }

        // Step 2: Update Budget details
        if (newName != null && !newName.trim().isEmpty()) {
            budget.setName(newName);
        }
        budget.setEndDate(newEndDate);
        budget.setStatus(BudgetStatus.ACTIVE); // Ensure it remains active
        budgetRepository.save(budget);

        // Step 3: Roll over Envelopes (keep existing funds and conditions)
        List<Envelope> envelopes = envelopeRepository.findByBudgetId(budgetId);
        System.out.println("Rolling over " + envelopes.size() + " envelopes for Budget ID: " + budgetId);
        // No changes needed to envelopes since we're rolling over existing funds

        // Step 4: Log transaction
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        TransactionLog transactionLog = new TransactionLog(
                user.getId(),
                budgetId,
                null, // No source envelope
                null, // No target envelope
                null, // No external account
                BigDecimal.ZERO, // No amount
                BigDecimal.ZERO, // No fee
                BUDGET_EXTENSION,
                "Extended budget end date to " + newEndDate
        );
        transactionLog.setCreatedAt(now);
        transactionLogRepository.save(transactionLog);
        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
    }

    // Placeholder for getTimeBasedGreeting
    private String getTimeBasedGreeting(String name) {
        int hour = LocalDateTime.now().getHour();
        String timeOfDay = hour < 12 ? "Morning" : hour < 17 ? "Afternoon" : "Evening";
        return String.format("Good %s, %s!", timeOfDay, name);
    }
    public Map<String, Object> getDashboard(String email) {
        User user = userService.findByEmail(email);
        String name = user.getName() != null ? user.getName() : user.getEmail().split("@")[0];
        String greeting = getTimeBasedGreeting(name);

        List<Object[]> rows = budgetRepository.findDashboardSummariesByUserId(user.getId());
        Map<String, List<Map<String, Object>>> budgetMap = new HashMap<>();
        budgetMap.put("active", new ArrayList<>());
        budgetMap.put("scheduled", new ArrayList<>());
        budgetMap.put("completed", new ArrayList<>());

        LocalDate today = LocalDate.now();
        for (Object[] row : rows) {
            Long budgetId = ((Number) row[0]).longValue();
            String budgetName = (String) row[1];
            BudgetStatus status = (BudgetStatus) row[2];
            // Soft-deleted (dissolved) budgets are kept only for audit — never
            // surface them (they would otherwise fall into the "completed" bucket).
            if (status == BudgetStatus.CANCELLED) {
                continue;
            }
            LocalDate startDate = (LocalDate) row[3];
            LocalDate endDate = (LocalDate) row[4];
            BigDecimal allocatedAmount = (BigDecimal) row[5];
            int envelopeCount = ((Number) row[6]).intValue();
            BigDecimal remainingAmount = row[7] != null
                    ? (BigDecimal) row[7]
                    : allocatedAmount; // default: nothing spent yet
            BigDecimal spentAmount = allocatedAmount.subtract(remainingAmount)
                    .max(BigDecimal.ZERO);

            Map<String, Object> budgetSummary = new HashMap<>();
            budgetSummary.put("id", budgetId);
            budgetSummary.put("name", budgetName);
            budgetSummary.put("status", status.name());
            budgetSummary.put("startDate", startDate.toString());
            budgetSummary.put("endDate", endDate != null ? endDate.toString() : startDate.toString());
            budgetSummary.put("allocatedAmount", allocatedAmount);
            budgetSummary.put("envelopeCount", envelopeCount);
            budgetSummary.put("remainingAmount", remainingAmount);
            budgetSummary.put("spentAmount", spentAmount);
            budgetSummary.put("isFunded", status == BudgetStatus.ACTIVE || status == BudgetStatus.SCHEDULED);

            if (status == BudgetStatus.SCHEDULED) {
                long daysUntilStart = startDate != null ? ChronoUnit.DAYS.between(today, startDate) : 0;
                budgetSummary.put("daysUntilStart", Math.max(0, daysUntilStart));
                budgetMap.get("scheduled").add(budgetSummary);
            } else if (status == BudgetStatus.ACTIVE && endDate != null && !endDate.isBefore(today)) {
                // Quick-spend rail: attach the budget's envelopes sorted spendable-first so the
                // dashboard can render tap-to-spend links without a second round-trip. Cap a little
                // above the 3 the UI shows for headroom. Active budgets only — keeps the payload
                // small and leaves the (additive) completed summaries untouched.
                budgetSummary.put("spendableEnvelopes",
                        envelopeService.getDashboardEnvelopes(budgetId, 5));
                budgetMap.get("active").add(budgetSummary);
            } else {
                budgetMap.get("completed").add(budgetSummary);
            }
        }

        return Map.of(
                "greeting", greeting,
                "budgets", budgetMap
        );
    }

//    private BudgetResponse mapToResponse(Budget budget) {
//        BudgetResponse response = new BudgetResponse(
//                budget.getId(),
//                budget.getName(),
//                budget.getTotalAmount(),
//                budget.getAllocatedAmount(),
//                budget.getDurationDays(),
//                budget.getStartDate(),
//                budget.getEndDate(),
//                budget.getStatus(),
//                budget.getCreatedAt(),
//                budget.getUser().getId(),
//                budget.getLastTopupTime()
//        );
//
//        // Map Envelopes to EnvelopeResponse
//        List<EnvelopeResponse> envelopeResponses = (budget.getEnvelopes() != null)
//                ? budget.getEnvelopes().stream()
//                .map(envelope -> new EnvelopeResponse(
//                        envelope.getId(),
//                        budget.getId(),
////                        envelope.getBudget().getId(),
//                        envelope.getName(),
//                        envelope.getAmount(),
//                        envelope.getRemainingAmount(),
//
//                        // New fields
//                        envelope.getAmount(),                    // ← initialAmount
//                        envelope.getTotalRemainingAmount(),      // ← totalRemaining
//                        envelope.getRemainingAmount(),           // ← periodRemaining
////                        getPeriodLimit(envelope),                // ← periodLimit
//                        calculatePeriodLimit(envelope, budget),
//                        getUsedThisPeriod(envelope),             // ← usedThisPeriod
//
//                        envelope.getConditions(),
//                        envelope.getCreatedAt(),
//                        envelope.getLastDisbursedAt(),
//                        envelope.getNextDisbursementAt()
//                ))
//                .collect(Collectors.toList()): new ArrayList<>();
//        response.setEnvelopes(envelopeResponses);
//        return response;
//    }

    private BudgetResponse mapToResponse(Budget budget) {
        BudgetResponse response = new BudgetResponse(
                budget.getId(),
                budget.getName(),
                budget.getTotalAmount(),
                budget.getAllocatedAmount(),
                budget.getDurationDays(),
                budget.getStartDate(),
                budget.getEndDate(),
                budget.getStatus(),
                budget.getCreatedAt(),
                budget.getUser().getId(),
                budget.getLastTopupTime()
        );

        String email = budget.getUser().getEmail();

        List<EnvelopeResponse> envelopeResponses = budget.getEnvelopes() != null
                ? budget.getEnvelopes()
                .stream()
                .map(envelope -> mapEnvelopeToResponse(envelope, budget, email))
                .collect(Collectors.toList())
                : new ArrayList<>();

        response.setEnvelopes(envelopeResponses);
        response.setOriginalAmount(budget.getOriginalAmount());
        response.setFeeAmount(budget.getFeeAmount());

        return response;
    }

    @Transactional
    public EnvelopeResponse lockEnvelope(
            Long envelopeId,
            String lockType,
            Integer durationDays,
            BigDecimal interestRate,
            String email
    ) {
        User user = userService.findByEmail(email);
        Envelope envelope = envelopeRepository.findById(envelopeId)
                .orElseThrow(() -> new IllegalArgumentException("Envelope not found"));

        if (!envelope.getBudget().getUser().getId().equals(user.getId())) {
            throw new SecurityException("Unauthorized");
        }

        Map<String, Object> conditions = envelope.getConditions();
        if (conditions.containsKey("locked")) {
            throw new IllegalArgumentException("Envelope already locked");
        }

        conditions.put("type", lockType.toLowerCase());
        conditions.put("lockDurationDays", durationDays);
        conditions.put("interestRate", interestRate);
        conditions.put("lockStartDate", LocalDate.now().toString());

        // For StrictLock, deduct fee
        if ("STRICT_LOCK".equals(lockType)) {
            BigDecimal fee = envelope.getRemainingAmount()
                    .multiply(new BigDecimal("0.02")); // 2% fee
            walletService.deductBalance(user.getId(), fee);
        }

        envelope.setConditions(conditions);
        envelopeRepository.save(envelope);
        evictEnvelopeCache(envelope.getBudget().getId());
        Budget budget = budgetRepository.findById(envelope.getBudget().getId())
                .orElseThrow(() -> new IllegalStateException("Budget not found for envelope " + envelope.getId()));

        return new EnvelopeResponse(
                envelope.getId(),
                budget.getId(), // ✅ Now works
                envelope.getName(),
                envelope.getRemainingAmount(),
                envelope.getRemainingAmount(),
                // New fields
                envelope.getAmount(),                    // ← initialAmount
                envelope.getTotalRemainingAmount(),      // ← totalRemaining
                envelope.getRemainingAmount(),           // ← periodRemaining
                getPeriodLimit(envelope),                // ← periodLimit
                getUsedThisPeriod(envelope),             // ← usedThisPeriod

                envelope.getHeldAmount() != null ? envelope.getHeldAmount() : BigDecimal.ZERO,

                Boolean.TRUE.equals(envelope.getIsAutomated()),

                envelope.getConditions(),
                envelope.getCreatedAt(),
                envelope.getLastDisbursedAt(),
                envelope.getNextDisbursementAt()
        );
    }

    private long countActiveDays(LocalDate start, LocalDate end, List<String> days) {
        return start.datesUntil(end.plusDays(1))
                .filter(d -> days.contains(d.getDayOfWeek().name()))
                .count();
    }

    private BigDecimal getPeriodLimit(Envelope envelope) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null || !conditions.containsKey("limit")) {
            return BigDecimal.ZERO;
        }
        Object limit = conditions.get("limit");
        return limit instanceof Number ? new BigDecimal(((Number) limit).doubleValue()) : BigDecimal.ZERO;
    }

    // CALCULATE THE PERIODLIMIT
    private BigDecimal calculatePeriodLimit(Envelope envelope, Budget budget) {
        Map<String, Object> cond = envelope.getConditions();
        if (cond == null || !cond.containsKey("type")) return envelope.getAmount();

        String type = ((String) cond.get("type")).toLowerCase();
        BigDecimal amount = envelope.getAmount();
        LocalDate start = budget.getStartDate();
        LocalDate end = budget.getEndDate();

        return switch (type) {
            case "daily" -> amount.divide(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end) + 1), 2, RoundingMode.HALF_UP);
            case "weekly" -> amount.divide(BigDecimal.valueOf((ChronoUnit.DAYS.between(start, end) + 6) / 7), 2, RoundingMode.HALF_UP);
            case "dynamic" -> {
                List<String> days = (List<String>) cond.get("days");
                long active = countActiveDays(start, end, days);
                yield active > 0 ? amount.divide(BigDecimal.valueOf(active), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            }
            default -> BigDecimal.ZERO;
        };
    }

    private void validateEnvelopeLimit(EnvelopeRequest req, BigDecimal envelopeAmount, LocalDate start, LocalDate end) {
        Map<String, Object> cond = req.getConditions();
        if (cond == null || !cond.containsKey("limit")) return;

        String type = ((String) cond.get("type")).toLowerCase();
        if (List.of("emergency", "strict_lock", "safe_lock", "savings_sweep").contains(type)) return;

        BigDecimal userLimit = new BigDecimal(cond.get("limit").toString());
        if (userLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    String.format("'%s' envelope: Limit must be positive. You set ₦%,.2f.", req.getName(), userLimit)
            );
        }

        long activePeriods = switch (type) {
            case "daily" -> ChronoUnit.DAYS.between(start, end) + 1;
            case "weekly" -> (ChronoUnit.DAYS.between(start, end) + 6) / 7;
            case "dynamic" -> {
                List<String> days = (List<String>) cond.get("days");
                if (days == null || days.isEmpty()) {
                    throw new IllegalArgumentException(
                            String.format("'%s' envelope: Dynamic type requires 'days' array.", req.getName())
                    );
                }
                yield countActiveDays(start, end, days);
            }
            default -> 1;
        };

        BigDecimal maxAllowed = envelopeAmount;
        BigDecimal maxFromLimit = userLimit.multiply(BigDecimal.valueOf(activePeriods));
        BigDecimal maxSafeLimit = maxAllowed.divide(BigDecimal.valueOf(activePeriods), 2, RoundingMode.HALF_UP);
        BigDecimal minNeededAllocation = maxFromLimit;

        if (maxFromLimit.compareTo(maxAllowed) > 0) {
            String message = String.format(
                    "'%s' envelope: Your limit of ₦%,.2f is too high.\n" +
                            "• With %d active %s, total allowed = ₦%,.2f\n" +
                            "• But you only allocated ₦%,.2f\n\n" +
                            "Fix it by:\n" +
                            "1. Reduce limit to ≤ ₦%,.2f per %s, or\n" +
                            "2. Increase envelope allocation to ≥ ₦%,.2f",
                    req.getName(),
                    userLimit,
                    activePeriods, activePeriods == 1 ? "period" : "periods",
                    maxFromLimit,
                    maxAllowed,
                    maxSafeLimit, type.equals("dynamic") ? "disbursement" : type,
                    minNeededAllocation
            );
            throw new IllegalArgumentException(message);
        }
    }

//    @Transactional
//    public void deductBudgetCreationFee(Long userId) {
//        BigDecimal fee = new BigDecimal("200.00");
//
//        // 1. Deduct from user's wallet
//        Wallet userWallet = walletRepository.findByUserId(userId)
//                .orElseThrow(() -> new IllegalArgumentException("User wallet not found"));
//
//        if (userWallet.getBalance().compareTo(fee) < 0) {
//            throw new InsufficientFundsException("Add ₦200+ to your wallet to create a budget.");
//        }
//
//        userWallet.setBalance(userWallet.getBalance().subtract(fee));
//        walletRepository.save(userWallet);
//
//        // 2. Credit platform revenue wallet
//        Wallet revenueWallet = walletRepository.findByIsRevenueWalletTrue()
//                .orElseThrow(() -> new RuntimeException("Revenue wallet not configured"));
//
//        revenueWallet.setBalance(revenueWallet.getBalance().add(fee));
//        walletRepository.save(revenueWallet);
//
//        // 3. Log revenue
//        RevenueLog revenueLog = new RevenueLog(
//                userId,
//                "budget_creation_fee",
//                fee,
//                "Budget creation fee deducted"
//        );
//        revenueLog.setCreatedAt(LocalDateTime.now());
//        revenueLogRepository.save(revenueLog);
//
//        // ✅ Event for Fee Deduction
//        Map<String, Object> feeParams = Map.of(
//                "amount", String.format("%,.2f", fee),
//                "reason", "Budget Creation Fee"
//        );
//
//        eventPublisher.publishEvent(new GenericNotificationEvent(
//                this,
//                userId.toString(),
//                NotificationType.BUDGET_CREATION_FEE,
//                feeParams,
//                null, null, null
//        ));
//
//        logger.info("₦200 budget creation fee collected from user {} → platform revenue", userId);
//    }

    private void deductBudgetCreationFee(Long userId, BigDecimal feeAmount) {
        if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            // Fee is zero (disabled via system_config) — nothing to deduct
            logger.debug("[Budget] Budget creation fee is 0 — skipping deduction for user {}", userId);
            return;
        }

        walletService.deductBalance(userId, feeAmount);
        logger.info("[Budget] Budget creation fee of ₦{} deducted from user {}", feeAmount, userId);
    }

    private void collectRubiesBudgetCreationFeeAfterCommit(Long userId, BigDecimal feeAmount, String feeReference) {
        if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) <= 0
                || feeReference == null || feeReference.isBlank()) {
            return;
        }

        Wallet userWallet = walletRepository.findByUserId(userId).orElse(null);
        if (userWallet == null) {
            logger.warn("[Budget] Cannot collect budget creation fee physically: wallet not found for user {}", userId);
            return;
        }
        if (!RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(userWallet.getProviderName())) {
            return;
        }
        if (userWallet.getProviderWalletRef() == null || userWallet.getProviderWalletRef().isBlank()) {
            logger.warn("[Budget] Cannot collect budget creation fee physically: Rubies wallet ref missing for user {}",
                    userId);
            return;
        }

        String fromWalletRef = userWallet.getProviderWalletRef();
        String debitName = walletService.resolveDisplayNameByUserId(userId);
        Runnable collectFee = () -> {
            try {
                walletService.collectRubiesBudgetCreationFeeAsync(
                        feeAmount,
                        fromWalletRef,
                        debitName,
                        feeReference,
                        userId
                );
            } catch (Exception e) {
                logger.error("[Budget] Failed to enqueue Rubies budget creation fee collection for ref={}: {}",
                        feeReference, e.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    collectFee.run();
                }
            });
        } else {
            collectFee.run();
        }
    }

    private EnvelopeResponse mapEnvelopeToResponse(Envelope envelope, Budget budget, String email) {
        BigDecimal periodLimit = getLimitFromConditions(envelope);
        BigDecimal periodRemaining = envelope.getRemainingAmount() != null
                ? envelope.getRemainingAmount()
                : BigDecimal.ZERO;

        BigDecimal usedThisPeriod = periodLimit
                .subtract(periodRemaining)
                .max(BigDecimal.ZERO);

        BigDecimal heldAmt = envelope.getHeldAmount() != null
                ? envelope.getHeldAmount()
                : BigDecimal.ZERO;

        return new EnvelopeResponse(
                envelope.getId(),
                budget.getId(),
                envelope.getName(),

                envelope.getAmount(),
                periodRemaining,

                envelope.getInitialAmount(),
                envelope.getTotalRemainingAmount(),
                periodRemaining,
                periodLimit,
                usedThisPeriod,

                heldAmt,

                Boolean.TRUE.equals(envelope.getIsAutomated()),

                envelope.getConditions(),
                envelope.getCreatedAt(),
                envelope.getLastDisbursedAt(),
                envelope.getNextDisbursementAt()
        );
    }

}
