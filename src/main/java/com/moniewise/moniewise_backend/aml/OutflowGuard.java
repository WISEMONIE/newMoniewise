package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The one check before money leaves a wallet, by any route: bank transfer,
 * transfer to another customer, budget or savings release, airtime or data.
 *
 * <p>A frozen wallet sends nothing. A review hold or the first-day limit
 * caps what can go out. The amount is counted as soon as it passes, in its
 * own transaction, so a transfer that fails later still counts until the
 * review clears. That errs on the safe side.
 */
@Service
public class OutflowGuard {

    private static final Logger logger = LoggerFactory.getLogger(OutflowGuard.class);

    /** Kept apart from any other advisory lock the database might use. */
    private static final long LOCK_NAMESPACE = 0x414D4CL << 32;

    public enum Channel {
        BANK_TRANSFER, WISEMONIE_TRANSFER, BUDGET_TO_BANK, BUDGET_TO_USER,
        SCHEDULED_RELEASE, SAVINGS_TO_USER, AIRTIME, DATA
    }

    private final InflowHoldRepository holds;
    private final WalletRepository walletRepository;
    private final AmlLockRepository locks;
    private final AmlSettings settings;
    private final Clock clock;

    public OutflowGuard(InflowHoldRepository holds,
                        WalletRepository walletRepository,
                        AmlLockRepository locks,
                        AmlSettings settings,
                        Optional<Clock> clock) {
        this.holds = holds;
        this.walletRepository = walletRepository;
        this.locks = locks;
        this.settings = settings;
        this.clock = clock.orElse(Clock.systemDefaultZone());
    }

    /**
     * Throws {@link OutflowBlockedException} when the amount may not leave.
     * Call after the PIN check and before any money moves.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void authorize(Long userId, BigDecimal amount, Channel channel) {
        if (userId == null || amount == null || amount.signum() <= 0) {
            return;
        }
        Wallet wallet = walletRepository.findByUserId(userId).orElse(null);
        if (wallet != null && wallet.getStatus() == WalletStatus.FROZEN) {
            logger.warn("[AML] Blocked {} of {} for user={}: wallet frozen", channel, amount, userId);
            throw new OutflowBlockedException(OutflowBlockedException.WALLET_FROZEN,
                    "Transfers from this wallet are paused. Please contact support.", null);
        }
        if (!settings.enabled()) {
            return;
        }

        // One check at a time per customer, so two transfers sent together
        // cannot both fit under the cap.
        locks.lock(LOCK_NAMESPACE | userId);

        LocalDateTime now = LocalDateTime.now(clock);
        ensureFirstDayHold(userId, wallet, now);

        List<InflowHold> open = holds.lockByUserAndStatusIn(userId, InflowReviewService.HOLDING);
        for (InflowHold hold : open) {
            if (!hold.isHoldingAt(now)) {
                if (hold.getReason() == InflowHold.Reason.FIRST_DAY) {
                    hold.setStatus(InflowHold.Status.EXPIRED);
                    holds.save(hold);
                }
                continue;
            }
            if (hold.getOutflowUsed().add(amount).compareTo(hold.getOutflowCap()) > 0) {
                BigDecimal left = hold.remaining();
                logger.warn("[AML] Blocked {} of {} for user={}: hold {} ({}) has {} left",
                        channel, amount, userId, hold.getId(), hold.getReason(), left);
                throw blocked(hold, left);
            }
        }
        for (InflowHold hold : open) {
            if (hold.isHoldingAt(now)) {
                hold.setOutflowUsed(hold.getOutflowUsed().add(amount));
                hold.addNote("system", channel + " " + AmlNotifier.naira(amount) + " allowed, "
                        + AmlNotifier.naira(hold.remaining()) + " left", now);
                holds.save(hold);
            }
        }
    }

    /**
     * What limits this customer's money out right now, for the app's banner.
     * Read-only. Null when nothing does, so most wallet responses are unchanged.
     * <ul>
     *   <li>frozen: nothing can leave</li>
     *   <li>underReview: a large payment is being reviewed</li>
     *   <li>newAccount: the first-day limit applies, until {@code newAccountUntil}</li>
     *   <li>availableToSend: the smallest amount left across the limits that apply</li>
     *   <li>customerContacted: support already heard from them about the review</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> restrictionFor(Long userId) {
        if (userId == null) {
            return null;
        }
        Wallet wallet = walletRepository.findByUserId(userId).orElse(null);
        boolean frozen = wallet != null && wallet.getStatus() == WalletStatus.FROZEN;
        boolean underReview = false;
        boolean contacted = false;
        LocalDateTime firstDayEnds = null;
        BigDecimal available = null;

        if (settings.enabled()) {
            LocalDateTime now = LocalDateTime.now(clock);
            boolean firstDayHoldSeen = false;
            for (InflowHold hold : holds.findByUserIdAndStatusInOrderByIdDesc(userId, InflowReviewService.HOLDING)) {
                if (!hold.isHoldingAt(now)) {
                    continue;
                }
                if (hold.getReason() == InflowHold.Reason.LARGE_INFLOW) {
                    underReview = true;
                    contacted = contacted || hold.getCustomerContactedAt() != null;
                } else {
                    firstDayHoldSeen = true;
                    firstDayEnds = hold.getEndsAt();
                }
                available = available == null ? hold.remaining() : available.min(hold.remaining());
            }
            // A new wallet that has not sent anything yet has no first-day row.
            LocalDateTime opened = wallet != null ? wallet.getOpenedAt() : null;
            if (!firstDayHoldSeen && opened != null
                    && now.isBefore(opened.plusHours(AmlSettings.FIRST_DAY_HOURS))) {
                firstDayEnds = opened.plusHours(AmlSettings.FIRST_DAY_HOURS);
                available = available == null ? settings.firstDayCap() : available.min(settings.firstDayCap());
            }
        }

        if (!frozen && !underReview && firstDayEnds == null) {
            return null;
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("frozen", frozen);
        out.put("underReview", underReview);
        out.put("newAccount", firstDayEnds != null);
        if (firstDayEnds != null) {
            out.put("newAccountUntil", firstDayEnds);
        }
        out.put("availableToSend", frozen ? BigDecimal.ZERO : available);
        out.put("customerContacted", contacted);
        return out;
    }

    /** Opens the first-day limit for a wallet that opened in the last 24 hours, once. */
    private void ensureFirstDayHold(Long userId, Wallet wallet, LocalDateTime now) {
        LocalDateTime opened = wallet != null ? wallet.getOpenedAt() : null;
        if (opened == null) {
            return;
        }
        LocalDateTime ends = opened.plusHours(AmlSettings.FIRST_DAY_HOURS);
        if (!now.isBefore(ends) || holds.existsByUserIdAndReason(userId, InflowHold.Reason.FIRST_DAY)) {
            return;
        }
        InflowHold hold = new InflowHold();
        hold.setUserId(userId);
        hold.setReason(InflowHold.Reason.FIRST_DAY);
        hold.setStatus(InflowHold.Status.ACTIVE);
        hold.setOutflowCap(settings.firstDayCap());
        hold.setOutflowUsed(BigDecimal.ZERO);
        hold.setStartedAt(opened);
        hold.setEndsAt(ends);
        hold.addNote("system", "First-day limit (CBN): " + AmlNotifier.naira(settings.firstDayCap())
                + " out until " + ends.withNano(0), now);
        holds.saveAndFlush(hold);
    }

    private static OutflowBlockedException blocked(InflowHold hold, BigDecimal left) {
        String allowance = left.signum() > 0
                ? " You can send up to " + AmlNotifier.naira(left) + " right now."
                : "";
        if (hold.getReason() == InflowHold.Reason.FIRST_DAY) {
            return new OutflowBlockedException(OutflowBlockedException.NEW_ACCOUNT_LIMIT,
                    "New accounts can send up to " + AmlNotifier.naira(hold.getOutflowCap())
                            + " in their first 24 hours." + allowance,
                    left);
        }
        return new OutflowBlockedException(OutflowBlockedException.OUTFLOW_HELD,
                "For your security, a large payment you received is being reviewed." + allowance
                        + " Contact support to complete the review.",
                left);
    }
}
