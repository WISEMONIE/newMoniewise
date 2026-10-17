package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.config.SavingsLifeCycleManager;
import com.moniewise.moniewise_backend.dto.request.CreateSavingsGoalRequest;
import com.moniewise.moniewise_backend.dto.request.SavingsP2PTransferRequest;
import com.moniewise.moniewise_backend.dto.request.UpdateSavingsGoalRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.EventCategory;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SavingsService {

    private static final Logger logger = LoggerFactory.getLogger(SavingsService.class);
    private final SavingsGoalRepository savingsGoalRepository;
    private final UserRepository userRepository;
    private final WalletService walletService;
    private final TransactionLogRepository transactionLogRepository;
    private final EnvelopeRepository envelopeRepository;
    private final NotificationService notificationService;
    private final SavingsCacheService savingsCacheService;
    private final SavingsLifeCycleManager savingsLifeCycleManager;
    private final UserService userService;
    private final PaymentGatewayResolver paymentGatewayResolver;
    private final BeneficiaryService beneficiaryService;
    private final MarkupCalculatorService markupCalculatorService;
    private final SavingsProjectionWebSocketService savingsProjectionWebSocketService;

    private static final String RUBIES_BANK_CODE = "090175";
    private static final String RUBIES_BANK_NAME = "Rubies MFB";
    private static final BigDecimal SAVINGS_BANK_FEE_UNDER_50K = new BigDecimal("100");
    private static final BigDecimal SAVINGS_BANK_FEE_50K_AND_ABOVE = new BigDecimal("200");
    private static final BigDecimal SAVINGS_BANK_FEE_THRESHOLD = new BigDecimal("50000");
    /** Names stay reserved only while money can still be in the event. */
    private static final Set<SavingsStatus> OPEN_EVENT_STATUSES = Set.of(
            SavingsStatus.ACTIVE, SavingsStatus.MATURED);

    public SavingsService(SavingsGoalRepository savingsGoalRepository,
                          UserRepository userRepository,
                          WalletService walletService,
                           TransactionLogRepository transactionLogRepository,
                           EnvelopeRepository envelopeRepository,
                           NotificationService notificationService,
                           SavingsCacheService savingsCacheService,
                           SavingsLifeCycleManager savingsLifeCycleManager,
                          UserService userService,
                          PaymentGatewayResolver paymentGatewayResolver,
                          BeneficiaryService beneficiaryService,
                          MarkupCalculatorService markupCalculatorService,
                          SavingsProjectionWebSocketService savingsProjectionWebSocketService) {
        this.savingsGoalRepository = savingsGoalRepository;
        this.userRepository = userRepository;
        this.walletService = walletService;
        this.transactionLogRepository = transactionLogRepository;
        this.envelopeRepository = envelopeRepository;
        this.notificationService = notificationService;
        this.savingsCacheService = savingsCacheService;
        this.savingsLifeCycleManager = savingsLifeCycleManager;
        this.userService = userService;
        this.paymentGatewayResolver = paymentGatewayResolver;
        this.beneficiaryService = beneficiaryService;
        this.markupCalculatorService = markupCalculatorService;
        this.savingsProjectionWebSocketService = savingsProjectionWebSocketService;
    }

    /**
     * Lazy maturity reconciliation: any ACTIVE goal whose maturityDate has passed
     * is flipped to MATURED (with its comms fired once) the moment the user's goals
     * are read — so the "Ready to Withdraw" UI never depends on the scheduled job
     * having run. The status flip inside matureGoal() is the once-only guard: the
     * scheduled job no longer sees the goal as ACTIVE, and vice versa.
     */
    private void reconcileMaturedGoals(Long userId) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        List<SavingsGoal> activeGoals =
                savingsGoalRepository.findByUserIdAndStatus(userId, SavingsStatus.ACTIVE);
        for (SavingsGoal goal : activeGoals) {
            try {
                if (goal.getMaturityDate() != null && !today.isBefore(goal.getMaturityDate())) {
                    savingsLifeCycleManager.matureGoal(goal.getId());
                }
            } catch (Exception e) {
                // Never let a reconciliation hiccup break the goals screen.
                logger.error("Failed to lazily mature Savings Goal ID: {}", goal.getId(), e);
            }
        }
    }

    /**
     * Push on the same transaction as the money change. The outbox insert rolls back
     * with the event if the save fails. A notification error is swallowed inside
     * sendTitledNotification and cannot undo the money move.
     */
    private void notifyEvent(Long userId, Long goalId, String title, String message, NotificationType type) {
        notificationService.sendTitledNotification(
                userId.toString(),
                title,
                message,
                type,
                "VIEW_SAVINGS",
                goalId == null ? null : "/savings/" + goalId
        );
    }

    /**
     * The current store build creates a pot without a type or a terms flag.
     * The Events app sends both. Require the terms only when this is an Events request,
     * so shipping the server does not block create on phones that have not updated.
     */
    private static boolean isEventsRequest(CreateSavingsGoalRequest request) {
        return request.getTermsAccepted() != null
                || (request.getCategory() != null && !request.getCategory().isBlank())
                || request.getRemindWeek() != null
                || request.getRemindDayBefore() != null
                || request.getRemindMorning() != null;
    }

    /**
     * Phase 1: Creating the pot and making the initial deposit
     */
    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal createSavingsGoal(Long userId, CreateSavingsGoalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Accept the savings terms to continue.");
        }
        boolean eventsRequest = isEventsRequest(request);
        EventCategory category = null;
        if (eventsRequest) {
            if (!Boolean.TRUE.equals(request.getTermsAccepted())) {
                throw new IllegalArgumentException("Accept the savings terms to continue.");
            }
            category = EventCategory.parse(request.getCategory());
            if (category == null) {
                throw new IllegalArgumentException("Choose what this savings is.");
            }
        }
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Enter a savings name.");
        }
        BigDecimal targetAmount = request.getTargetAmount();
        BigDecimal initialDeposit = request.getInitialDeposit();
        LocalDate maturityDate = request.getMaturityDate();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 1. Safety Check: an open event already uses this name. Closed ones do not.
        if (savingsGoalRepository.existsByUserIdAndNameIgnoreCaseAndStatusIn(
                userId, name, OPEN_EVENT_STATUSES)) {
            throw new IllegalArgumentException("You already have a savings named '" + name + "'");
        }

        // 2. Create the Pot. New events do not earn interest, whatever the request sends.
        SavingsGoal goal = new SavingsGoal();
        goal.setUser(user);
        goal.setName(name);
        goal.setTargetAmount(targetAmount);
        goal.setStartDate(LocalDate.now(ZoneId.of("Africa/Lagos")));
        goal.setMaturityDate(maturityDate);
        goal.setInterestRate(BigDecimal.ZERO);
        goal.setStatus(SavingsStatus.ACTIVE);
        if (eventsRequest) {
            goal.setCategory(category);
            goal.setRemindWeek(request.getRemindWeek() == null || request.getRemindWeek());
            goal.setRemindDayBefore(request.getRemindDayBefore() == null || request.getRemindDayBefore());
            goal.setRemindMorning(Boolean.TRUE.equals(request.getRemindMorning()));
            goal.setTermsAcceptedAt(LocalDateTime.now(ZoneId.of("Africa/Lagos")));
        }
        
        // 3. Handle Initial Deposit (e.g., the 800k)
        if (initialDeposit != null && initialDeposit.compareTo(BigDecimal.ZERO) > 0) {
            // Deduct from Main Wallet
            walletService.debitWalletForWithdrawal(userId, initialDeposit, "fund this savings");

            goal.setCurrentBalance(initialDeposit);

            // Log the Transaction
            TransactionLog log = new TransactionLog();
            log.setUserId(userId);
            log.setAmount(initialDeposit);
            // NOTE: Add SAVINGS_DEPOSIT to your TransactionType Enum!
            log.setTransactionType(TransactionType.SAVINGS_DEPOSIT); 
            log.setDescription("Funded savings: " + name);
            log.setStatus(TransactionStatus.COMPLETED);
            log.setReference("SAVE-" + UUID.randomUUID().toString());
            log.setCreatedAt(LocalDateTime.now());
            transactionLogRepository.save(log);
        } else {
            goal.setCurrentBalance(BigDecimal.ZERO);
        }

        SavingsGoal saved = savingsGoalRepository.save(goal);
        savingsCacheService.evictUserSavingsCachesAfterCommit(userId);
        logger.info("Created Savings Goal '{}' for User {} with initial balance ₦{}", name, userId, saved.getCurrentBalance());

        boolean funded = initialDeposit != null && initialDeposit.compareTo(BigDecimal.ZERO) > 0;
        String dateLabel = maturityDate.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));
        notifyEvent(userId, saved.getId(), name,
                funded
                        ? String.format("You created '%s' for %s and locked ₦%,.2f from your wallet.", name, dateLabel, initialDeposit)
                        : String.format("'%s' is set for %s. The money stays locked until that day.", name, dateLabel),
                NotificationType.SAVINGS_GOAL_CREATED);

        return saved;
    }

    /**
     * Change a live event. The unlock day can only move later. Cancelling is not offered.
     */
    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal updateEvent(Long userId, Long goalId, UpdateSavingsGoalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Nothing to change.");
        }
        SavingsGoal goal = savingsGoalRepository.findByIdForUpdate(goalId)
                .orElseThrow(() -> new IllegalArgumentException("Savings not found."));
        if (goal.getUser() == null || goal.getUser().getId() == null || !goal.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Savings not found.");
        }

        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        if (goal.getStatus() != SavingsStatus.ACTIVE
                || goal.getMaturityDate() == null
                || !today.isBefore(goal.getMaturityDate())) {
            throw new IllegalStateException("This savings can no longer be changed.");
        }

        boolean changed = false;

        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty() || name.length() > 100) {
                throw new IllegalArgumentException("Enter a savings name.");
            }
            if (!name.equalsIgnoreCase(goal.getName())
                    && savingsGoalRepository.existsByUserIdAndNameIgnoreCaseAndStatusInAndIdNot(
                            userId, name, OPEN_EVENT_STATUSES, goal.getId())) {
                throw new IllegalArgumentException("You already have a savings named '" + name + "'");
            }
            goal.setName(name);
            changed = true;
        }

        if (request.getTargetAmount() != null) {
            if (request.getTargetAmount().compareTo(new BigDecimal("1000")) < 0) {
                throw new IllegalArgumentException("Amount needed must be at least ₦1,000");
            }
            goal.setTargetAmount(request.getTargetAmount());
            changed = true;
        }

        if (request.getCategory() != null) {
            EventCategory category = EventCategory.parse(request.getCategory());
            if (category == null) {
                throw new IllegalArgumentException("Choose what this savings is.");
            }
            goal.setCategory(category);
            changed = true;
        }

        if (request.getRemindWeek() != null) {
            goal.setRemindWeek(request.getRemindWeek());
            changed = true;
        }
        if (request.getRemindDayBefore() != null) {
            goal.setRemindDayBefore(request.getRemindDayBefore());
            changed = true;
        }
        if (request.getRemindMorning() != null) {
            goal.setRemindMorning(request.getRemindMorning());
            changed = true;
        }

        if (request.getMaturityDate() != null) {
            LocalDate next = request.getMaturityDate();
            if (!next.isAfter(goal.getMaturityDate())) {
                throw new IllegalArgumentException("The savings day can only move to a later date.");
            }
            long days = ChronoUnit.DAYS.between(today, next);
            if (days > 7) {
                goal.setMaturityReminderSent(false);
            }
            if (days > 1) {
                goal.setMaturityEveReminderSent(false);
            }
            goal.setMorningReminderSent(false);
            goal.setMaturityDate(next);
            changed = true;
        }

        if (!changed) {
            throw new IllegalArgumentException("Nothing to change.");
        }

        SavingsGoal saved = savingsGoalRepository.save(goal);
        savingsCacheService.evictUserSavingsCachesAfterCommit(userId);
        return saved;
    }

    /**
     * Moves envelope money into an open event. Callers already hold a transaction.
     * This method has no transaction of its own: a scheduled activation wraps it in a
     * savepoint, and a failure there must not mark the budget start as rollback-only.
     */
    public void sweepEnvelopeToSavings(Long userId, Long savingsGoalId, BigDecimal amount,
                                       String envelopeName, Long budgetId, Long envelopeId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return;

        SavingsGoal goal = savingsGoalRepository.findByIdForUpdate(savingsGoalId)
                .orElseThrow(() -> new RuntimeException("Savings Goal not found"));

        if (!goal.getUser().getId().equals(userId)) {
            throw new SecurityException("Savings goal does not belong to this user.");
        }

        if (goal.getStatus() != SavingsStatus.ACTIVE) {
            throw new IllegalStateException("This savings is no longer open for new money.");
        }

        goal.setCurrentBalance(goal.getCurrentBalance().add(amount));
        savingsGoalRepository.save(goal);
        savingsCacheService.evictUserSavingsCachesAfterCommit(userId);

        TransactionLog log = new TransactionLog();
        log.setUserId(userId);
        log.setBudgetId(budgetId);
        log.setSourceEnvelopeId(envelopeId);
        log.setAmount(amount);
        log.setTransactionType(TransactionType.SAVINGS_DEPOSIT);
        log.setDescription("Swept from Budget Envelope: " + envelopeName);
        log.setStatus(TransactionStatus.COMPLETED);
        log.setReference("SWEEP-" + UUID.randomUUID().toString());
        log.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(log);

        logger.info("Swept ₦{} from envelope '{}' (budget={}) into Savings Goal {}", amount, envelopeName, budgetId, savingsGoalId);

        notifyEvent(userId, goal.getId(), goal.getName(),
                String.format("₦%,.2f moved from your '%s' envelope into '%s'.",
                        amount, envelopeName, goal.getName()),
                NotificationType.SAVINGS_DEPOSIT);
    }

    @Transactional
    public List<SavingsGoal> getActiveSavingsForUser(Long userId) {
        var cached = savingsCacheService.getActiveSavings(userId);
        if (cached.isPresent()) {
            return cached.get();
        }

        reconcileMaturedGoals(userId);
        List<SavingsGoal> goals = savingsGoalRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, SavingsStatus.ACTIVE);
        savingsCacheService.putActiveSavings(userId, goals);
        return goals;
    }

    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal triggerEnvelopeSweep(String userEmail, Long envelopeId) {
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, userEmail)
                .orElseThrow(() -> new IllegalArgumentException("Envelope not found or does not belong to you."));

        Map<String, Object> conditions = envelope.getConditions();
        if (!"savings_sweep".equals(conditions.get("type"))) {
            throw new IllegalStateException("This envelope is not linked to a savings goal.");
        }

        Object goalIdObj = conditions.get("targetSavingsGoalId");
        if (goalIdObj == null) {
            throw new IllegalStateException("This envelope has no linked savings goal.");
        }
        Long savingsGoalId = ((Number) goalIdObj).longValue();

        BigDecimal amount = envelope.getTotalRemainingAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("No funds available to sweep.");
        }

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Long budgetId = envelope.getBudget().getId();

        sweepEnvelopeToSavings(user.getId(), savingsGoalId, amount, envelope.getName(), budgetId, envelopeId);

        envelope.setTotalRemainingAmount(BigDecimal.ZERO);
        envelope.setRemainingAmount(BigDecimal.ZERO);
        envelopeRepository.save(envelope);

        return savingsGoalRepository.findById(savingsGoalId)
                .orElseThrow(() -> new RuntimeException("Savings goal not found after sweep."));
    }

    @Transactional
    public List<SavingsGoal> getAllSavingsForUser(Long userId) {
        var cached = savingsCacheService.getAllSavings(userId);
        if (cached.isPresent()) {
            return cached.get();
        }

        reconcileMaturedGoals(userId);
        List<SavingsGoal> goals = savingsGoalRepository.findByUserIdOrderByCreatedAtDesc(userId);
        savingsCacheService.putAllSavings(userId, goals);
        return goals;
    }

    /** Full withdrawal — kept for existing callers; delegates to the amount-aware version. */
    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal withdrawSavings(Long userId, Long savingsGoalId) {
        return withdrawSavings(userId, savingsGoalId, null);
    }

    /**
     * Withdraws from a MATURED pot to the wallet. {@code requestedAmount} null =
     * the full payout (original behavior). A partial amount leaves the pot
     * MATURED with the remainder still withdrawable; interest folds into the
     * balance on the first withdrawal (same math as the P2P path). The pot flips
     * to WITHDRAWN only when emptied.
     */
    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal withdrawSavings(Long userId, Long savingsGoalId, BigDecimal requestedAmount) {
        SavingsGoal goal = savingsGoalRepository.findByIdForUpdate(savingsGoalId)
                .orElseThrow(() -> new IllegalArgumentException("Savings goal not found"));

        if (!goal.getUser().getId().equals(userId)) {
            throw new SecurityException("You do not have permission to withdraw this savings goal.");
        }

        if (goal.getStatus() != SavingsStatus.MATURED) {
            throw new IllegalStateException("Only matured savings goals can be withdrawn. Current status: " + goal.getStatus());
        }

        BigDecimal principal = goal.getCurrentBalance() != null ? goal.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal interest  = goal.getAccruedInterest() != null ? goal.getAccruedInterest() : BigDecimal.ZERO;
        BigDecimal available = principal.add(interest);

        BigDecimal amount = requestedAmount != null ? requestedAmount : available;
        boolean isFull = amount.compareTo(available) == 0;

        // Explicit partial requests must be positive; a FULL withdrawal of an
        // empty pot stays allowed — it simply closes the pot (original behavior).
        if (requestedAmount != null && requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be greater than zero.");
        }
        if (amount.compareTo(available) > 0) {
            throw new IllegalStateException(
                    String.format("Insufficient funds in this savings pot. Available: ₦%,.2f", available));
        }

        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            String msg = isFull
                    ? String.format("Savings withdrawal: %s (₦%,.2f principal + ₦%,.2f interest)",
                            goal.getName(), principal, interest)
                    : String.format("Partial savings withdrawal: %s (₦%,.2f of ₦%,.2f)",
                            goal.getName(), amount, available);
            // Suppress the wallet-side deposit log: the SAVINGS_WITHDRAWAL entry
            // below is now the single user-visible record for this move, so we
            // don't want a duplicate WALLET_DEPOSIT for the same ₦ making it look
            // like the money doubled.
            walletService.fundWallet(userId, amount, msg, true);
        }

        TransactionLog log = new TransactionLog();
        log.setUserId(userId);
        log.setAmount(amount);
        log.setTransactionType(TransactionType.SAVINGS_WITHDRAWAL);
        log.setDescription((isFull ? "Withdrawal from matured savings: " : "Partial withdrawal from matured savings: ")
                + goal.getName());
        log.setStatus(TransactionStatus.COMPLETED);
        log.setReference("SAVE-OUT-" + UUID.randomUUID().toString());
        log.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(log);

        // Interest folds into the balance on the first withdrawal; the pot stays
        // MATURED (still withdrawable) until it is actually empty.
        BigDecimal remaining = available.subtract(amount);
        goal.setAccruedInterest(BigDecimal.ZERO);
        goal.setCurrentBalance(remaining);
        if (remaining.compareTo(BigDecimal.ZERO) == 0) {
            goal.setStatus(SavingsStatus.WITHDRAWN);
        }
        SavingsGoal saved = savingsGoalRepository.save(goal);
        savingsCacheService.evictUserSavingsCachesAfterCommit(userId);

        logger.info("User {} withdrew ₦{} ({}) from savings goal {} ({})",
                userId, amount, isFull ? "full" : "partial", savingsGoalId, goal.getName());
        return saved;
    }

    /**
     * Early-breaks an ACTIVE savings pot during account closure: returns the
     * principal to the wallet and forfeits any accrued (unvested) bonus, then
     * marks the pot CANCELLED. This is the deliberate "I'm leaving" exception to
     * the maturity lock, used only by the account-deletion flow so a departing
     * user can always recover their principal — never the bonus. ACTIVE only:
     * MATURED pots go through withdrawSavings; anything else is a no-op.
     */
    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal breakActiveSavingsToWallet(Long userId, Long savingsGoalId) {
        SavingsGoal goal = savingsGoalRepository.findByIdForUpdate(savingsGoalId)
                .orElseThrow(() -> new IllegalArgumentException("Savings goal not found"));

        if (!goal.getUser().getId().equals(userId)) {
            throw new SecurityException("You do not have permission to break this savings goal.");
        }
        if (goal.getStatus() != SavingsStatus.ACTIVE) {
            return goal; // only ACTIVE pots break early; others are handled elsewhere / no-op
        }

        BigDecimal principal = goal.getCurrentBalance() != null ? goal.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal forfeitedBonus = goal.getAccruedInterest() != null ? goal.getAccruedInterest() : BigDecimal.ZERO;

        if (principal.compareTo(BigDecimal.ZERO) > 0) {
            // Suppress the wallet-side deposit log (pass true) — the SAVINGS_WITHDRAWAL
            // entry below is the single user-visible record for this move.
            walletService.fundWallet(userId, principal,
                    "Early savings break on account closure: " + goal.getName(), true);

            TransactionLog log = new TransactionLog();
            log.setUserId(userId);
            log.setAmount(principal);
            log.setTransactionType(TransactionType.SAVINGS_WITHDRAWAL);
            log.setDescription("Early savings break on account closure: " + goal.getName()
                    + (forfeitedBonus.compareTo(BigDecimal.ZERO) > 0
                            ? String.format(" (₦%,.2f bonus forfeited)", forfeitedBonus) : ""));
            log.setStatus(TransactionStatus.COMPLETED);
            log.setReference("SAVE-BREAK-" + UUID.randomUUID());
            log.setCreatedAt(LocalDateTime.now());
            transactionLogRepository.save(log);
        }

        // Bonus is forfeited on an early break; principal has moved to the wallet.
        goal.setAccruedInterest(BigDecimal.ZERO);
        goal.setCurrentBalance(BigDecimal.ZERO);
        goal.setStatus(SavingsStatus.CANCELLED);
        SavingsGoal saved = savingsGoalRepository.save(goal);
        savingsCacheService.evictUserSavingsCachesAfterCommit(userId);

        logger.info("User {} early-broke ACTIVE savings goal {} ({}) on account closure — ₦{} principal to wallet, ₦{} bonus forfeited",
                userId, savingsGoalId, goal.getName(), principal, forfeitedBonus);
        return saved;
    }

    /**
     * P2P from a MATURED savings pot straight to another Wisemonie user.
     * Faithful adaptation of EnvelopeService.transferToMonieWiseUser with the
     * savings pot as the source instead of an envelope:
     * <ul>
     *   <li>Same PIN verification, recipient resolution and self-transfer guard.</li>
     *   <li>Same two money paths: Rubies-to-Rubies internal book transfer
     *       (recipient credited by the CR webhook — never here, to avoid the
     *       double-credit) or the internal DB-only ledger path.</li>
     *   <li>Partial sends allowed. Accrued interest is folded into the balance on
     *       the first send; when the pot empties it flips to WITHDRAWN.</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal transferSavingsToUser(Long userId, Long savingsGoalId,
                                             SavingsP2PTransferRequest request) {
        BigDecimal amount = request.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        User sender = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        if (request.getTransactionPin() == null || request.getTransactionPin().isBlank()) {
            throw new IllegalArgumentException("Transaction PIN is required");
        }
        if (!userService.verifyTransactionPin(sender, request.getTransactionPin())) {
            throw new IllegalArgumentException("Invalid transaction PIN");
        }

        User recipient = userService.findByEmailOrPhone(request.getRecipientIdentity())
                .orElseThrow(() -> new EntityNotFoundException("Recipient not found"));

        if (sender.getId().equals(recipient.getId())) {
            throw new IllegalArgumentException("You cannot transfer to yourself.");
        }

        SavingsGoal goal = savingsGoalRepository.findByIdForUpdate(savingsGoalId)
                .orElseThrow(() -> new EntityNotFoundException("Savings goal not found"));

        if (!goal.getUser().getId().equals(userId)) {
            throw new SecurityException("You do not have permission to withdraw this savings goal.");
        }
        if (goal.getStatus() != SavingsStatus.MATURED) {
            throw new IllegalStateException(
                    "Only matured savings can be sent to a friend. Current status: " + goal.getStatus());
        }

        BigDecimal principal = goal.getCurrentBalance() != null ? goal.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal interest  = goal.getAccruedInterest() != null ? goal.getAccruedInterest() : BigDecimal.ZERO;
        BigDecimal available = principal.add(interest);

        if (amount.compareTo(available) > 0) {
            throw new IllegalStateException(
                    String.format("Insufficient funds in this savings pot. Available: ₦%,.2f", available));
        }

        String senderName    = walletService.resolveDisplayName(sender);
        String recipientName = walletService.resolveDisplayName(recipient);

        boolean providerBackedP2p = shouldUseProviderBackedP2p(sender, recipient);
        String providerReference = null;
        String logRefPrefix    = "P2P-SV-DB-";
        String logCrRefPrefix  = "P2P-SV-CR-";
        String logProviderName = null;

        if (providerBackedP2p) {
            // ── Rubies-to-Rubies internal book transfer ──────────────────────────
            // The pot's money physically sits in the sender's Rubies wallet (the
            // app-level pot is a ledger partition), so the PSP moves wallet→wallet.
            Wallet senderWallet    = walletService.getWalletByUserId(sender.getId());
            Wallet recipientWallet = walletService.getWalletByUserId(recipient.getId());

            String p2pReference = "P2P-RB-SV-" + sender.getId() + "-" + System.currentTimeMillis();

            logger.info("[P2P-SAVINGS-RUBIES] Initiating internal transfer ref={} from={} to={} amount={}",
                    p2pReference, senderWallet.getProviderWalletRef(),
                    recipientWallet.getProviderWalletRef(), amount);

            PaymentGateway gateway = paymentGatewayResolver.resolveByProviderName(RubiesGateway.PROVIDER_NAME);
            try {
                providerReference = gateway.initiateTransferWithContext(
                        senderWallet.getProviderWalletRef(),
                        walletService.resolveDisplayName(sender),
                        RUBIES_BANK_CODE,
                        RUBIES_BANK_NAME,
                        recipientWallet.getProviderWalletRef(),
                        walletService.resolveDisplayName(recipient),
                        amount,
                        p2pReference,
                        "Wisemonie P2P (savings): " + senderName + " to " + recipientName
                );
            } catch (RuntimeException ex) {
                // Sanitise Rubies float-related errors — don't expose internal float state to users.
                String rawCause = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
                if (rawCause.contains("insufficient float")
                        || rawCause.contains("not enough float")
                        || (rawCause.contains("insufficient balance") && rawCause.contains("rubies"))) {
                    throw new RuntimeException(
                            "Transfer temporarily unavailable. Please try again in a few minutes or contact support.");
                }
                throw ex;
            }

            // DO NOT credit the recipient here — the Rubies CR webhook does it.
            logRefPrefix    = "P2P-RB-SV-DB-";
            logCrRefPrefix  = "P2P-RB-SV-CR-";
            logProviderName = RubiesGateway.PROVIDER_NAME;

            logger.info("[P2P-SAVINGS-RUBIES] Transfer accepted: ref={} sessionId={}", p2pReference, providerReference);
        } else {
            // ── Internal DB-only path ────────────────────────────────────────────
            walletService.fundWallet(recipient.getId(), amount, null, true);
        }

        // Debit the pot: interest folds into the balance on the first send;
        // an emptied pot flips to WITHDRAWN (same terminal state as withdraw).
        BigDecimal remaining = available.subtract(amount);
        goal.setAccruedInterest(BigDecimal.ZERO);
        goal.setCurrentBalance(remaining);
        if (remaining.compareTo(BigDecimal.ZERO) == 0) {
            goal.setStatus(SavingsStatus.WITHDRAWN);
        }
        SavingsGoal saved = savingsGoalRepository.save(goal);
        savingsCacheService.evictUserSavingsCachesAfterCommit(userId);

        String baseRef = providerReference != null && !providerReference.isBlank()
                ? providerReference
                : recipient.getId() + "-" + System.currentTimeMillis();

        String description = request.getNote() != null && !request.getNote().isBlank()
                ? request.getNote()
                : "Sent to " + recipientName + " from savings: " + goal.getName();

        LocalDateTime now = LocalDateTime.now();

        TransactionLog senderLog = TransactionLog.builder()
                .userId(sender.getId())
                .counterpartyUserId(recipient.getId())
                .amount(amount.negate())
                .fee(BigDecimal.ZERO)
                .transactionType(TransactionType.SAVINGS_WITHDRAWAL)
                .status(TransactionStatus.COMPLETED)
                .reference(logRefPrefix + baseRef)
                .providerName(logProviderName)
                .providerReference(providerReference)
                .description(description)
                .createdAt(now)
                .build();
        transactionLogRepository.save(senderLog);

        TransactionLog recipientLog = TransactionLog.builder()
                .userId(recipient.getId())
                .counterpartyUserId(sender.getId())
                .amount(amount)
                .fee(BigDecimal.ZERO)
                .transactionType(TransactionType.USER_TO_USER)
                .status(TransactionStatus.COMPLETED)
                .reference(logCrRefPrefix + baseRef)
                .providerName(logProviderName)
                .providerReference(providerReference)
                .description("Received from " + senderName)
                .createdAt(now)
                .build();
        transactionLogRepository.save(recipientLog);

        // Sender notification (outbox — commits atomically with this transfer).
        notificationService.sendNotification(
                sender.getId().toString(),
                String.format("You sent ₦%,.2f to %s from your savings '%s'.%s",
                        amount, recipientName, goal.getName(),
                        remaining.compareTo(BigDecimal.ZERO) > 0
                                ? String.format(" ₦%,.2f left in the pot.", remaining)
                                : " The pot is now closed."),
                NotificationType.EXTERNAL_TRANSFER,
                null,
                null,
                "VIEW_SAVINGS",
                "/savings/" + goal.getId()
        );

        // Recipient notification — only on the internal path; Rubies P2P recipient
        // alerts are sent by the CR webhook after the balance is actually credited.
        if (!providerBackedP2p) {
            notificationService.sendNotification(
                    recipient.getId().toString(),
                    String.format("%s sent you ₦%,.2f.", senderName, amount),
                    NotificationType.WALLET_DEPOSIT,
                    null,
                    null,
                    "VIEW_WALLET",
                    "/dashboard"
            );
        }

        try {
            beneficiaryService.addBeneficiary(sender.getId(), recipient.getEmail(), recipientName);
        } catch (Exception e) {
            // Beneficiary bookkeeping must never fail the transfer.
        }

        logger.info("User {} sent ₦{} from savings goal {} ({}) to user {}",
                userId, amount, savingsGoalId, goal.getName(), recipient.getId());
        return saved;
    }

    /** Rubies-to-Rubies pairs use the provider-backed book transfer; anything else falls back to the internal ledger path. */
    private boolean shouldUseProviderBackedP2p(User sender, User recipient) {
        try {
            Wallet senderWallet    = walletService.getWalletByUserId(sender.getId());
            Wallet recipientWallet = walletService.getWalletByUserId(recipient.getId());
            return senderWallet    != null
                && recipientWallet != null
                && RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(senderWallet.getProviderName())
                && RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(recipientWallet.getProviderName());
        } catch (Exception e) {
            logger.warn("[P2P-SAVINGS] Could not determine provider-backed eligibility — falling back to internal path: {}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * Send money from a MATURED savings pot straight to an external bank account.
     *
     * <p>This deliberately does NOT touch the envelope external-transfer rail.
     * Instead it composes existing, tested pieces: it debits the pot, credits the
     * user's wallet (the pot's funds physically live there), then hands off to
     * {@link WalletService#processWithdrawal} — which already verifies the PIN,
     * re-verifies the destination account name with the bank, quotes fees, and
     * runs the proven settlement/reversal lifecycle. So no new async/webhook code
     * exists here and nothing that already works is modified.
     *
     * <p>Safety: everything runs in one transaction. If the withdrawal throws
     * (bad account, insufficient fee balance, provider reject) the whole thing
     * rolls back and the pot is restored. If it later fails at the provider
     * webhook, the existing withdrawal reversal returns the funds to the wallet —
     * never lost. Matured-only; partial allowed; the pot flips to WITHDRAWN when
     * emptied. {@code request} is the same WithdrawalRequest the wallet endpoint
     * uses (amount + inline destination bank + PIN).
     */
    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal transferSavingsToBank(Long userId, Long savingsGoalId, WithdrawalRequest request) {
        BigDecimal amount = request.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        SavingsGoal goal = savingsGoalRepository.findByIdForUpdate(savingsGoalId)
                .orElseThrow(() -> new EntityNotFoundException("Savings goal not found"));

        if (!goal.getUser().getId().equals(userId)) {
            throw new SecurityException("You do not have permission to withdraw this savings goal.");
        }
        if (goal.getStatus() != SavingsStatus.MATURED) {
            throw new IllegalStateException(
                    "Only matured savings can be sent to a bank. Current status: " + goal.getStatus());
        }

        BigDecimal principal = goal.getCurrentBalance() != null ? goal.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal interest  = goal.getAccruedInterest() != null ? goal.getAccruedInterest() : BigDecimal.ZERO;
        BigDecimal available = principal.add(interest);

        BigDecimal flatFee = savingsBankTransferFee(amount);
        BigDecimal nipFee  = markupCalculatorService.calculateNipFee(amount);
        BigDecimal revenue = flatFee.subtract(nipFee).max(BigDecimal.ZERO);
        BigDecimal totalFromPot = amount.add(flatFee);

        if (totalFromPot.compareTo(available) > 0) {
            BigDecimal maxSendable = available.subtract(flatFee).max(BigDecimal.ZERO);
            throw new IllegalStateException(
                    String.format("Insufficient funds. Transfer fee ₦%,.2f · Max sendable ₦%,.2f",
                            flatFee, maxSendable));
        }

        // 1) Debit the pot: transfer amount + flat fee. Interest folds into the
        //    balance on the first exit; emptying the pot closes it.
        BigDecimal remaining = available.subtract(totalFromPot);
        goal.setAccruedInterest(BigDecimal.ZERO);
        goal.setCurrentBalance(remaining);
        if (remaining.compareTo(BigDecimal.ZERO) == 0) {
            goal.setStatus(SavingsStatus.WITHDRAWN);
        }
        savingsGoalRepository.save(goal);
        savingsCacheService.evictUserSavingsCachesAfterCommit(userId);

        // 2) Credit the wallet with amount + flatFee so processWithdrawal can debit
        //    the exact totalDebit (amount + nipFee + revenue) without shortfall.
        walletService.fundWallet(userId, totalFromPot,
                "Savings withdrawal to bank: " + goal.getName(), true);

        // 3) Hand off to the tested wallet withdrawal with the savings-specific fees.
        //    revenue goes to Moniewise; nipFee is auto-deducted by Rubies.
        walletService.processWithdrawal(userId, request, revenue, nipFee);

        logger.info("User {} sent ₦{} (fee ₦{}) from savings goal {} ({}) to external bank {} / {}",
                userId, amount, flatFee, savingsGoalId, goal.getName(),
                request.getBankCode(), request.getAccountNumber());
        return goal;
    }

    public static BigDecimal savingsBankTransferFee(BigDecimal amount) {
        return amount.compareTo(SAVINGS_BANK_FEE_THRESHOLD) < 0
                ? SAVINGS_BANK_FEE_UNDER_50K
                : SAVINGS_BANK_FEE_50K_AND_ABOVE;
    }

    /**
     * Phase 4: Manual "Ad-Hoc" Top-Up directly from Wallet
     */
    @Transactional(rollbackFor = Exception.class)
    public SavingsGoal manualTopUp(Long userId, Long savingsGoalId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Top-up amount must be greater than zero.");
        }

        SavingsGoal preview = savingsGoalRepository.findById(savingsGoalId)
                .orElseThrow(() -> new IllegalArgumentException("Savings Goal not found"));
        if (preview.getUser() == null || preview.getUser().getId() == null
                || !preview.getUser().getId().equals(userId)) {
            throw new SecurityException("You do not have permission to fund this savings goal.");
        }
        if (preview.getStatus() != SavingsStatus.ACTIVE) {
            throw new IllegalStateException("This savings is no longer open for new money.");
        }

        // Debit the wallet before locking the event. The wallet is locked first
        // everywhere else money leaves it, and this whole method rolls back together.
        walletService.debitWalletForWithdrawal(userId, amount, "top up this savings");

        SavingsGoal goal = savingsGoalRepository.findByIdForUpdate(savingsGoalId)
                .orElseThrow(() -> new IllegalArgumentException("Savings Goal not found"));
        if (goal.getUser() == null || goal.getUser().getId() == null
                || !goal.getUser().getId().equals(userId)) {
            throw new SecurityException("You do not have permission to fund this savings goal.");
        }
        if (goal.getStatus() != SavingsStatus.ACTIVE) {
            throw new IllegalStateException("This savings is no longer open for new money.");
        }

        goal.setCurrentBalance(goal.getCurrentBalance().add(amount));
        SavingsGoal updatedGoal = savingsGoalRepository.save(goal);
        savingsCacheService.evictUserSavingsCachesAfterCommit(userId);
        savingsProjectionWebSocketService.sendTopUpProjectionAfterCommit(
                goal.getUser().getEmail(),
                userId,
                updatedGoal,
                amount
        );

        // 3. Log the transaction
        TransactionLog log = new TransactionLog();
        log.setUserId(userId);
        log.setAmount(amount);
        log.setTransactionType(TransactionType.SAVINGS_DEPOSIT);
        log.setDescription("Manual top-up to Savings: " + goal.getName());
        log.setStatus(TransactionStatus.COMPLETED);
        log.setReference("SAVE-TOPUP-" + UUID.randomUUID().toString());
        log.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(log);

        logger.info("User {} manually topped up ₦{} into Savings Goal {}", userId, amount, savingsGoalId);

        // 4. Push: money added to the pot from the wallet.
        notifyEvent(userId, goal.getId(), goal.getName(),
                String.format("₦%,.2f added to '%s'. It stays locked until the day.",
                        amount, goal.getName()),
                NotificationType.SAVINGS_DEPOSIT);

        // 5. Celebrate hitting the target.
        if (updatedGoal.getCurrentBalance().compareTo(updatedGoal.getTargetAmount()) >= 0) {
            logger.info("🎉 User {} just hit their savings target for {}!", userId, goal.getName());
            notifyEvent(userId, goal.getId(), goal.getName(),
                    String.format("You reached the ₦%,.2f amount for '%s'. The money stays locked until the day.",
                            updatedGoal.getTargetAmount(), goal.getName()),
                    NotificationType.GOAL_ACHIEVED);
        }

        return updatedGoal;
    }
}
