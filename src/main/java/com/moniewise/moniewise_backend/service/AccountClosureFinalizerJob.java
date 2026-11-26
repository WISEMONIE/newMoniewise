package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Finishes account closures the user already asked for.
 *
 * <h3>The problem this exists to solve</h3>
 * Closing an account consolidates everything into the wallet — savings broken,
 * budgets dissolved — and then asks the user to withdraw before the account can
 * actually close. If they withdrew and never came back to tap Delete a second
 * time, they were left in the worst possible state: their commitments destroyed,
 * their money gone, and the account still fully open. They believe they closed
 * it; we never did.
 *
 * <p>The second tap was never about consent — consent was captured, behind a
 * transaction PIN, at the first tap. It only existed so <em>we</em> could
 * re-check the wallet. This job does that check instead, so the user is not
 * responsible for our bookkeeping.
 *
 * <h3>What it does</h3>
 * <ul>
 *   <li><b>Wallet emptied →</b> close immediately. This is the common case: they
 *       withdrew, and closure completes without them lifting a finger.</li>
 *   <li><b>Grace period expired →</b> close anyway. The money is NOT lost: a
 *       closure is a soft delete, so the wallet survives and is recoverable if
 *       they ever reactivate.</li>
 *   <li><b>Otherwise →</b> leave it pending and look again next run.</li>
 * </ul>
 */
@Service
public class AccountClosureFinalizerJob {

    private static final Logger logger = LoggerFactory.getLogger(AccountClosureFinalizerJob.class);

    /** Hourly. Closure is not time-critical — the user has already left. */
    private static final long POLL_INTERVAL_MS = 60 * 60 * 1000L;

    /**
     * How long a closure may sit pending before we close regardless. Long enough
     * that someone who withdraws days later still lands on the clean path, short
     * enough that a stated intent to leave isn't ignored indefinitely.
     */
    private static final Duration GRACE_PERIOD = Duration.ofDays(30);

    /** Matches AccountDeletionService: below this, a balance can't be withdrawn. */
    private static final BigDecimal MIN_WITHDRAWABLE_BALANCE = new BigDecimal("100");

    private final UserRepository userRepository;
    private final WalletService walletService;
    private final AccountDeletionService accountDeletionService;

    public AccountClosureFinalizerJob(UserRepository userRepository,
                                      WalletService walletService,
                                      AccountDeletionService accountDeletionService) {
        this.userRepository = userRepository;
        this.walletService = walletService;
        this.accountDeletionService = accountDeletionService;
    }

    /** {@code fixedDelay} (not {@code fixedRate}) so a slow batch never overlaps itself. */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void finalizePendingClosures() {
        List<User> pending = userRepository.findPendingClosures();
        if (pending.isEmpty()) {
            return; // keep quiet servers quiet
        }

        logger.info("[Closure] Checking {} pending account closure(s)", pending.size());
        LocalDateTime graceCutoff = LocalDateTime.now().minus(GRACE_PERIOD);

        for (User user : pending) {
            // One user's failure must never abort the batch.
            try {
                BigDecimal balance = safeBalance(user.getId());
                boolean walletEmptied = balance.compareTo(MIN_WITHDRAWABLE_BALANCE) < 0;
                boolean graceExpired = user.getClosureRequestedAt() != null
                        && user.getClosureRequestedAt().isBefore(graceCutoff);

                if (walletEmptied) {
                    logger.info("[Closure] Wallet cleared for {} — finalizing closure they requested on {}",
                            user.getEmail(), user.getClosureRequestedAt());
                    accountDeletionService.finalizeClosure(user, null);
                } else if (graceExpired) {
                    // Deliberate: honour the request rather than leave it hanging.
                    // The balance stays in the soft-deleted wallet and comes back
                    // with the account if they ever reactivate.
                    logger.warn("[Closure] Grace period expired for {} with ₦{} still in wallet — "
                                    + "closing anyway; balance remains recoverable on reactivation",
                            user.getEmail(), balance);
                    accountDeletionService.finalizeClosure(user, null);
                }
            } catch (Exception e) {
                logger.error("[Closure] Could not finalize closure for {}", user.getEmail(), e);
            }
        }
    }

    private BigDecimal safeBalance(Long userId) {
        try {
            BigDecimal balance = walletService.checkBalance(userId);
            return balance == null ? BigDecimal.ZERO : balance;
        } catch (Exception e) {
            // Unknown balance: treat as "still has money" so we never close an
            // account on the strength of a failed lookup.
            logger.warn("[Closure] Balance lookup failed for userId={} — leaving pending", userId, e);
            return new BigDecimal("999999999");
        }
    }
}
