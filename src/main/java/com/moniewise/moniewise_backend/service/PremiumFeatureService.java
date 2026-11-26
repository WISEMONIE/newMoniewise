package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.SubscriptionPlan;
import com.moniewise.moniewise_backend.entity.UserSubscription;
import com.moniewise.moniewise_backend.enums.SubscriptionStatus;
import com.moniewise.moniewise_backend.repository.UserSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

/**
 * Checks whether a user has an active premium subscription and whether
 * their plan includes a specific feature.
 *
 * <p>Features are stored as a comma-separated string in
 * {@code subscription_plans.features} so new capabilities can be added
 * without a schema migration.
 *
 * <p>All checks fail-closed — if a DB error occurs the user is treated
 * as free-tier to avoid accidental premium access on infrastructure failure.
 */
@Service
public class PremiumFeatureService {

    private static final Logger logger = LoggerFactory.getLogger(PremiumFeatureService.class);

    private final UserSubscriptionRepository userSubscriptionRepository;

    public PremiumFeatureService(UserSubscriptionRepository userSubscriptionRepository) {
        this.userSubscriptionRepository = userSubscriptionRepository;
    }

    /**
     * Returns {@code true} if the user has an active, non-expired subscription
     * whose plan includes {@code feature}.
     *
     * @param userId  the user's database ID
     * @param feature feature name to check — must match a
     *                {@link com.moniewise.moniewise_backend.enums.PremiumFeature} value
     *                (case-insensitive)
     */
    public boolean hasFeature(Long userId, String feature) {
        try {
            Optional<UserSubscription> sub = userSubscriptionRepository
                    .findTopByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE);

            if (sub.isEmpty()) return false;

            UserSubscription subscription = sub.get();

            if (subscription.getEndDate().isBefore(LocalDate.now())) {
                logger.debug("[Premium] Subscription {} expired for user {}", subscription.getId(), userId);
                return false;
            }

            SubscriptionPlan plan = subscription.getPlan();
            if (plan == null || plan.getFeatures() == null || plan.getFeatures().isBlank()) {
                return false;
            }

            return Arrays.stream(plan.getFeatures().split(","))
                    .map(String::trim)
                    .anyMatch(f -> f.equalsIgnoreCase(feature));

        } catch (Exception e) {
            logger.warn("[Premium] Feature check failed for user {}: {}", userId, e.getMessage());
            return false; // fail-closed
        }
    }

    /**
     * Returns {@code true} if the user is on any active, non-expired plan.
     */
    public boolean isPremium(Long userId) {
        try {
            return userSubscriptionRepository
                    .findTopByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE)
                    .filter(sub -> !sub.getEndDate().isBefore(LocalDate.now()))
                    .isPresent();
        } catch (Exception e) {
            logger.warn("[Premium] isPremium check failed for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
}
