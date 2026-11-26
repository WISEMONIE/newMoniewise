package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.SubscriptionPlan;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.UserSubscription;
import com.moniewise.moniewise_backend.enums.SubscriptionStatus;
import com.moniewise.moniewise_backend.repository.SubscriptionPlanRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.UserSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;


/**
 * Manages premium subscription lifecycle.
 *
 * <h3>Key invariants</h3>
 * <ul>
 *   <li>A user has at most one ACTIVE subscription at any time.</li>
 *   <li>Subscribing while already active extends the end date by one billing cycle.</li>
 *   <li>A daily job expires subscriptions whose endDate has passed.</li>
 * </ul>
 */
@Service
public class SubscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionPlanRepository planRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    public SubscriptionService(SubscriptionPlanRepository planRepository,
                               UserSubscriptionRepository subscriptionRepository,
                               UserRepository userRepository) {
        this.planRepository         = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository         = userRepository;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns the user's current active subscription, or empty if they're on free tier.
     */
    public Optional<UserSubscription> getActiveSubscription(Long userId) {
        return subscriptionRepository
                .findTopByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE)
                .filter(sub -> !sub.getEndDate().isBefore(LocalDate.now()));
    }

    /**
     * Returns all available active plans (for the "upgrade" screen).
     */
    public List<SubscriptionPlan> getAvailablePlans() {
        return planRepository.findByActiveTrue();
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Activates a subscription for the user after a successful payment.
     *
     * <p>If the user already has an active subscription for the same plan,
     * the end date is extended by one billing cycle (grace-based renewal).
     *
     * @param userId           the user's ID
     * @param planName         plan to activate (case-insensitive, e.g. "PREMIUM")
     * @param paymentReference the payment reference from the payment provider
     * @return the saved {@link UserSubscription}
     */
    @Transactional
    public UserSubscription subscribe(Long userId, String planName, String paymentReference) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        SubscriptionPlan plan = planRepository.findByPlanNameIgnoreCase(planName)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planName));

        if (!plan.isActive()) {
            throw new IllegalStateException("Plan '" + planName + "' is not currently available");
        }

        LocalDate today = LocalDate.now();

        // If there's an existing active sub for this plan, extend it
        Optional<UserSubscription> existing = subscriptionRepository
                .findTopByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE);

        if (existing.isPresent() && existing.get().getPlan().getId().equals(plan.getId())
                && !existing.get().getEndDate().isBefore(today)) {
            UserSubscription sub = existing.get();
            sub.setEndDate(advanceByOneCycle(sub.getEndDate(), plan.getBillingCycle().name()));
            sub.setPaymentReference(paymentReference);
            logger.info("[Subscription] Extended plan={} for user={} → endDate={}",
                    planName, userId, sub.getEndDate());
            return subscriptionRepository.save(sub);
        }

        // New subscription
        LocalDate endDate = advanceByOneCycle(today, plan.getBillingCycle().name());

        UserSubscription sub = new UserSubscription();
        sub.setUser(user);
        sub.setPlan(plan);
        sub.setStartDate(today);
        sub.setEndDate(endDate);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setPaymentReference(paymentReference);

        logger.info("[Subscription] New plan={} for user={} → {} to {}", planName, userId, today, endDate);
        return subscriptionRepository.save(sub);
    }

    /**
     * Cancels the user's active subscription immediately.
     * The subscription is marked CANCELLED but no refund logic is applied here.
     */
    @Transactional
    public void cancel(Long userId) {
        subscriptionRepository
                .findTopByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE)
                .filter(sub -> !sub.getEndDate().isBefore(LocalDate.now()))
                .ifPresent(sub -> {
                    sub.setStatus(SubscriptionStatus.CANCELLED);
                    subscriptionRepository.save(sub);
                    logger.info("[Subscription] Cancelled plan={} for user={}", sub.getPlan().getPlanName(), userId);
                });
    }

    // ── Scheduled expiry ──────────────────────────────────────────────────────

    /**
     * Runs daily at 00:05 and expires subscriptions whose end date has passed.
     */
    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void expireStaleSubscriptions() {
        List<UserSubscription> expired = subscriptionRepository
                .findByStatusAndEndDateBefore(SubscriptionStatus.ACTIVE, LocalDate.now());

        if (expired.isEmpty()) return;

        logger.info("[Subscription] Expiring {} stale subscriptions", expired.size());
        expired.forEach(sub -> sub.setStatus(SubscriptionStatus.EXPIRED));
        subscriptionRepository.saveAll(expired);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LocalDate advanceByOneCycle(LocalDate from, String billingCycle) {
        return switch (billingCycle.toUpperCase()) {
            case "QUARTERLY" -> from.plusMonths(3);
            case "ANNUALLY"  -> from.plusYears(1);
            default          -> from.plusMonths(1); // MONTHLY
        };
    }

    /** Returns the feature strings for the active subscription, or empty list on free tier. */
    public List<String> getActiveFeatures(Long userId) {
        return getActiveSubscription(userId)
                .map(sub -> {
                    String features = sub.getPlan().getFeatures();
                    if (features == null || features.isBlank()) return Collections.<String>emptyList();
                    return Arrays.asList(features.split(","));
                })
                .orElse(Collections.emptyList());
    }
}
