package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.AccountClosureEmailScenario;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.enums.WithdrawalStatus;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.OutboxEventRepository;
import com.moniewise.moniewise_backend.repository.PendingDisbursementRepository;
import com.moniewise.moniewise_backend.repository.SavingsGoalRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.repository.WithdrawalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates account deletion as a deliberate "I'm leaving" flow — the one
 * place a commitment can be unwound. It reuses the already-tested refund and
 * withdraw paths (so no money logic is reinvented) and never lets a user lose
 * money: leaving returns every locked naira (savings principal, budget balances)
 * to the wallet first, forfeiting only unvested savings bonus.
 */
@Service
public class AccountDeletionService {

    private static final Logger logger = LoggerFactory.getLogger(AccountDeletionService.class);

    /** ₦100 withdrawal minimum. Below this a wallet balance is un-withdrawable
     *  dust that must not permanently block deletion. */
    private static final BigDecimal MIN_WITHDRAWABLE_BALANCE = new BigDecimal("100");

    /** A withdrawal left INITIATED/PROCESSING longer than this is treated as stuck
     *  (e.g. a lost provider webhook) so it can never permanently block account
     *  closure. NIP transfers settle in seconds–minutes, so this is deliberately
     *  generous; account deletion is a soft delete and the withdrawal record
     *  survives it, so proceeding past a stale one loses nothing. */
    private static final Duration WITHDRAWAL_INFLIGHT_WINDOW = Duration.ofHours(2);

    public enum Outcome { DELETED, WITHDRAWAL_REQUIRED }

    public record Result(Outcome outcome, BigDecimal walletBalance) {}

    private final NotificationService notificationService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final BudgetService budgetService;
    private final SavingsService savingsService;
    private final WalletService walletService;
    private final WalletRepository walletRepository;
    private final BudgetRepository budgetRepository;
    private final SavingsGoalRepository savingsGoalRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final PendingDisbursementRepository pendingDisbursementRepository;
    private final OutboxEventRepository outboxEventRepository;

    public AccountDeletionService(NotificationService notificationService,
                                  UserService userService, UserRepository userRepository,
                                  BudgetService budgetService, SavingsService savingsService,
                                  WalletService walletService, WalletRepository walletRepository,
                                  BudgetRepository budgetRepository,
                                  SavingsGoalRepository savingsGoalRepository,
                                  WithdrawalRepository withdrawalRepository,
                                  PendingDisbursementRepository pendingDisbursementRepository,
                                  OutboxEventRepository outboxEventRepository) {
        this.notificationService = notificationService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.budgetService = budgetService;
        this.savingsService = savingsService;
        this.walletService = walletService;
        this.walletRepository = walletRepository;
        this.budgetRepository = budgetRepository;
        this.savingsGoalRepository = savingsGoalRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.pendingDisbursementRepository = pendingDisbursementRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Attempts to close the account. Blocks only while an async withdrawal is
     * still settling; otherwise it breaks active savings (principal to the wallet,
     * accrued bonus forfeited), dissolves active budgets, and rakes matured
     * savings into the wallet, then either closes the
     * account (nothing withdrawable left) or returns WITHDRAWAL_REQUIRED so the
     * client can send the consolidated balance to the user's bank first.
     *
     * <p>Idempotent and async-confirmed: the client calls it again after the
     * withdrawal clears — the in-flight guard means it will only finalize once
     * the transfer has actually COMPLETED (or FAILED, in which case the money is
     * back in the wallet and it asks for withdrawal again).
     */
    @Transactional
    public Result deleteAccount(String email, String reason, String transactionPin) {
        User user = userService.findByEmail(email);
        Long userId = user.getId();

        // Closing an account moves real money — it breaks savings, dissolves
        // budgets and then empties the wallet — so prove it is the account owner
        // first, with the same transaction PIN every other money movement needs.
        if (transactionPin == null || transactionPin.isBlank()) {
            throw new IllegalArgumentException("Transaction PIN is required to close your account.");
        }
        if (!userService.verifyTransactionPin(user, transactionPin)) {
            throw new IllegalArgumentException("Incorrect transaction PIN.");
        }

        // Async-confirmed close: never finalize (or dissolve further) while a
        // withdrawal is genuinely still settling — wait for it to COMPLETE or FAIL.
        // Bounded by a staleness window so a stuck INITIATED/PROCESSING withdrawal
        // (e.g. a lost provider webhook) can't trap the account closed forever.
        LocalDateTime inFlightCutoff = LocalDateTime.now().minus(WITHDRAWAL_INFLIGHT_WINDOW);
        boolean withdrawalInFlight = withdrawalRepository.existsByUserIdAndStatusInAndCreatedAtAfter(
                userId, List.of(WithdrawalStatus.INITIATED, WithdrawalStatus.PROCESSING), inFlightCutoff);
        if (withdrawalInFlight) {
            throw new IllegalStateException(
                    "A withdrawal is still processing. Please try again once it completes.");
        }

        AccountClosureEmailScenario closureScenario = determineClosureScenario(userId);
        storeClosureFeedbackContext(user, reason, closureScenario);

        // Break ACTIVE (still-maturing) savings on the way out: principal returns
        // to the wallet, any accrued bonus is forfeited. Account closure is the one
        // place the maturity lock is lifted, so a departing user can always recover
        // their principal.
        for (SavingsGoal goal : savingsGoalRepository.findByUserIdAndStatus(userId, SavingsStatus.ACTIVE)) {
            savingsService.breakActiveSavingsToWallet(userId, goal.getId());
        }

        // Dissolve active budgets → wallet (refund unused balance, then remove).
        for (Budget budget : budgetRepository.findByUserIdAndStatus(userId, BudgetStatus.ACTIVE)) {
            budgetService.deleteBudget(budget.getId(), email);
        }
        for (Budget budget : budgetRepository.findByUserIdAndStatus(userId, BudgetStatus.SCHEDULED)) {
            budgetService.deleteBudget(budget.getId(), email);
        }

        // Prevent ghost notifications: delete any pending disbursement records and
        // cancel undelivered outbox events so the departing user never receives
        // stale disbursement/expiry alerts after their account is closed.
        pendingDisbursementRepository.deleteByUserId(userId);
        outboxEventRepository.cancelPendingByUserId(userId);

        // Rake matured (unwithdrawn) savings → wallet.
        for (SavingsGoal goal : savingsGoalRepository.findByUserIdAndStatus(userId, SavingsStatus.MATURED)) {
            savingsService.withdrawSavings(userId, goal.getId(), null);
        }

        // Everything is now consolidated in the wallet. A withdrawable balance
        // (>= ₦100) must go to the user's bank via the normal withdraw flow
        // first — we don't close until it's gone. Sub-₦100 dust is
        // un-withdrawable and does not block; it stays in the soft-deleted wallet
        // and is recoverable if the user reactivates.
        BigDecimal balance = safeBalance(userId);
        if (balance.compareTo(MIN_WITHDRAWABLE_BALANCE) >= 0) {
            // Their intent is captured and PIN-verified NOW. Stamp it, so
            // AccountClosureFinalizerJob can finish the closure by itself once
            // the wallet empties — the user must never be left with dissolved
            // budgets, broken savings and a still-open account because they
            // withdrew and never came back to tap Delete a second time.
            // Preserve the ORIGINAL request time across repeat taps so the
            // grace period is measured from when they first asked.
            if (user.getClosureRequestedAt() == null) {
                user.setClosureRequestedAt(LocalDateTime.now());
                userRepository.save(user);
                logger.info("Closure pending (withdrawal required) for {} — balance ₦{}", email, balance);
            }
            return new Result(Outcome.WITHDRAWAL_REQUIRED, balance);
        }

        finalizeClosure(user, reason);
        return new Result(Outcome.DELETED, balance);
    }

    /**
     * The single place an account actually closes. Every route here — the user's
     * second tap, the finalizer job once their wallet empties, and the
     * grace-period expiry — goes through this method, so the farewell email and
     * the pending-flag cleanup can never diverge between paths.
     */
    @Transactional
    public void finalizeClosure(User user, String reason) {
        user.setDeleted(true);
        user.setClosureRequestedAt(null);   // no longer pending — it's done
        userRepository.save(user);
        logger.info("Soft-deleted user account: {}", user.getEmail());

        // Async + best-effort: a mail failure must never undo a closure.
        try {
            notificationService.sendAccountClosedEmail(
                    user.getEmail(),
                    firstNameOf(user),
                    resolveReason(user, reason),
                    resolveClosureScenario(user));
        } catch (Exception e) {
            logger.error("Farewell email failed for {}", user.getEmail(), e);
        }
    }

    /**
     * Finishes a closure the moment the client confirms the closure withdrawal,
     * so the app can log the user straight out with the account already closed
     * instead of waiting on the hourly finalizer. Requires the pending-closure
     * state (they have already confirmed with their PIN and consolidated their
     * money in {@link #deleteAccount}); idempotent.
     *
     * <p>Deliberately skips the in-flight-withdrawal guard that {@code
     * deleteAccount} runs: the closure withdrawal this fires after is expected to
     * be PROCESSING. The money has already left the wallet toward the user's
     * bank, so closing now is correct and the transfer settles on its own.
     */
    @Transactional
    public void finalizeDeletion(String email) {
        User user = userRepository.findFirstByEmailOrderByCreatedAtAsc(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (user.isDeleted()) {
            return; // already closed — idempotent, safe to call more than once
        }
        if (user.getClosureRequestedAt() == null) {
            // Never let this close a healthy account: only one already in the
            // PIN-confirmed pending-closure state can be finalized here.
            throw new IllegalStateException("No account closure is in progress.");
        }
        finalizeClosure(user, null);
    }

    /** First name for the farewell greeting, falling back to the email prefix. */
    private String firstNameOf(User user) {
        String name = user.getName();
        if (name != null && !name.isBlank()) return name.trim().split("\\s+")[0];
        String email = user.getEmail();
        if (email != null && email.contains("@")) {
            String prefix = email.substring(0, email.indexOf('@'));
            if (!prefix.isEmpty()) {
                return Character.toUpperCase(prefix.charAt(0)) + prefix.substring(1);
            }
        }
        return "there";
    }

    /**
     * The reason for the farewell email. On a job-driven close there is no
     * request body, so fall back to the reason stored at the first tap.
     */
    private String resolveReason(User user, String reason) {
        if (reason != null && !reason.isBlank()) return reason.trim();
        Map<String, Object> profile = user.getProfileData();
        if (profile != null && profile.get("deletionReason") != null) {
            return profile.get("deletionReason").toString();
        }
        return null;
    }

    private AccountClosureEmailScenario determineClosureScenario(Long userId) {
        Wallet wallet = walletRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId).orElse(null);
        if (wallet == null) {
            return AccountClosureEmailScenario.NO_WALLET;
        }

        boolean hasPlans = !budgetRepository.findByUserId(userId).isEmpty()
                || !savingsGoalRepository.findByUserIdOrderByCreatedAtDesc(userId).isEmpty();
        if (hasPlans) {
            return AccountClosureEmailScenario.USED_WISEMONIE;
        }

        BigDecimal balance = wallet.getBalance() != null ? wallet.getBalance() : BigDecimal.ZERO;
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            return AccountClosureEmailScenario.FUNDED_NO_PLAN;
        }

        return AccountClosureEmailScenario.WALLET_NOT_FUNDED;
    }

    private void storeClosureFeedbackContext(User user, String reason, AccountClosureEmailScenario scenario) {
        Map<String, Object> profile = user.getProfileData() != null
                ? new HashMap<>(user.getProfileData())
                : new HashMap<>();
        if (reason != null && !reason.isBlank()) {
            profile.put("deletionReason", reason.trim());
        }
        profile.put("deletionScenario", scenario.name());
        user.setProfileData(profile);
        userRepository.save(user);
    }

    private AccountClosureEmailScenario resolveClosureScenario(User user) {
        Map<String, Object> profile = user.getProfileData();
        if (profile == null || profile.get("deletionScenario") == null) {
            return AccountClosureEmailScenario.GENERAL;
        }
        try {
            return AccountClosureEmailScenario.valueOf(profile.get("deletionScenario").toString());
        } catch (IllegalArgumentException e) {
            return AccountClosureEmailScenario.GENERAL;
        }
    }

    private BigDecimal safeBalance(Long userId) {
        try {
            BigDecimal balance = walletService.checkBalance(userId);
            return balance == null ? BigDecimal.ZERO : balance;
        } catch (Exception ignored) {
            // No wallet / lookup issue — treat as no balance; never block on this.
            return BigDecimal.ZERO;
        }
    }
}
