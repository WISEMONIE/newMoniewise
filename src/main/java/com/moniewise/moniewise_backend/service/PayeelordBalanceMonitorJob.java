package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.psp.payeelord.PayeelordGateway;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Polls our Payeelord float balance on an interval and pushes a HIGH-priority
 * alert to every {@link Role#ADMIN} user when it drops below a configurable
 * threshold (default ₦5,000) — so admins know to top the float up before
 * airtime/data purchases start failing.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li><b>Best-effort &amp; non-blocking.</b> A failed balance read
 *       ({@link PayeelordGateway#checkWalletBalance()} returns empty) just skips
 *       the run — it never throws out of the scheduled method.</li>
 *   <li><b>Cooldown to avoid spam.</b> Once an alert fires, further alerts are
 *       suppressed for {@code payeelord.balance.alert.cooldown_minutes} (default
 *       6h). The cooldown timestamp is in-memory: a restart re-arms it, which at
 *       worst sends one extra alert — acceptable for an ops nudge.</li>
 *   <li><b>Admins only.</b> Targets users with {@code ROLE_ADMIN}; regular users
 *       never see this.</li>
 *   <li><b>Gated.</b> Honours {@code payeelord.balance.alert.enabled} (default true).</li>
 * </ul>
 */
@Component
public class PayeelordBalanceMonitorJob {

    private static final Logger logger = LoggerFactory.getLogger(PayeelordBalanceMonitorJob.class);

    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("5000");
    private static final int DEFAULT_COOLDOWN_MINUTES = 360;

    private final PayeelordGateway gateway;
    private final SystemConfigService systemConfig;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** In-memory dedupe — when the last low-balance alert was sent. */
    private volatile LocalDateTime lastAlertAt;

    public PayeelordBalanceMonitorJob(PayeelordGateway gateway,
                                      SystemConfigService systemConfig,
                                      UserRepository userRepository,
                                      NotificationService notificationService) {
        this.gateway = gateway;
        this.systemConfig = systemConfig;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    /**
     * Runs every 30 minutes by default — override with
     * {@code payeelord.balance.alert.interval.ms}. Uses fixedDelay so a slow
     * Payeelord call never overlaps with the next run.
     */
    @Scheduled(fixedDelayString = "${payeelord.balance.alert.interval.ms:1800000}",
               initialDelayString = "${payeelord.balance.alert.initial.delay.ms:120000}")
    public void monitorFloatBalance() {
        if (!systemConfig.getBoolean(SystemConfigService.PAYEELORD_BALANCE_ALERT_ENABLED, true)) {
            return;
        }

        BigDecimal threshold = systemConfig.getBigDecimal(
                SystemConfigService.PAYEELORD_BALANCE_ALERT_THRESHOLD, DEFAULT_THRESHOLD);

        BigDecimal balance = gateway.checkWalletBalance().orElse(null);
        if (balance == null) {
            logger.debug("[PayeelordBalanceMonitor] Balance unavailable this run — skipping.");
            return;
        }

        if (balance.compareTo(threshold) >= 0) {
            logger.debug("[PayeelordBalanceMonitor] Float OK: ₦{} ≥ threshold ₦{}", balance, threshold);
            return;
        }

        // Below threshold — respect the cooldown before alerting again.
        int cooldownMinutes = systemConfig.getInt(
                SystemConfigService.PAYEELORD_BALANCE_ALERT_COOLDOWN_MINUTES, DEFAULT_COOLDOWN_MINUTES);
        if (lastAlertAt != null
                && Duration.between(lastAlertAt, LocalDateTime.now()).toMinutes() < cooldownMinutes) {
            logger.info("[PayeelordBalanceMonitor] Float low (₦{}) but within cooldown — alert suppressed.", balance);
            return;
        }

        alertAdmins(balance, threshold);
        lastAlertAt = LocalDateTime.now();
    }

    private void alertAdmins(BigDecimal balance, BigDecimal threshold) {
        List<User> admins = userRepository.findByRole(Role.ADMIN);
        if (admins.isEmpty()) {
            logger.warn("[PayeelordBalanceMonitor] Float low (₦{}) but no ADMIN users to notify.", balance);
            return;
        }

        String message = String.format(
                "⚠️ Payeelord float is low: ₦%,.2f (below ₦%,.2f). Top up the Payeelord wallet to keep "
                        + "airtime & data purchases working.", balance, threshold);

        for (User admin : admins) {
            try {
                notificationService.sendNotification(
                        admin.getId().toString(), message, NotificationType.ADMIN_PAYEELORD_LOW_BALANCE);
            } catch (Exception e) {
                logger.error("[PayeelordBalanceMonitor] Failed to notify admin userId={}", admin.getId(), e);
            }
        }
        logger.warn("[PayeelordBalanceMonitor] Low-balance alert (₦{}) sent to {} admin(s).", balance, admins.size());
    }
}
