package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.ReferralReward;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.ReferralRewardRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ReferralService {

    static final String PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=com.wisemonie";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private final UserRepository userRepository;
    private final ReferralRewardRepository referralRewardRepository;
    private final BudgetRepository budgetRepository;
    private final SystemConfigService systemConfig;
    private final SecureRandom random = new SecureRandom();

    public ReferralService(UserRepository userRepository,
                           ReferralRewardRepository referralRewardRepository,
                           BudgetRepository budgetRepository,
                           SystemConfigService systemConfig) {
        this.userRepository = userRepository;
        this.referralRewardRepository = referralRewardRepository;
        this.budgetRepository = budgetRepository;
        this.systemConfig = systemConfig;
    }

    /**
     * Removes one configured setup-fee unit when a credit is available and the
     * fee is at least that unit. A zero fee, including a first budget, is unchanged.
     */
    public static BigDecimal feeAfterOneUnit(BigDecimal fee, BigDecimal unit, boolean hasCredit) {
        BigDecimal safeFee = fee == null ? BigDecimal.ZERO : fee;
        if (!hasCredit || unit == null || unit.compareTo(BigDecimal.ZERO) <= 0) {
            return safeFee;
        }
        if (safeFee.compareTo(unit) < 0) {
            return safeFee;
        }
        return safeFee.subtract(unit);
    }

    @Transactional
    public String ensureCode(User user) {
        if (user.getReferralCode() != null && !user.getReferralCode().isBlank()) {
            return user.getReferralCode();
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = randomCode();
            if (userRepository.findByReferralCode(code).isEmpty()) {
                user.setReferralCode(code);
                userRepository.save(user);
                return code;
            }
        }
        throw new IllegalStateException("Could not create a referral code.");
    }

    /**
     * Stores the referrer once. An unknown code, a self-code, or a second code
     * is ignored so profile save can still finish.
     */
    @Transactional
    public boolean attach(User user, String rawCode) {
        if (user == null || user.getReferredByUserId() != null) {
            return false;
        }
        String code = normalize(rawCode);
        if (code == null) {
            return false;
        }
        Optional<User> referrer = userRepository.findByReferralCode(code);
        if (referrer.isEmpty() || referrer.get().getId() == null
                || referrer.get().getId().equals(user.getId())) {
            return false;
        }
        user.setReferredByUserId(referrer.get().getId());
        userRepository.save(user);
        return true;
    }

    @Transactional(readOnly = true)
    public BigDecimal adjustedFee(Long userId, BigDecimal fullFee) {
        BigDecimal unit = feeUnit();
        boolean hasCredit = userId != null && referralRewardRepository
                .existsByReferrerUserIdAndStatus(userId, ReferralReward.EARNED);
        return feeAfterOneUnit(fullFee, unit, hasCredit);
    }

    @Transactional
    public void consumeCredit(Long userId, Long budgetId, BigDecimal waived) {
        if (userId == null || waived == null || waived.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        ReferralReward reward = referralRewardRepository
                .findFirstByReferrerUserIdAndStatusOrderByIdAsc(userId, ReferralReward.EARNED)
                .orElse(null);
        if (reward == null) {
            return;
        }
        reward.setStatus(ReferralReward.USED);
        reward.setUsedAt(lagosNow());
        reward.setUsedBudgetId(budgetId);
        reward.setAmountWaived(waived);
        referralRewardRepository.save(reward);
    }

    /**
     * Called after a budget has been saved and the wallet has covered it.
     * The friend's first funded budget creates one reward. A referrer who
     * had no budget yet leaves that reward pending until their own budget exists.
     */
    @Transactional
    public void onBudgetFunded(Long userId) {
        if (userId == null) {
            return;
        }
        long budgets = budgetRepository.countByUserId(userId);
        if (budgets >= 1) {
            promotePending(userId);
        }
        if (budgets != 1) {
            return;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getReferredByUserId() == null) {
            return;
        }
        if (user.getReferredByUserId().equals(userId)) {
            return;
        }
        if (referralRewardRepository.existsByReferredUserId(userId)) {
            return;
        }
        LocalDateTime now = lagosNow();
        ReferralReward reward = new ReferralReward();
        reward.setReferrerUserId(user.getReferredByUserId());
        reward.setReferredUserId(userId);
        reward.setCreatedAt(now);
        if (budgetRepository.countByUserId(user.getReferredByUserId()) > 0) {
            reward.setStatus(ReferralReward.EARNED);
            reward.setEarnedAt(now);
        } else {
            reward.setStatus(ReferralReward.PENDING);
        }
        referralRewardRepository.save(reward);
    }

    @Transactional
    public Map<String, Object> summary(User user) {
        String code = ensureCode(user);
        BigDecimal unit = feeUnit();
        long available = referralRewardRepository
                .countByReferrerUserIdAndStatus(user.getId(), ReferralReward.EARNED);
        long used = referralRewardRepository
                .countByReferrerUserIdAndStatus(user.getId(), ReferralReward.USED);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("shareText", shareText(code));
        body.put("earnedCount", available + used);
        body.put("availableCredits", available);
        body.put("creditValue", unit);
        return body;
    }

    public static String shareText(String code) {
        return "When your salary lands, lock the rent before anyone asks. "
                + "Use my Wisemonie code " + code + ". " + PLAY_STORE_URL
                + "  wisemonie://refer?code=" + code;
    }

    static String normalize(String rawCode) {
        if (rawCode == null) {
            return null;
        }
        String code = rawCode.trim().toUpperCase(Locale.ROOT);
        return code.isEmpty() ? null : code;
    }

    private void promotePending(Long referrerId) {
        List<ReferralReward> pending = referralRewardRepository
                .findByReferrerUserIdAndStatus(referrerId, ReferralReward.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        LocalDateTime now = lagosNow();
        for (ReferralReward reward : pending) {
            reward.setStatus(ReferralReward.EARNED);
            reward.setEarnedAt(now);
        }
        referralRewardRepository.saveAll(pending);
    }

    private BigDecimal feeUnit() {
        BigDecimal unit = systemConfig.getBigDecimal(
                SystemConfigService.BUDGET_CREATION_FEE, BigDecimal.ZERO);
        return unit == null ? BigDecimal.ZERO : unit;
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private static LocalDateTime lagosNow() {
        return ZonedDateTime.now(ZoneId.of("Africa/Lagos")).toLocalDateTime();
    }
}
