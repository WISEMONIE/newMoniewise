package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Messages for a payment review. The customer is told calmly, without
 * blame. Compliance gets the facts. Neither message says a report may be
 * filed (no tipping off, guardrail 11).
 */
@Component
public class AmlNotifier {

    private static final Logger logger = LoggerFactory.getLogger(AmlNotifier.class);

    static final String CUSTOMER_TITLE = "Your payment is being reviewed";

    private final NotificationService notificationService;

    @Autowired(required = false)
    private UserRepository userRepository;

    @Value("${moniewise.compliance.alert-email:${COMPLIANCE_ALERT_EMAIL:}}")
    private String complianceEmail = "";

    @Value("${moniewise.support.contact:${SUPPORT_CONTACT:support@wisemonie.com}}")
    private String supportContact = "support@wisemonie.com";

    public AmlNotifier(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void customerHoldStarted(User user, BigDecimal cap) {
        String message = "For your security, large incoming payments are reviewed before full withdrawal. "
                + "You can still send up to " + naira(cap) + " while we review. "
                + "Contact support to complete the review: " + supportContact + ".";
        notificationService.sendTitledNotification(String.valueOf(user.getId()), CUSTOMER_TITLE, message,
                NotificationType.SYSTEM, null, null);
        String email = user.getEmail();
        CompletableFuture.runAsync(() -> notificationService.sendPlainEmail(email, CUSTOMER_TITLE,
                "Hello,\n\n" + message + "\n\nThank you for banking safely with Wisemonie."));
    }

    public void customerHoldReleased(User user) {
        notificationService.sendTitledNotification(String.valueOf(user.getId()), "Review complete",
                "Thanks for your patience. Your review is complete and your usual limits are back.",
                NotificationType.SYSTEM, null, null);
    }

    /**
     * The subject carries only ids and amounts, because it is also logged.
     * Names and bank details stay in the body, which only goes by email.
     */
    public void compliance(String subject, String body) {
        boolean sent = !complianceEmail.isBlank()
                && notificationService.sendPlainEmail(complianceEmail, "[AML] " + subject, body);
        if (!sent) {
            // ERROR reaches Sentry, so the alert is never silently lost.
            logger.error("[AML] {} (compliance email not sent; set COMPLIANCE_ALERT_EMAIL)", subject);
        } else {
            logger.warn("[AML] {}", subject);
        }
    }

    // ── Admin pushes ───────────────────────────────────────────────────────
    // Push text passes through Firebase, so it carries no names, emails or
    // account numbers: only what kind of task is waiting and the amount.

    public void adminsHoldStarted(BigDecimal amount) {
        pushAdmins("Payment review needed",
                "A " + naira(amount) + " payment was put on hold. Open the admin site to review it.");
    }

    public void adminsTellRubies() {
        pushAdmins("Tell Rubies about a held payment",
                "A held customer has not replied in 24 hours. Open the admin site to inform Rubies.");
    }

    public void adminsWalletNeedsFixing() {
        pushAdmins("A wallet could not open",
                "A verified customer's wallet keeps failing to open. Open the admin site to fix it.");
    }

    private void pushAdmins(String title, String message) {
        if (userRepository == null) {
            return;
        }
        try {
            for (User admin : userRepository.findByRole(Role.ADMIN)) {
                if (!admin.isDeleted()) {
                    notificationService.sendTitledNotification(String.valueOf(admin.getId()), title, message,
                            NotificationType.SYSTEM, null, null);
                }
            }
        } catch (Exception e) {
            logger.warn("[AML] Could not push admins: {}", e.getMessage());
        }
    }

    static String naira(BigDecimal amount) {
        if (amount == null) {
            return "₦0";
        }
        NumberFormat format = NumberFormat.getNumberInstance(Locale.ENGLISH);
        format.setMaximumFractionDigits(2);
        format.setMinimumFractionDigits(0);
        return "₦" + format.format(amount);
    }
}
