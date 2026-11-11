package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the lifecycle of "abandoned signups" — users who created an account
 * (a {@code users} row exists) but never finished onboarding, so they have
 * neither a {@code wallets} row nor a {@code kyc_profiles} row.
 *
 * <p>Rather than letting these pile up forever (which is exactly how the
 * Rubies-sandbox duplicate-BVN mess happened) or deleting them the moment
 * someone gets distracted mid-signup, we run the cadence top fintechs use:
 *
 * <ul>
 *   <li><b>Day 1</b>  — first "complete your profile" nudge (~24h after signup)</li>
 *   <li><b>Day 1–15</b> — a capped email campaign on user-specific staggered
 *       days, with rotating psychological copy so it does not feel like spam</li>
 *   <li><b>Every 2 hours</b> — FCM-only push nudges between the daily emails</li>
 *   <li><b>Day 30</b> — if STILL incomplete, the registration is purged along
 *       with every dependent row, in the same leaf-to-root order as the
 *       manual cleanup script (transaction history → budgets/envelopes →
 *       kyc/wallet → user)</li>
 * </ul>
 *
 * This protects two things at once: it gives genuinely-interested users every
 * reasonable chance (and a friendly nudge) to finish, while keeping
 * {@code users}/{@code kyc_profiles} free of dead rows that would otherwise
 * collide with the BVN/phone/email uniqueness constraints we're restoring.
 */
@Component
public class IncompleteSignupLifecycleManager {

    private static final Logger logger = LoggerFactory.getLogger(IncompleteSignupLifecycleManager.class);

    /** First nudge lands ~24h after signup. */
    private static final int FIRST_REMINDER_DAY = 1;

    /** Email reminders stop after this signup age, even though cleanup waits longer. */
    private static final int REMINDER_CAMPAIGN_MAX_DAYS = 15;

    /** Maximum number of email reminders inside the campaign window. */
    private static final int MAX_REMINDER_EMAILS = 6;

    /** User-specific reminder gaps vary between these two values. */
    private static final int MIN_REMINDER_GAP_DAYS = 2;
    private static final int MAX_REMINDER_GAP_DAYS = 4;

    /** Show the "your slot will be released soon" notice once we're this close to the purge. */
    private static final int URGENCY_THRESHOLD_DAYS = 5;

    /** Registrations that are still incomplete after this many days get purged. */
    private static final int PURGE_AFTER_DAYS = 30;

    /**
     * Play Store review account. It stays incomplete on purpose (no wallet, no KYC)
     * and has rows in user_events, so the purge must never include this id.
     */
    static final long PLAY_STORE_REVIEW_USER_ID = 71L;

    private static final String COMPLETE_PROFILE_ROUTE = "/complete_profile/";
    private static final long PUSH_TTL_SECONDS = 7_200L;

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuthSessionService authSessionService;
    private final NamedParameterJdbcTemplate jdbc;

    public IncompleteSignupLifecycleManager(UserRepository userRepository,
                                             NotificationService notificationService,
                                             AuthSessionService authSessionService,
                                             NamedParameterJdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.authSessionService = authSessionService;
        this.jdbc = jdbc;
    }

    /**
     * Runs once a day at 9:00 AM server time — late enough that the email
     * lands in someone's morning inbox rather than at 1 AM.
     * CRON syntax: Seconds Minutes Hours DayOfMonth Month DayOfWeek
     */
    @Scheduled(cron = "0 0 9 * * ?", zone = "Africa/Lagos")
    @Transactional
    public void processIncompleteSignups() {
        List<User> incomplete = userRepository.findIncompleteSignups();
        if (incomplete.isEmpty()) {
            return;
        }

        logger.info("[ONBOARDING] Reviewing {} incomplete signup(s)", incomplete.size());

        LocalDateTime now = LocalDateTime.now();
        List<Long> toPurge = new ArrayList<>();

        for (User user : incomplete) {
            long daysSinceSignup = ChronoUnit.DAYS.between(user.getCreatedAt(), now);

            if (daysSinceSignup >= PURGE_AFTER_DAYS) {
                toPurge.add(user.getId());
                continue;
            }

            if (isReminderDue(user, daysSinceSignup)) {
                sendReminder(user, daysSinceSignup);
            }
        }

        if (!toPurge.isEmpty()) {
            purgeIncompleteSignups(toPurge);
        }
    }

    // ───────────────────────────── REMINDERS ─────────────────────────────

    private boolean isReminderDue(User user, long daysSinceSignup) {
        if (daysSinceSignup > REMINDER_CAMPAIGN_MAX_DAYS) {
            return false;
        }

        int sentSoFar = user.getOnboardingReminderCount();
        if (sentSoFar >= MAX_REMINDER_EMAILS) {
            return false;
        }

        long nextDueOnDay = nextReminderDay(user.getId(), sentSoFar);

        if (daysSinceSignup < nextDueOnDay) {
            return false;
        }

        // Guard against double-sends if the job runs more than once on the same day
        // (e.g. an app restart re-triggers the scheduler shortly after the first run).
        if (user.getLastOnboardingReminderAt() != null
                && user.getLastOnboardingReminderAt().toLocalDate().isEqual(LocalDate.now())) {
            return false;
        }

        return true;
    }

    private long nextReminderDay(Long userId, int sentSoFar) {
        long day = FIRST_REMINDER_DAY;
        for (int i = 0; i < sentSoFar; i++) {
            day += randomGapDays(userId, i);
        }
        return day;
    }

    private int randomGapDays(Long userId, int reminderIndex) {
        int spread = MAX_REMINDER_GAP_DAYS - MIN_REMINDER_GAP_DAYS + 1;
        int offset = Math.floorMod(Objects.hash(userId, reminderIndex), spread);
        return MIN_REMINDER_GAP_DAYS + offset;
    }

    private void sendReminder(User user, long daysSinceSignup) {
        String firstName = extractFirstName(user);
        long daysRemaining = Math.max(0, PURGE_AFTER_DAYS - daysSinceSignup);
        boolean urgent = daysRemaining <= URGENCY_THRESHOLD_DAYS;
        int reminderNumber = user.getOnboardingReminderCount() + 1;

        notificationService.sendOnboardingReminderEmail(
                user.getEmail(),
                firstName,
                (int) daysSinceSignup,
                (int) daysRemaining,
                urgent,
                reminderNumber
        );

        sendPushIfReachable(user, urgent);

        user.setOnboardingReminderCount(user.getOnboardingReminderCount() + 1);
        user.setLastOnboardingReminderAt(LocalDateTime.now());
        userRepository.save(user);

        logger.info("[ONBOARDING] Sent reminder #{} (email+push) to {} (day {} since signup, {} day(s) until purge{})",
                user.getOnboardingReminderCount(), user.getEmail(), daysSinceSignup, daysRemaining,
                urgent ? ", URGENT" : "");
    }

    /**
     * Runs every 2 hours — sends FCM-only push to incomplete users so they
     * re-open the app and finish onboarding. No email on this cadence.
     */
    @Scheduled(cron = "0 0 */2 * * ?")
    public void pushIncompleteSignups() {
        List<User> incomplete = userRepository.findIncompleteSignups();
        if (incomplete.isEmpty()) {
            return;
        }

        int sent = 0;
        for (User user : incomplete) {
            long hoursSinceSignup = ChronoUnit.HOURS.between(user.getCreatedAt(), LocalDateTime.now());
            if (hoursSinceSignup < 1) {
                continue;
            }
            try {
                if (sendPushIfReachable(user, false)) {
                    sent++;
                }
            } catch (Exception e) {
                logger.debug("[ONBOARDING] Push failed for user={}: {}", user.getId(), e.getMessage());
            }
        }

        if (sent > 0) {
            logger.info("[ONBOARDING] 2h push cycle: sent {} onboarding push(es)", sent);
        }
    }

    private boolean sendPushIfReachable(User user, boolean urgent) {
        if (!hasPushTarget(user.getId())) {
            return false;
        }
        String title = urgent
                ? "Account setup expires soon ⏳"
                : "Finish your Wisemonie setup 📝";
        String body = urgent
                ? "Complete your profile now to keep your Wisemonie account and wallet slot."
                : "You're almost there. Complete your profile to unlock your wallet and start your first plan 💚.";
        notificationService.enqueuePushOnlyNotification(
                user.getId(),
                NotificationType.ONBOARDING_REMINDER,
                title,
                body,
                COMPLETE_PROFILE_ROUTE,
                PUSH_TTL_SECONDS);
        return true;
    }

    private boolean hasPushTarget(Long userId) {
        if (userId == null) return false;
        try {
            if (!authSessionService.getActiveFcmTokens(userId).isEmpty()) return true;
        } catch (Exception ignored) {}
        try {
            String fallback = userRepository.findFcmTokenById(userId);
            return fallback != null && !fallback.isBlank();
        } catch (Exception ignored) {}
        return false;
    }

    /** Mirrors the same best-effort name extraction used for the welcome email. */
    private String extractFirstName(User user) {
        try {
            if (user.getProfileData() != null) {
                Object nameObj = user.getProfileData().get("name");
                if (nameObj != null) {
                    String fullName = nameObj.toString().trim();
                    return fullName.contains(" ") ? fullName.substring(0, fullName.indexOf(" ")) : fullName;
                }
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return "";
    }

    // ───────────────────────────── PURGE ─────────────────────────────

    /**
     * Deletes a batch of stale, never-completed registrations and every row
     * that hangs off them — in the exact same leaf-to-root order as the
     * manual SQL cleanup script, so nothing trips a foreign-key violation
     * and nothing is left orphaned.
     * <p>
     * Deliberately implemented with explicit, ordered {@code DELETE}s rather
     * than {@code ON DELETE CASCADE} — we never want a future user-deletion
     * to silently cascade-wipe financial/audit history. This purge path is
     * the one narrow exception, reserved for accounts that never became real
     * customers in the first place (no wallet, no KYC, 30 days of silence).
     */
    @Transactional
    public void purgeIncompleteSignups(List<Long> userIds) {
        List<Long> ids = idsEligibleForPurge(userIds);
        if (ids.isEmpty()) {
            return;
        }

        logger.info("[ONBOARDING] Purging {} stale incomplete signup(s) (>{} days, never finished profile): {}. User {} is excluded.",
                ids.size(), PURGE_AFTER_DAYS, ids, PLAY_STORE_REVIEW_USER_ID);

        Map<String, Object> params = Map.of("ids", ids);

        // ── Leaf / dependent tables first ──
        deleteByUserId("transaction_requests", params);
        deleteByUserId("withdrawals", params);
        deleteByUserId("transaction_logs", params);
        deleteByUserId("outbox_events", params);
        deleteByUserId("notifications", params);
        deleteByUserId("pending_disbursements", params);
        deleteByUserId("analytics_logs", params);
        deleteByUserId("revenue_logs", params);
        deleteByUserId("otps", params);
        deleteByUserId("auth_sessions", params);
        deleteByUserId("beneficiaries", params);
        deleteByUserId("user_badges", params);
        deleteByUserId("user_goals", params);
        deleteByUserId("leaderboard_entries", params);
        deleteByUserId("savings_goals", params);
        deleteByUserId("user_tier_assignments", params);
        deleteByUserId("user_subscriptions", params);
        deleteByUserId("user_legal_acceptances", params);

        // Email-keyed tables (no FK, but hold dead references — clean for hygiene)
        deleteByEmailOfUserId("password_resets", params);
        deleteByEmailOfUserId("transaction_pin_resets", params);

        // Mid-level: envelopes hang off budgets, budgets hang off the user
        jdbc.update("""
                DELETE FROM envelopes
                WHERE budget_id IN (SELECT id FROM budgets WHERE user_id IN (:ids))
                """, params);
        deleteByUserId("budgets", params);

        // Near-root tables (defensive — these users have neither by definition,
        // but running it keeps the purge symmetric with the manual script)
        deleteByUserId("kyc_profiles", params);
        deleteByUserId("wallets", params);
        // No ON DELETE CASCADE. Leaving these rows makes the users delete fail.
        deleteByUserId("user_events", params);

        // Root. The review account is excluded again here so this statement
        // cannot remove user 71 even if the id list is wrong.
        Map<String, Object> userDelete = Map.of(
                "ids", ids,
                "keepUserId", PLAY_STORE_REVIEW_USER_ID);
        int deleted = jdbc.update(
                "DELETE FROM users WHERE id IN (:ids) AND id <> :keepUserId",
                userDelete);

        logger.info("[ONBOARDING] Purge complete — removed {} stale registration(s) and all dependent rows", deleted);
    }

    static List<Long> idsEligibleForPurge(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Long id : userIds) {
            if (id != null && id != PLAY_STORE_REVIEW_USER_ID) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void deleteByUserId(String table, Map<String, Object> params) {
        jdbc.update("DELETE FROM " + table + " WHERE user_id IN (:ids)", params);
    }

    private void deleteByEmailOfUserId(String table, Map<String, Object> params) {
        jdbc.update("DELETE FROM " + table + " WHERE email IN (SELECT email FROM users WHERE id IN (:ids))", params);
    }
}
