package com.moniewise.moniewise_backend.service;

import com.google.firebase.messaging.*;
import com.moniewise.moniewise_backend.config.GenericNotificationEvent;
import com.moniewise.moniewise_backend.dto.response.NotificationBulkReadResponse;
import com.moniewise.moniewise_backend.entity.Notification;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.AccountClosureEmailScenario;
import com.moniewise.moniewise_backend.enums.BudgetEngagementNudgeType;
import com.moniewise.moniewise_backend.enums.LifecycleRecoveryType;
import com.moniewise.moniewise_backend.enums.NotificationPriority;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.utils.ExceptionClassifier;
import com.twilio.Twilio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.annotation.PostConstruct;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.moniewise.moniewise_backend.entity.OutboxEvent;
import com.moniewise.moniewise_backend.repository.OutboxEventRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);
    private static final String ANDROID_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.wisemonie";
    private static final String ACTION_OPEN_EXTERNAL_URL = "OPEN_EXTERNAL_URL";
    private static final String ACTION_IOS_APP_COMING_SOON = "IOS_APP_COMING_SOON";
    private static final int OUTBOX_MAX_ATTEMPTS = 5;
    private static final long OUTBOX_MAX_RETRY_DELAY_MINUTES = 30L;

    private final FirebaseMessaging firebaseMessaging;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AuthSessionService authSessionService;
    private final DeepLinkService deepLinkService;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    /** Self-proxy so the short prepare/finalize transactions below actually get their
     *  own transaction when called from the (non-transactional) deliverOutboxEvent
     *  orchestrator — self-invocation would otherwise bypass the proxy. */
    @Autowired
    @Lazy
    private NotificationService self;

    @Value("${spring.profiles.active:prod}")
    private String activeProfile;

    @Value("${twilio.account.sid:YOUR_TWILIO_ACCOUNT_SID}")
    private String twilioAccountSid;

    @Value("${twilio.auth.token:YOUR_TWILIO_AUTH_TOKEN}")
    private String twilioAuthToken;

    @Value("${twilio.phone.number:+15551234567}")
    private String twilioPhoneNumber;

    @Value("${spring.mail.from:moniewise@example.com}")
    private String fromEmail;

    @Value("${app.base-url:http://localhost:9000}")
    private String appBaseUrl;

    @Value("${moniewise.reconciliation.admin-alert-email:}")
    private String reconciliationAdminEmail;

    @Autowired
    public NotificationService(
            @Autowired(required = false) FirebaseMessaging firebaseMessaging,
            UserRepository userRepository,
            NotificationRepository notificationRepository,
            OutboxEventRepository outboxEventRepository,
            AuthSessionService authSessionService,
            DeepLinkService deepLinkService,
            @Autowired(required = false) JavaMailSender mailSender,
            TemplateEngine templateEngine) {
        this.firebaseMessaging = firebaseMessaging;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.authSessionService = authSessionService;
        this.deepLinkService = deepLinkService;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    @Transactional
    public NotificationBulkReadResponse markAllNotificationsAsRead(Long userId) {
        int updatedCount = notificationRepository.markAllAsReadForUser(userId);
        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
        return new NotificationBulkReadResponse(updatedCount, unreadCount);
    }

    @PostConstruct
    public void initTwilio() {
        if (twilioAccountSid == null || twilioAuthToken == null ||
                "stub".equals(activeProfile) ||
                twilioAccountSid.equals("YOUR_TWILIO_ACCOUNT_SID") ||
                twilioAuthToken.equals("YOUR_TWILIO_AUTH_TOKEN")) {
            logger.info("[STUB] Twilio not initialized due to placeholder credentials or stub mode");
        } else {
            Twilio.init(twilioAccountSid, twilioAuthToken);
            logger.info("Twilio initialized with account SID: {}", twilioAccountSid);
        }
    }

    // =========================================================================
    // 1. EVENT-DRIVEN LISTENER (The New Standard)
    // =========================================================================

    /**
     * ðŸŸ¢ FINTECH BEST PRACTICE:
     * Only handle the notification AFTER the transaction commits successfully.
     * Prevents "Ghost Notifications" where a user gets an alert but no money moved.
     */
//    @Async
//    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
//    public void handleNotificationEvent(GenericNotificationEvent event) {
//        try {
//            // 1. Centralized Message Generation
//            String message = generateMessage(event.getType(), event.getParams());
//            NotificationPriority priority = getPriority(event.getType());
//
//            // ðŸ›‘ NEW: Determine if this notification should live in the DB forever
//            boolean shouldSaveToDatabase = shouldPersistToDatabase(event.getType());
//
//            // 2. Persist to DB (ONLY if it's an important event)
//            if (shouldSaveToDatabase) {
//                Notification notification = new Notification();
//                notification.setUserId(Long.valueOf(event.getUserId()));
//                notification.setMessage(message);
//                notification.setType(event.getType());
//                notification.setCreatedAt(LocalDateTime.now());
//                notification.setBudgetId(event.getContextId1());
//                notification.setEnvelopeId(event.getContextId2());
//                notification.setRedirectUrl(event.getActionUrl());
//                notification.setRead(false);
//                notificationRepository.save(notification);
//            }
//
//            // 3. Send FCM Push (We want to send pushes for MORE things than we save)
//            // Example: We push a 15-min warning to their phone, but we don't save it to the DB inbox.
//            Long userId = Long.valueOf(event.getUserId());
//            String fcmToken = userRepository.findFcmTokenById(userId);
//
//            if (fcmToken != null && !fcmToken.isEmpty() && !"stub".equals(activeProfile)) {
//                if (firebaseMessaging != null) {
//                    String dynamicTitle = getNotificationTitle(event.getType());
//                    sendFCMMessage(fcmToken, dynamicTitle, message, null,
//                                    event.getActionUrl(), event.getType(), userId,
//                                    event.getContextId2()); // contextId2 = envelopeId
//                } else {
//                    logger.warn("âš ï¸ Skipping FCM: Firebase is not initialized.");
//                }
//            }
//
//        } catch (Exception e) {
//            logger.error("Failed to process notification event for user {}", event.getUserId(), e);
//        }
//    }

    // ðŸ‘‡ ADD THIS HELPER METHOD ðŸ‘‡
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleNotificationEvent(GenericNotificationEvent event) {
        try {
            String message = generateMessage(event.getType(), event.getParams());
            NotificationPriority priority = getPriority(event.getType());
            boolean shouldSaveToDatabase = shouldPersistToDatabase(event.getType());
            boolean pushEligible = priority == NotificationPriority.HIGH || priority == NotificationPriority.MEDIUM;

            Long userId = Long.valueOf(event.getUserId());
            List<String> fcmTokens = pushEligible ? authSessionService.getActiveFcmTokens(userId) : List.of();
            Long inboxNotificationId = null;

            // 1. Save to App Inbox (If important)
            if (shouldSaveToDatabase) {
                Notification notification = new Notification();
                notification.setUserId(userId);
                notification.setMessage(message);
                notification.setType(event.getType());
                notification.setCreatedAt(LocalDateTime.now());
                notification.setBudgetId(event.getContextId1());
                notification.setEnvelopeId(event.getContextId2());
                notification.setRedirectUrl(event.getActionUrl());
                notification.setRead(false);
                // Keep push-eligible inbox rows pending until Firebase accepts a token.
                notification.setPushSent(!pushEligible);
                notification = notificationRepository.save(notification);
                inboxNotificationId = notification.getId();
            }

            // 2. Send FCM Push (Only for HIGH or MEDIUM priority)
            if (pushEligible) {
                if ("stub".equals(activeProfile)) {
                    logger.info("ðŸ›‘ [STUB MODE] Simulated Push Notification to User {}: {}", userId, message);
                } else if (!fcmTokens.isEmpty()) {
                    if (firebaseMessaging != null) {
                        String dynamicTitle = getNotificationTitle(event.getType());
                        for (String fcmToken : fcmTokens) {
                            try {
                                sendFCMMessage(fcmToken, dynamicTitle, message, null,
                                        event.getActionUrl(), event.getType(), userId,
                                        event.getContextId1(), event.getContextId2(), null, null);
                                if (inboxNotificationId != null) {
                                    notificationRepository.markPushSent(inboxNotificationId);
                                }
                            } catch (DeadTokenException ignored) {
                                // Token was permanently invalid and has already been cleared.
                            } catch (RuntimeException fcmError) {
                                logger.warn("[FCM] Notification event push failed for user {} type={}",
                                        userId, event.getType(), fcmError);
                            }
                        }
                    } else {
                        logger.warn("âš ï¸ FCM is not initialized. Cannot send push.");
                    }
                } else {
                    logger.info("[FCM] No active token for user {} - queued for redelivery on next token registration", userId);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to process notification event for user {}", event.getUserId(), e);
        }
    }

    /**
     * Determines which events are permanently saved in the user's in-app Inbox.
     * Spammy events (like 5-minute warnings) return false.
     */
    private boolean shouldPersistToDatabase(NotificationType type) {
        if (type == null) return false;

        return switch (type) {
            // âŒ DO NOT SAVE TO INBOX (Transient, Nudges, or Bundled Noise)
            case PRE_DISBURSEMENT, DISBURSEMENT_REMINDER, POSITIVE_NUDGE, BUDGET_ENGAGEMENT_NUDGE,
                    SALARY_WEEK_NUDGE, POST_SALARY_NUDGE, MID_MONTH_NUDGE,
                    SPECIAL_OCCASION_NUDGE, BIRTHDAY_NUDGE,
                    SIGNUP_RETURN_NUDGE, ONBOARDING_REMINDER, WELCOME,
                    BUDGET_CREATION_FEE, ENVELOPE_CREATED -> false; // <--- Added here!

            // âœ… SAVE TO INBOX (Financial / Important)
            case WALLET_FUNDED, WALLET_DEPOSIT, REFUND_ISSUED,
                    WITHDRAWAL, EXTERNAL_TRANSFER, ENVELOPE_TRANSFER,
                    DISBURSEMENT_SUCCESS, EXPIRED_DISBURSEMENT, DISBURSEMENT_FAILED,
                    INSUFFICIENT_BALANCE, LOW_BALANCE_WARNING, ENVELOPE_LOW_BALANCE,
                    LIMIT_REACHED, BUDGET_LIMIT_WARNING, EMERGENCY_USED,
                    BUDGET_CREATION, BUDGET_SCHEDULED, BUDGET_ACTIVATED, BUDGET_COMPLETED,
                    ENVELOPE_UPDATED, ENVELOPE_LOCKED, ENVELOPE_UNLOCKED,
                    BUDGET_END, BUDGET_END_SOON, BUDGET_ENDS_TODAY,
                    AUTO_TRANSFER_SUCCESS, AUTO_TRANSFER_FAILED, AUTO_TRANSFER_INSUFFICIENT_FUNDS,
                    HOW_TO_USE_WISEMONIE, SYSTEM -> true;

            default -> true;
        };
    }

   /**
     * ðŸŸ¢ CENTRALIZED COPY: Premium Fintech Notification Phrasing
     */
        private String generateMessage(NotificationType type, Map<String, Object> params) {
        try {
            if (type == null) {
                return "Notification";
            }

            if (params == null || params.isEmpty()) {
                return "You have a new update from Wisemonie.";
            }

            return switch (type) {
                case WALLET_FUNDED, WALLET_DEPOSIT -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String sender = safeText(params.get("senderName"), null);

                    if (sender != null) {
                        yield "\u20A6" + amount + " has been credited to your wallet from " + sender + ".";
                    }
                    yield "Your wallet has been funded with \u20A6" + amount + ".";
                }
                case ENVELOPE_TRANSFER -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String remaining = formatAmount(params.getOrDefault("remaining", "0"));
                    String recipient = safeText(params.get("recipient"), null);

                    if (recipient != null) {
                        yield "You successfully sent \u20A6" + amount + " to " + recipient + ".";
                    }
                    yield "You moved \u20A6" + amount + ". You have \u20A6" + remaining + " left to spend.";
                }
                case EXTERNAL_TRANSFER -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String recipient = safeText(params.get("recipient"), "the recipient");
                    yield "Your transfer of \u20A6" + amount + " to " + recipient + " was successful.";
                }
                case BUDGET_CREATION -> {
                    String amount = formatAmount(params.getOrDefault("allocated", "0"));
                    String name = safeText(params.get("budgetName"), "your");
                    String fee = formatAmount(params.getOrDefault("fee", "0"));
                    String envCount = safeText(params.get("envelopeCount"), "your");
                    yield "And we're live! Your '" + name + "' budget is set up with \u20A6" + amount
                            + " across " + envCount + " envelopes. (Includes \u20A6" + fee + " setup fee).";
                }
                case BUDGET_SCHEDULED -> {
                    String amount = formatAmount(params.getOrDefault("allocated", "0"));
                    String name = safeText(params.get("budgetName"), "your");
                    String startDate = safeText(params.get("startDate"), "your start date");
                    String envCount = safeText(params.get("envelopeCount"), "your");
                    yield "Your '" + name + "' budget is funded and scheduled for " + startDate
                            + ". \u20A6" + amount + " is protected across " + envCount + " envelopes until it starts.";
                }
                case BUDGET_ACTIVATED -> {
                    String name = safeText(params.get("budgetName"), "your");
                    yield "Your scheduled budget '" + name + "' is now active. Your envelopes are ready based on their release rules.";
                }
                case BUDGET_UPDATED -> {
                    String custom = safeText(params.get("__message"), null);
                    if (custom != null) {
                        yield custom;
                    }
                    String name = safeText(params.get("budgetName"), "your");
                    yield "Your '" + name + "' budget has been updated.";
                }
                case BUDGET_CREATION_FEE -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    yield "A budget creation fee of \u20A6" + amount + " was deducted from your wallet.";
                }
                case BUDGET_COMPLETED -> {
                    String amount = formatAmount(params.getOrDefault("refunded", "0"));
                    String name = safeText(params.get("budgetName"), "your");
                    yield "Great job! Your budget '" + name + "' has ended. \u20A6" + amount
                            + " of unused funds has been returned to your wallet.";
                }
                case ENVELOPE_CREATED -> {
                    String name = safeText(params.get("envelopeName"), "new");
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    yield "You've set aside \u20A6" + amount + " in your new '" + name + "' envelope.";
                }
                case DISBURSEMENT_SUCCESS -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String name = safeText(params.get("envelopeName"), "selected");
                    yield "\u20A6" + amount + " has been unlocked in your '" + name + "' envelope. It's ready to spend!";
                }
                case DISBURSEMENT, DISBURSEMENT_READY -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String name = safeText(params.get("envelopeName"), "selected");
                    yield "\u20A6" + amount + " is ready in your '" + name + "' envelope.";
                }
                case PRE_DISBURSEMENT, DISBURSEMENT_REMINDER -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String name = safeText(params.get("envelopeName"), "selected");
                    String time = safeText(params.get("time"), "shortly");
                    yield "Heads up: \u20A6" + amount + " will unlock in your '" + name + "' envelope " + time + ".";
                }
                case EXPIRED_DISBURSEMENT -> {
                    String name = safeText(params.get("envelopeName"), "selected");
                    yield "The spending window for your '" + name + "' envelope has closed. The funds remain safely in your vault.";
                }
                case DISBURSEMENT_FAILED -> {
                    String name = safeText(params.get("envelopeName"), "selected");
                    yield "We could not complete the scheduled release for your '" + name + "' envelope. Please open Wisemonie to review it.";
                }
                case ENVELOPE_LOW_BALANCE -> {
                    String name = safeText(params.get("envelopeName"), "selected");
                    yield "Your '" + name + "' envelope did not have enough money for its scheduled release.";
                }
                case AUTO_TRANSFER_SUCCESS -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String recipient = safeText(params.get("recipient"), "the recipient");
                    String name = safeText(params.get("envelopeName"), "selected");
                    yield "₦" + amount + " from your '" + name + "' envelope has been auto-transferred to " + recipient + ".";
                }
                case AUTO_TRANSFER_FAILED -> {
                    String name = safeText(params.get("envelopeName"), "selected");
                    String reason = safeText(params.get("reason"), "Please check your envelope and try a manual transfer.");
                    yield "Auto-transfer failed for your '" + name + "' envelope. " + reason;
                }
                case AUTO_TRANSFER_INSUFFICIENT_FUNDS -> {
                    String amount = formatAmount(params.getOrDefault("amount", "0"));
                    String fee = formatAmount(params.getOrDefault("fee", "0"));
                    String name = safeText(params.get("envelopeName"), "selected");
                    yield "Auto-transfer of ₦" + amount + " from your '" + name + "' envelope was skipped. Insufficient funds to cover ₦" + fee + " in transfer charges.";
                }
                case LOW_BALANCE_WARNING -> "Your wallet balance is getting low. Please top up if you still have important payments planned.";
                case BUDGET_END_SOON, BUDGET_ENDING_SOON -> {
                    String name = safeText(params.get("budgetName"), "your");
                    yield "Your '" + name + "' budget is ending soon. Review your envelopes and prepare the next plan.";
                }
                case BUDGET_ENDS_TODAY -> {
                    String name = safeText(params.get("budgetName"), "your");
                    yield "Today is the last day of your '" + name + "' budget.";
                }
                case BUDGET_END, BUDGET_EXPIRED -> {
                    String name = safeText(params.get("budgetName"), "your");
                    yield "Your '" + name + "' budget has ended.";
                }
                default -> "You have a new update regarding your account.";
            };
        } catch (Exception e) {
            logger.error("Failed to generate notification message for type {}", type, e);
            return "Notification";
        }
    }
    /**
     * Helper to format amount consistently (â‚¦1,234.00)
     */
        private String formatAmount(Object value) {
        if (value == null) {
            return "0.00";
        }

        try {
            BigDecimal amount = new BigDecimal(value.toString());
            return String.format("%,.2f", amount);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private String safeText(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }

        String text = value.toString().trim();
        return text.isEmpty() ? fallback : text;
    }
    /**
     * Helper to add optional " • From: ..." part only if value exists
     */
        private String optionalPart(String label, Object value) {
        String safeValue = safeText(value, null);
        if (safeValue == null) {
            return "";
        }
        return " • " + label + safeValue;
    }

    // =========================================================================
    // 2. LEGACY METHODS (For backwards compatibility during migration)
    // =========================================================================

    public void sendNotification(String userId, String message, NotificationType type) {
        sendNotification(userId, message, type, null, null, null, null);
    }

    public void sendNotification(
            String userId,
            String message,
            NotificationType type,
            Long budgetId,
            Long envelopeId,
            String actionType,
            String redirectUrl) {
        // Enqueue onto the transactional outbox -- the single canonical delivery path.
        // Called inside a business @Transactional method, the outbox row commits/rolls
        // back atomically with that change (no ghost notifications). The outbox worker
        // then delivers it with retries + idempotency + one consistent priority rule.
        // The pre-formatted message rides in the payload so the worker doesn't re-build it.
        try {
            Long uId = Long.valueOf(userId);

            Map<String, Object> payload = new java.util.HashMap<>();
            if (message != null) payload.put("__message", message);
            if (actionType != null) payload.put("__actionType", actionType);
            if (redirectUrl != null) payload.put("__redirectUrl", redirectUrl);

            OutboxEvent event = new OutboxEvent();
            event.setEventType(type.name());
            event.setUserId(uId);
            event.setBudgetId(budgetId);
            event.setEnvelopeId(envelopeId);
            event.setPayload(payload);
            event.setStatus("PENDING");
            event.setCreatedAt(LocalDateTime.now());
            // Generous default TTL so nothing is dropped during offline / worker-downtime windows.
            event.setTtlSeconds(259_200L); // 72h
            outboxEventRepository.save(event);
        } catch (Exception e) {
            // A notification enqueue failure must never roll back the business change.
            logger.error("Failed to enqueue notification type {} for user {}", type, userId, e);
        }
    }

    /** Same outbox path as {@link #sendNotification}, with a lock-screen title. */
    public boolean sendTitledNotification(String userId, String title, String message, NotificationType type,
                                          String actionType, String redirectUrl) {
        try {
            if (userId == null || userId.isBlank()) {
                return false;
            }
            Long uId = Long.valueOf(userId);
            Map<String, Object> payload = new java.util.HashMap<>();
            if (title != null && !title.isBlank()) payload.put("__title", title);
            if (message != null) payload.put("__message", message);
            if (actionType != null) payload.put("__actionType", actionType);
            if (redirectUrl != null) payload.put("__redirectUrl", redirectUrl);

            OutboxEvent event = new OutboxEvent();
            event.setEventType(type.name());
            event.setUserId(uId);
            event.setPayload(payload);
            event.setStatus("PENDING");
            event.setCreatedAt(LocalDateTime.now());
            event.setTtlSeconds(259_200L);
            outboxEventRepository.save(event);
            return true;
        } catch (Exception e) {
            logger.error("Failed to enqueue titled notification type {} for user {}", type, userId, e);
            return false;
        }
    }

    public void enqueuePushOnlyNotification(Long userId,
                                            NotificationType type,
                                            String title,
                                            String message,
                                            String redirectUrl,
                                            long ttlSeconds) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("notification type is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }

        Map<String, Object> payload = new java.util.HashMap<>();
        if (title != null && !title.isBlank()) {
            payload.put("__title", title);
        }
        payload.put("__message", message);
        if (redirectUrl != null && !redirectUrl.isBlank()) {
            payload.put("__redirectUrl", redirectUrl);
        }

        OutboxEvent event = new OutboxEvent();
        event.setEventType(type.name());
        event.setUserId(userId);
        event.setPayload(payload);
        event.setStatus("PENDING");
        event.setCreatedAt(LocalDateTime.now());
        event.setTtlSeconds(ttlSeconds > 0 ? ttlSeconds : 86_400L);
        outboxEventRepository.save(event);
    }

    public void enqueueExternalPushOnlyNotification(Long userId,
                                                    NotificationType type,
                                                    String title,
                                                    String message,
                                                    String externalUrl,
                                                    long ttlSeconds) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("notification type is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        if (externalUrl == null || externalUrl.isBlank()) {
            throw new IllegalArgumentException("externalUrl is required");
        }

        Map<String, Object> payload = new java.util.HashMap<>();
        if (title != null && !title.isBlank()) {
            payload.put("__title", title);
        }
        payload.put("__message", message);
        payload.put("__actionType", ACTION_OPEN_EXTERNAL_URL);
        payload.put("__redirectUrl", externalUrl);
        payload.put("__androidUrl", externalUrl);
        payload.put("__iosUrl", externalUrl);

        OutboxEvent event = new OutboxEvent();
        event.setEventType(type.name());
        event.setUserId(userId);
        event.setPayload(payload);
        event.setStatus("PENDING");
        event.setCreatedAt(LocalDateTime.now());
        event.setTtlSeconds(ttlSeconds > 0 ? ttlSeconds : 86_400L);
        outboxEventRepository.save(event);
    }

    @Transactional
    public Long enqueueAdminBroadcastNotification(Long userId,
                                                  NotificationType type,
                                                  String title,
                                                  String message,
                                                  String actionType,
                                                  String redirectUrl,
                                                  long ttlSeconds) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("notification type is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }

        Map<String, Object> payload = new java.util.HashMap<>();
        if (title != null && !title.isBlank()) {
            payload.put("__title", title);
        }
        payload.put("__message", message);
        if (actionType != null && !actionType.isBlank()) {
            payload.put("__actionType", actionType);
        }
        if (redirectUrl != null && !redirectUrl.isBlank()) {
            payload.put("__redirectUrl", redirectUrl);
            if (ACTION_OPEN_EXTERNAL_URL.equals(actionType)) {
                payload.put("__androidUrl", redirectUrl);
                payload.put("__iosUrl", redirectUrl);
            }
        }

        OutboxEvent event = new OutboxEvent();
        event.setEventType(type.name());
        event.setUserId(userId);
        event.setPayload(payload);
        event.setStatus("PENDING");
        event.setCreatedAt(LocalDateTime.now());
        event.setTtlSeconds(ttlSeconds > 0 ? ttlSeconds : 86_400L);
        outboxEventRepository.save(event);
        return event.getId();
    }

    // =========================================================================
    // 3. CORE FCM LOGIC
    // =========================================================================

    /**
     * Per-type FCM Time-To-Live (milliseconds).
     *
     * FCM honours TTL: if the device is offline when we push, FCM holds the message
     * for at most {@code ttlMs}. After that it drops it silently — exactly what we want
     * for time-sensitive nudges (PRE_DISBURSEMENT) but NOT for financial events.
     *
     * PRE_DISBURSEMENT / DISBURSEMENT_REMINDER  -> 45 min  (stale nudge is useless)
     * DISBURSEMENT_SUCCESS / credits / transfers -> 72 h   (must arrive eventually)
     * BUDGET_END_SOON / warnings                -> 24 h
     * LOW_BALANCE / ENVELOPE_LOW_BALANCE        ->  6 h
     * Default                                   -> 24 h
     */
    private long computeFcmTtlMs(NotificationType type) {
        if (type == null) return 86_400_000L;
        return switch (type) {
            case PRE_DISBURSEMENT, DISBURSEMENT_REMINDER                  -> 2_700_000L;   // 45 min
            case ONBOARDING_REMINDER                                      -> 7_200_000L;   // 2 h
            case SIGNUP_RETURN_NUDGE                                      -> 172_800_000L; // 48 h
            case SERVICE_OUTAGE, SPECIAL_ANNOUNCEMENT                     -> 86_400_000L;  // 24 h
            case SCHEDULED_MAINTENANCE                                    -> 259_200_000L; // 72 h
            case DISBURSEMENT_SUCCESS, DISBURSEMENT_READY, DISBURSEMENT,
                 WALLET_FUNDED, WALLET_DEPOSIT, EXTERNAL_TRANSFER,
                 ENVELOPE_TRANSFER, REFUND_ISSUED, DISBURSEMENT_REFUNDED,
                 BUDGET_UNALLOCATED_REFUNDED, BUDGET_SCHEDULED,
                 BUDGET_ACTIVATED, AUTO_TRANSFER_SUCCESS, HOW_TO_USE_WISEMONIE
                                                                            -> 259_200_000L; // 72 h
            case AUTO_TRANSFER_FAILED, AUTO_TRANSFER_INSUFFICIENT_FUNDS    -> 86_400_000L;  // 24 h
            case LOW_BALANCE_WARNING, ENVELOPE_LOW_BALANCE               -> 21_600_000L;  //  6 h
            default                                                       -> 86_400_000L;  // 24 h
        };
    }

    /**
     * Sends one FCM push notification.
     *
     * @param envelopeId used to group related envelope notifications while the
     *                   visible Android tag stays unique per notification.
     */
    private void sendFCMMessage(String fcmToken, String title, String body, String actionType,
                                String redirectUrl, NotificationType type, Long userId, Long envelopeId) {
        sendFCMMessage(fcmToken, title, body, actionType, redirectUrl, type, userId, null, envelopeId, null, null);
    }

    private void sendFCMMessage(String fcmToken, String title, String body, String actionType,
                                String redirectUrl, NotificationType type, Long userId, Long envelopeId,
                                String devicePlatform) {
        sendFCMMessage(fcmToken, title, body, actionType, redirectUrl, type, userId, null, envelopeId,
                devicePlatform, null);
    }

    private void sendFCMMessage(String fcmToken, String title, String body, String actionType,
                                String redirectUrl, NotificationType type, Long userId, Long budgetId,
                                Long envelopeId, String devicePlatform, Long notificationContextId) {
        try {
            // Grouping keeps related notifications visually organized, while the
            // notification tag/id below stays unique so quick back-to-back financial
            // events do not replace each other in Android's notification tray.
            String groupKey = getGroupKey(type, userId, envelopeId);
            String notificationTag = buildNotificationTag(type, userId, envelopeId, notificationContextId);
            String notificationId = buildNotificationId(notificationTag);

            // ── Per-type FCM TTL ──────────────────────────────────────────────────────
            long ttlMs = computeFcmTtlMs(type);

            // 1. Define the Visible Notification (For System Tray)
            com.google.firebase.messaging.Notification notificationPayload =
                    com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build();

            AndroidConfig androidConfig = AndroidConfig.builder()
                    .setTtl(ttlMs)
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(AndroidNotification.builder()
                            .setChannelId("wisemonie_alerts_v2") // Must match Flutter Channel
                            .setSound("wisemonie")
                            .setDefaultSound(false)
                            .setTag(notificationTag)
                            .setTitle(title)
                            .setBody(body)
                            .setPriority(AndroidNotification.Priority.MAX)
                            .setVisibility(AndroidNotification.Visibility.PUBLIC)
                            .setClickAction("FLUTTER_NOTIFICATION_CLICK")
                            .build())
                    .build();

            ApnsConfig apnsConfig = ApnsConfig.builder()
                    .setAps(Aps.builder()
                            .setSound("wisemonie.wav")
                            .setContentAvailable(true)
                            .setThreadId(groupKey)
                            .build())
                    .build();

            Message.Builder messageBuilder = Message.builder()
                    .setToken(fcmToken)
                    .setNotification(notificationPayload)
                    .setAndroidConfig(androidConfig)
                    .setApnsConfig(apnsConfig);

            // Data Payload for Flutter navigation
            messageBuilder.putData("click_action", "FLUTTER_NOTIFICATION_CLICK");
            messageBuilder.putData("eventType", type != null ? type.name() : "UNKNOWN");
            messageBuilder.putData("notificationId", notificationId);
            messageBuilder.putData("notificationTag", notificationTag);
            messageBuilder.putData("groupKey", groupKey);
            if (userId != null) messageBuilder.putData("userId", userId.toString());
            if (budgetId != null) messageBuilder.putData("budgetId", budgetId.toString());
            if (envelopeId != null) messageBuilder.putData("envelopeId", envelopeId.toString());
            if (actionType != null) messageBuilder.putData("actionType", actionType);
            if (devicePlatform != null) messageBuilder.putData("devicePlatform", devicePlatform);

            if (isExternalPushAction(actionType)) {
                if (redirectUrl != null && !redirectUrl.isBlank()) {
                    messageBuilder.putData("url", redirectUrl);
                    messageBuilder.putData("externalUrl", redirectUrl);
                    messageBuilder.putData("redirectUrl", redirectUrl);
                }
            } else {
                String route = deepLinkService.normalizeAppRoute(redirectUrl);
                String deepLinkUrl = deepLinkService.toDeepLink(route);
                if (redirectUrl != null) messageBuilder.putData("redirectUrl", redirectUrl);
                messageBuilder.putData("route", route);
                messageBuilder.putData("deepLinkUrl", deepLinkUrl);
            }

            // Text Payload for UI
            messageBuilder.putData("title", title);
            messageBuilder.putData("body", body);

            String messageId = firebaseMessaging.send(messageBuilder.build());
            logger.info("[FCM] Delivered to user {} type={} envelope={} notificationTag={} ttlMs={} messageId={}",
                    userId, type, envelopeId, notificationTag, ttlMs, messageId);

            try {
                authSessionService.recordPushSuccess(fcmToken);
            } catch (Exception ignored) {
            }

        } catch (FirebaseMessagingException e) {
            String errorCode = e.getMessagingErrorCode() != null
                    ? e.getMessagingErrorCode().toString() : "UNKNOWN";

            if (errorCode.equals("UNREGISTERED") || errorCode.equals("NOT_FOUND")) {
                // Token is genuinely dead — apply the 3-strike grace period.
                // Strike 1-2: log + increment counter, keep the token active.
                // Strike 3:   deactivate from all sources.
                logger.warn("[FCM] Dead token signal for user {} (code={}). Applying strike.", userId, errorCode);
                try {
                    authSessionService.clearDeadFcmToken(fcmToken, errorCode);
                } catch (Exception ex) {
                    logger.error("[FCM] Failed to process dead token for user {}", userId, ex);
                }
                throw new DeadTokenException(fcmToken);
            } else {
                // Transient / payload error (QUOTA_EXCEEDED, INTERNAL, UNAVAILABLE,
                // INVALID_ARGUMENT, etc.) — rethrow so the outbox worker retries.
                // INVALID_ARGUMENT means the MESSAGE is broken, not the token.
                logger.error("[FCM] Transient error for user {} (code={}). Will retry via outbox.", userId, errorCode, e);
                throw new RuntimeException("FCM transient failure: " + errorCode, e);
            }
        } catch (Exception e) {
            // Any other unexpected error — rethrow for outbox retry
            logger.error("[FCM] Unexpected error sending push to user {}", userId, e);
            throw new RuntimeException("FCM unexpected failure", e);
        }
    }

    // =========================================================================
    // 4. CONFIGURATION HELPERS
    // =========================================================================

    private String getNotificationTitle(NotificationType type) {
        if (type == null) return "Wisemonie";
        if (type == NotificationType.BUDGET_SCHEDULED) return "Budget Scheduled";
        if (type == NotificationType.BUDGET_ACTIVATED) return "Budget Active";

        return switch (type) {
            case WALLET_FUNDED, WALLET_DEPOSIT, REFUND_ISSUED, DISBURSEMENT_REFUNDED, BUDGET_UNALLOCATED_REFUNDED -> "Credit Alert 🚀";
            case WITHDRAWAL, EXTERNAL_TRANSFER, ENVELOPE_TRANSFER, BUDGET_CREATION_FEE -> "Debit Alert 💸";
            case DISBURSEMENT, DISBURSEMENT_SUCCESS, DISBURSEMENT_READY -> "Funds Released 🔓";
            case PRE_DISBURSEMENT, DISBURSEMENT_REMINDER -> "Funds Unlocking Soon ⏳";
            case EXPIRED_DISBURSEMENT, DISBURSEMENT_FAILED -> "Disbursement Expired ❌";
            case INSUFFICIENT_BALANCE -> "Transaction Declined ⛔";
            case LOW_BALANCE_WARNING, ENVELOPE_LOW_BALANCE -> "Low Balance Warning 📉";
            case LIMIT_REACHED, BUDGET_LIMIT_WARNING -> "Spending Limit Hit ⚠️";
            case EMERGENCY_USED -> "Emergency Fund Used 🚨";
            case BUDGET_CREATION, BUDGET_CREATION_SUCCESS -> "Budget Active 🎯";
            case BUDGET_COMPLETED, GOAL_ACHIEVED -> "Goal Smashed! 🏆";
            case ENVELOPE_CREATED -> "New Envelope ✉️";
            case ENVELOPE_UPDATED, BUDGET_UPDATED, ENVELOPE_UNLOCKED -> "Update Successful ✅";
            case ENVELOPE_LOCKED -> "Envelope Locked 🔒";
            case BUDGET_END, BUDGET_EXPIRED -> "Budget Ended 🏁";
            case BUDGET_END_SOON, BUDGET_ENDING_SOON -> "Budget Ending Soon ⏳";
            case BUDGET_ENDS_TODAY -> "Budget Ends Today ⏰";
            case MATURITY_ALERT -> "Maturity Alert 📅";
            case WEEKLY_SUMMARY -> "Weekly Recap 📊";
            case WELCOME -> "Welcome to Wisemonie 👋";
            case SALARY_WEEK_NUDGE -> "Happy salary week! \uD83D\uDE42";
            case POST_SALARY_NUDGE -> "Plan the month \uD83D\uDE0A";
            case MID_MONTH_NUDGE -> "Money check \uD83E\uDDED";
            case SPECIAL_OCCASION_NUDGE -> "Wisemonie note \uD83C\uDF89";
            case BIRTHDAY_NUDGE -> "Happy birthday \uD83C\uDF82";
            case HOW_TO_USE_WISEMONIE -> "Watch the Wisemonie guide \uD83C\uDFA5";
            case SIGNUP_RETURN_NUDGE -> "Come back to Wisemonie \uD83E\uDDED";
            case ONBOARDING_REMINDER -> "Complete your profile \uD83D\uDCDD";
            case AUTO_TRANSFER_SUCCESS -> "Auto-Transfer Sent 🚀";
            case AUTO_TRANSFER_FAILED -> "Auto-Transfer Failed ❌";
            case AUTO_TRANSFER_INSUFFICIENT_FUNDS -> "Auto-Transfer Skipped ⚠️";
            case ADMIN_RECONCILIATION_ALERT -> "Reconciliation Alert";
            case SERVICE_OUTAGE -> "Service Alert";
            case SCHEDULED_MAINTENANCE -> "Scheduled Maintenance";
            case SPECIAL_ANNOUNCEMENT -> "Wisemonie Update";
            case SYSTEM -> "System Update 📢";
            case POSITIVE_NUDGE -> "Keep it up! 💪";
            default -> "Wisemonie Notification";
        };
    }
    /**
     * Builds an Android/APNs group key for a notification.
     *
     * IMPORTANT: disbursement types get a PER-ENVELOPE unique key.
     * Using a shared "DISBURSEMENTS" key caused Android to keep only the
     * last notification and silently discard all earlier ones, so users
     * with multiple envelopes would miss every disbursement except the last.
     *
     * Transaction types still share a group key so that a rapid burst of
     * wallet-top-up events is consolidated — that's intentional and user-friendly.
     */
    private String getGroupKey(NotificationType type, Long userId, Long envelopeId) {
        if (type == null) return "GENERAL";
        return switch (type) {
            // Per-envelope key: each disbursement is an independent financial event
            case DISBURSEMENT, DISBURSEMENT_SUCCESS, DISBURSEMENT_READY ->
                    "DISB_" + (userId != null ? userId : "0")
                            + "_" + (envelopeId != null ? envelopeId : "0");
            case AUTO_TRANSFER_SUCCESS, AUTO_TRANSFER_FAILED, AUTO_TRANSFER_INSUFFICIENT_FUNDS ->
                    "AUTOXFER_" + (userId != null ? userId : "0")
                            + "_" + (envelopeId != null ? envelopeId : "0");
            case BUDGET_ACTIVATED ->
                    "BUDGETACT_" + (userId != null ? userId : "0");
            case WALLET_DEPOSIT, WALLET_FUNDED, ENVELOPE_TRANSFER -> "TRANSACTIONS";
            case LOW_BALANCE_WARNING, BUDGET_LIMIT_WARNING -> "WARNINGS";
            default -> "GENERAL";
        };
    }

    private String buildNotificationTag(NotificationType type, Long userId, Long envelopeId, Long contextId) {
        String typeName = type != null ? type.name() : "UNKNOWN";
        Long id = contextId != null
                ? contextId
                : System.currentTimeMillis() + Math.abs(Objects.hash(typeName, userId, envelopeId));
        return "MW_" + typeName + "_" + id;
    }

    private String buildNotificationId(String notificationTag) {
        return Integer.toString(notificationTag.hashCode() & 0x7fffffff);
    }

    private NotificationPriority getPriority(NotificationType type) {
        return switch (type) {
            case WALLET_DEPOSIT, WALLET_FUNDED, ENVELOPE_TRANSFER, EXTERNAL_TRANSFER,
                    LOW_BALANCE_WARNING, INSUFFICIENT_BALANCE, DISBURSEMENT, DISBURSEMENT_SUCCESS, DISBURSEMENT_READY, BUDGET_COMPLETED,
                    BUDGET_SCHEDULED, BUDGET_ACTIVATED,
                    SAVINGS_GOAL_CREATED, SAVINGS_DEPOSIT, GOAL_ACHIEVED,
                    // "Your money is ready" is the single most important savings push —
                    // it was missing here, falling to default LOW = push never sent.
                    SAVINGS_MATURED,
                    AUTO_TRANSFER_SUCCESS, AUTO_TRANSFER_FAILED, AUTO_TRANSFER_INSUFFICIENT_FUNDS,
                    SERVICE_OUTAGE,
                    ADMIN_PAYEELORD_LOW_BALANCE,
                    ADMIN_RECONCILIATION_ALERT -> NotificationPriority.HIGH;

            case BUDGET_LIMIT_WARNING, BUDGET_END_SOON, BUDGET_ENDING_SOON, BUDGET_ENDS_TODAY,
                    PRE_DISBURSEMENT, DISBURSEMENT_REMINDER,
                    EXPIRED_DISBURSEMENT, DISBURSEMENT_FAILED,
                    SAVINGS_MATURING_SOON,
                    BUDGET_ENGAGEMENT_NUDGE,
                    SALARY_WEEK_NUDGE, POST_SALARY_NUDGE, MID_MONTH_NUDGE,
                    SPECIAL_OCCASION_NUDGE, BIRTHDAY_NUDGE, HOW_TO_USE_WISEMONIE,
                    SIGNUP_RETURN_NUDGE, ONBOARDING_REMINDER,
                    SCHEDULED_MAINTENANCE, SPECIAL_ANNOUNCEMENT,
                    WELCOME -> NotificationPriority.MEDIUM;
            default -> NotificationPriority.LOW;
        };
    }

    // =========================================================================
    // 5. EMAIL & SMS METHODS
    // =========================================================================

    /**
     * Returns the absolute public URL for the Wisemonie logo.
     * The logo is served by Spring Boot from {@code static/images/wisemonie-logo.png}.
     * All email templates reference it via {@code th:src="${logoUrl}"} so the image
     * renders in every email client (webmail, mobile, desktop) without CID issues.
     */
    private String logoUrl() {
        return appBaseUrl + "/images/main_logo.png";
    }

    private String normalizeFundingBankName(String bankName) {
        if (bankName == null || bankName.isBlank()) {
            return "Rubies Microfinance Bank / Rubies MFB";
        }
        String normalized = bankName.trim();
        String lower = normalized.toLowerCase();
        if (lower.contains("rubies") && !lower.contains("microfinance")) {
            return "Rubies Microfinance Bank / Rubies MFB";
        }
        return normalized;
    }

    /** No-op kept for backward compatibility — CID approach replaced by hosted URL. */
    private void attachLogo(MimeMessageHelper helper) {
        // intentionally empty — logo is now served via public URL (logoUrl())
    }

    private void setWisemonieSender(MimeMessageHelper helper) throws MessagingException {
        try {
            helper.setFrom(fromEmail, "Timi from Wisemonie");
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("Failed to encode Wisemonie email sender name", e);
        }
    }

    @Async
    public void sendWelcomeEmail(String email, String firstName, String accountNumber, String bankName, BigDecimal balance) {
        if ("stub".equals(activeProfile) || mailSender == null) return;
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("accountNumber", accountNumber);
            context.setVariable("bankName", normalizeFundingBankName(bankName));

            String htmlContent = templateEngine.process("welcome-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(email);
            helper.setSubject("Your Wisemonie wallet is ready. Search Rubies MFB to fund it");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent HTML welcome email to {}", email);
        } catch (MessagingException e) {
            logger.error("Failed to send welcome email to {}", email, e);
        }
    }

    public void sendAdminBroadcastEmail(String email,
                                        String firstName,
                                        String subject,
                                        String tag,
                                        String title,
                                        String body,
                                        String footerNote,
                                        String ctaLabel,
                                        String ctaUrl) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending admin broadcast email '{}' to {}", subject, email);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("subject", subject != null && !subject.isBlank() ? subject : "Wisemonie update");
            context.setVariable("tag", tag != null && !tag.isBlank() ? tag : "Wisemonie update");
            context.setVariable("title", title != null && !title.isBlank() ? title : "Wisemonie update");
            context.setVariable("body", body != null && !body.isBlank() ? body : "We have an update for you.");
            context.setVariable("footerNote", footerNote != null && !footerNote.isBlank()
                    ? footerNote
                    : "Thank you for using Wisemonie.");
            context.setVariable("ctaLabel", ctaLabel != null && !ctaLabel.isBlank() ? ctaLabel : "Open Wisemonie");
            context.setVariable("ctaUrl", ctaUrl != null && !ctaUrl.isBlank() ? ctaUrl : appBaseUrl);
            context.setVariable("hasCta", ctaUrl != null && !ctaUrl.isBlank());

            String htmlContent = templateEngine.process("admin-broadcast", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(email);
            helper.setSubject(subject != null && !subject.isBlank() ? subject : "Wisemonie update");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent admin broadcast email '{}' to {}", subject, email);
        } catch (Exception e) {
            logger.error("Failed to send admin broadcast email '{}' to {}", subject, email, e);
        }
    }

    /**
     * "Complete your profile" nudge email for users who signed up but never
     * finished KYC/profile (and therefore never got a wallet). Sent on a
     * cadence by {@code IncompleteSignupLifecycleManager}: ~24h after signup,
     * then on staggered user-specific days with rotating campaign copy, capped
     * at the first 15 days after signup.
     *
     * @param email               recipient
     * @param firstName           best-effort first name (falls back to "there")
     * @param daysSinceSignup     used only to pick a fitting subject line/tone
     * @param daysRemaining       days left before the account is purged — shown
     *                            only when {@code showUrgencyNotice} is true
     * @param showUrgencyNotice   true for the later reminders (close to the
     *                            30-day cutoff), renders the soft warning block
     */
    @Async
    public void sendOnboardingReminderEmail(String email, String firstName,
                                             int daysSinceSignup, int daysRemaining,
                                             boolean showUrgencyNotice, int reminderNumber) {
        if ("stub".equals(activeProfile) || mailSender == null) return;
        try {
            OnboardingReminderCopy copy = onboardingReminderCopy(reminderNumber, showUrgencyNotice);
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("daysRemaining", daysRemaining);
            context.setVariable("showUrgencyNotice", showUrgencyNotice);
            context.setVariable("introParagraph", copy.introParagraph());
            context.setVariable("supportParagraph", copy.supportParagraph());
            context.setVariable("bvnTrustLine",
                    "BVN is used so our licensed banking partner can verify your identity, open your wallet account and give you an account number for funding. It is not requested without a purpose.");
            context.setVariable("ctaSubtext", copy.ctaSubtext());
            context.setVariable("firstPointIcon", copy.firstPointIcon());
            context.setVariable("firstPointTitle", copy.firstPointTitle());
            context.setVariable("firstPointBody", copy.firstPointBody());
            context.setVariable("secondPointIcon", copy.secondPointIcon());
            context.setVariable("secondPointTitle", copy.secondPointTitle());
            context.setVariable("secondPointBody", copy.secondPointBody());
            context.setVariable("thirdPointIcon", copy.thirdPointIcon());
            context.setVariable("thirdPointTitle", copy.thirdPointTitle());
            context.setVariable("thirdPointBody", copy.thirdPointBody());
            context.setVariable("closingLine", copy.closingLine());
            addAppDownloadContext(context);

            String htmlContent = templateEngine.process("onboarding-reminder", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(email);
            helper.setSubject(copy.subject());
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent onboarding-reminder email #{} (day {} since signup, urgent={}) to {}",
                    reminderNumber, daysSinceSignup, showUrgencyNotice, email);
        } catch (MessagingException e) {
            logger.error("Failed to send onboarding-reminder email to {}", email, e);
        }
    }

    /** Budget usage nudge sent by BudgetEngagementNudgeService. */
    @Async
    public void sendBudgetEngagementNudgeEmail(String email,
                                                String firstName,
                                                BudgetEngagementNudgeType type,
                                                BigDecimal walletBalance,
                                                String lastBudgetName) {
        BudgetEngagementNudgeType safeType = type != null
                ? type
                : BudgetEngagementNudgeType.WALLET_READY_NO_BUDGET;

        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending budget engagement nudge {} to {}", safeType, email);
            return;
        }

        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("tag", budgetNudgeTag(safeType));
            context.setVariable("headline", budgetNudgeHeadline(safeType));
            context.setVariable("subheadline", budgetNudgeSubheadline(safeType));
            context.setVariable("paragraphs", budgetNudgeParagraphs(safeType, lastBudgetName));
            context.setVariable("adviceItems", budgetNudgeAdviceItems(safeType));
            context.setVariable("footerNote", budgetNudgeFooterNote(safeType));
            context.setVariable("hasBalance", walletBalance != null && walletBalance.compareTo(BigDecimal.ZERO) > 0);
            context.setVariable("walletBalance", formatAmount(walletBalance != null ? walletBalance : BigDecimal.ZERO));
            addAppDownloadContext(context);

            String htmlContent = templateEngine.process("budget-engagement-nudge", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(email);
            helper.setSubject(budgetNudgeSubject(safeType));
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent budget engagement nudge {} to {}", safeType, email);
        } catch (Exception e) {
            logger.error("Failed to send budget engagement nudge {} to {}", safeType, email, e);
        }
    }

    public void sendBudgetEngagementNudgePush(Long userId,
                                              BudgetEngagementNudgeType type,
                                              BigDecimal walletBalance,
                                              String lastBudgetName) {
        if (userId == null) {
            return;
        }

        BudgetEngagementNudgeType safeType = type != null
                ? type
                : BudgetEngagementNudgeType.WALLET_READY_NO_BUDGET;

        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("__title", budgetNudgePushTitle(safeType));
            payload.put("__message", budgetNudgePushMessage(safeType, walletBalance, lastBudgetName));
            payload.put("__actionType", ACTION_OPEN_EXTERNAL_URL);
            payload.put("__redirectUrl", ANDROID_PLAY_STORE_URL);
            payload.put("__androidUrl", ANDROID_PLAY_STORE_URL);

            String iosAppStoreUrl = deepLinkService.iosAppStoreUrl();
            if (iosAppStoreUrl != null && !iosAppStoreUrl.isBlank()) {
                payload.put("__iosUrl", iosAppStoreUrl);
            }

            OutboxEvent event = new OutboxEvent();
            event.setEventType(NotificationType.BUDGET_ENGAGEMENT_NUDGE.name());
            event.setUserId(userId);
            event.setPayload(payload);
            event.setStatus("PENDING");
            event.setCreatedAt(LocalDateTime.now());
            event.setTtlSeconds(86_400L);
            outboxEventRepository.save(event);
        } catch (Exception e) {
            logger.error("Failed to enqueue budget engagement push {} for user {}", safeType, userId, e);
        }
    }

    @Async
    public void sendEngagementNudgeEmail(String email,
                                         String firstName,
                                         String subject,
                                         String tag,
                                         String headline,
                                         String body,
                                         String footerNote,
                                         String ctaLabel,
                                         String ctaUrl) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending engagement nudge email '{}' to {}", subject, email);
            return;
        }

        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("subject", subject != null && !subject.isBlank() ? subject : "Wisemonie");
            context.setVariable("tag", tag != null && !tag.isBlank() ? tag : "Money check");
            context.setVariable("headline", headline != null && !headline.isBlank() ? headline : "A calmer money week");
            context.setVariable("body", body != null && !body.isBlank()
                    ? body
                    : "Your money can feel easier when it has a clear plan.");
            context.setVariable("footerNote", footerNote != null && !footerNote.isBlank()
                    ? footerNote
                    : "Wisemonie is here to help you spend with less pressure.");
            context.setVariable("ctaLabel", ctaLabel != null && !ctaLabel.isBlank() ? ctaLabel : "Open Wisemonie");
            context.setVariable("ctaUrl", ctaUrl != null && !ctaUrl.isBlank() ? ctaUrl : appBaseUrl);

            String htmlContent = templateEngine.process("engagement-nudge", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(email);
            helper.setSubject(subject != null && !subject.isBlank() ? subject : "Wisemonie");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent engagement nudge email '{}' to {}", subject, email);
        } catch (Exception e) {
            logger.error("Failed to send engagement nudge email '{}' to {}", subject, email, e);
        }
    }

    @Async
    public void sendLifecycleRecoveryEmail(String email,
                                           String firstName,
                                           LifecycleRecoveryType type,
                                           String accountNumber,
                                           String bankName) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending lifecycle recovery {} to {}", type, email);
            return;
        }

        LifecycleRecoveryType safeType = type != null ? type : LifecycleRecoveryType.NO_WALLET;
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("subject", lifecycleRecoverySubject(safeType));
            context.setVariable("tag", lifecycleRecoveryTag(safeType));
            context.setVariable("headline", lifecycleRecoveryHeadline(safeType));
            context.setVariable("introParagraph", lifecycleRecoveryIntro(safeType));
            context.setVariable("insightParagraph", lifecycleRecoveryInsight(safeType));
            context.setVariable("actionTitle", lifecycleRecoveryActionTitle(safeType));
            context.setVariable("actionItems", lifecycleRecoveryActionItems(safeType));
            context.setVariable("trustNote", lifecycleRecoveryTrustNote(safeType));
            context.setVariable("footerNote", lifecycleRecoveryFooterNote(safeType));
            context.setVariable("hasFundingDetails",
                    safeType != LifecycleRecoveryType.NO_WALLET
                            && accountNumber != null
                            && !accountNumber.isBlank());
            context.setVariable("accountNumber", accountNumber);
            context.setVariable("bankName", normalizeFundingBankName(bankName));
            addAppDownloadContext(context);

            String htmlContent = templateEngine.process("lifecycle-recovery", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(email);
            helper.setSubject(lifecycleRecoverySubject(safeType));
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent lifecycle recovery {} email to {}", safeType, email);
        } catch (Exception e) {
            logger.error("Failed to send lifecycle recovery {} email to {}", safeType, email, e);
        }
    }

    private String lifecycleRecoverySubject(LifecycleRecoveryType type) {
        return switch (type) {
            case NO_WALLET -> "Your Wisemonie wallet was not created yet";
            case WALLET_READY_NOT_FUNDED -> "Still trying to fund your Wisemonie wallet?";
            case FUNDED_NO_PLAN -> "Your money is inside Wisemonie. Give it instructions";
        };
    }

    private String lifecycleRecoveryTag(LifecycleRecoveryType type) {
        return switch (type) {
            case NO_WALLET -> "Setup paused";
            case WALLET_READY_NOT_FUNDED -> "Funding help";
            case FUNDED_NO_PLAN -> "Next small step";
        };
    }

    private String lifecycleRecoveryHeadline(LifecycleRecoveryType type) {
        return switch (type) {
            case NO_WALLET -> "Your wallet stopped before it was created";
            case WALLET_READY_NOT_FUNDED -> "The bank name to search is Rubies MFB";
            case FUNDED_NO_PLAN -> "One big balance still leaves too much in your head";
        };
    }

    private String lifecycleRecoveryIntro(LifecycleRecoveryType type) {
        return switch (type) {
            case NO_WALLET ->
                    "You started setting up Wisemonie, but the wallet account was not created yet.";
            case WALLET_READY_NOT_FUNDED ->
                    "Your wallet account is ready, but it looks like funding may have been the part that got unclear.";
            case FUNDED_NO_PLAN ->
                    "You already put money inside Wisemonie. That means the intention was real.";
        };
    }

    private String lifecycleRecoveryInsight(LifecycleRecoveryType type) {
        return switch (type) {
            case NO_WALLET ->
                    "Most people pause here because BVN feels sensitive, or because something interrupts the setup. The reason is simple: our licensed banking partner needs it to verify you and create your wallet account number.";
            case WALLET_READY_NOT_FUNDED ->
                    "One common mistake is searching for Wisemonie as the bank name. Your wallet account is provided through Rubies Microfinance Bank, so that is the name your banking app needs.";
            case FUNDED_NO_PLAN ->
                    "When money stays as one big balance, every spend becomes a fresh decision. That is where pressure, impulse and mental maths enter.";
        };
    }

    private String lifecycleRecoveryActionTitle(LifecycleRecoveryType type) {
        return switch (type) {
            case NO_WALLET -> "Finish the wallet step";
            case WALLET_READY_NOT_FUNDED -> "Fund with the right bank name";
            case FUNDED_NO_PLAN -> "Start with one instruction";
        };
    }

    private List<String> lifecycleRecoveryActionItems(LifecycleRecoveryType type) {
        return switch (type) {
            case NO_WALLET -> List.of(
                    "Open Wisemonie on your phone.",
                    "Continue identity verification.",
                    "Complete the BVN step if requested.",
                    "Once verified, your wallet account number can be created."
            );
            case WALLET_READY_NOT_FUNDED -> List.of(
                    "Open your banking app.",
                    "Choose transfer to another bank.",
                    "Search for Rubies MFB, Rubies Microfinance Bank or Rubies.",
                    "Paste your Wisemonie account number.",
                    "Confirm the account name, then send."
            );
            case FUNDED_NO_PLAN -> List.of(
                    "Open Wisemonie on your phone.",
                    "Create one simple money plan.",
                    "Start with food, transport, bills, savings, family or enjoyment.",
                    "Put only what you can plan today. You can improve the rest later."
            );
        };
    }

    private String lifecycleRecoveryTrustNote(LifecycleRecoveryType type) {
        return switch (type) {
            case NO_WALLET ->
                    "BVN is used so our licensed banking partner can verify your identity, open your wallet account and give you an account number for funding. It is not requested without a purpose.";
            case WALLET_READY_NOT_FUNDED ->
                    "Do not search for Wisemonie as the bank name. Search for Rubies MFB or Rubies Microfinance Bank.";
            case FUNDED_NO_PLAN -> null;
        };
    }

    private String lifecycleRecoveryFooterNote(LifecycleRecoveryType type) {
        return switch (type) {
            case NO_WALLET ->
                    "You do not need to set up everything today. Finish the wallet step first.";
            case WALLET_READY_NOT_FUNDED ->
                    "If your bank app still does not show Rubies, reply to this email and tell us the bank app you used.";
            case FUNDED_NO_PLAN ->
                    "This is not about a perfect plan. It is about deciding before pressure shows up.";
        };
    }

    private String budgetNudgeSubject(BudgetEngagementNudgeType type) {
        return switch (type) {
            case FUNDED_WALLET_NO_BUDGET -> "Your Wisemonie balance needs a simple plan";
            case POST_BUDGET_COMPLETION -> "Ready for your next money plan?";
            case WALLET_READY_NO_BUDGET -> "Your Wisemonie wallet is ready for a plan";
        };
    }

    private String budgetNudgePushTitle(BudgetEngagementNudgeType type) {
        return switch (type) {
            case FUNDED_WALLET_NO_BUDGET -> "Give your money a job";
            case POST_BUDGET_COMPLETION -> "Ready for your next plan?";
            case WALLET_READY_NO_BUDGET -> "Your wallet is ready";
        };
    }

    private String budgetNudgePushMessage(BudgetEngagementNudgeType type,
                                          BigDecimal walletBalance,
                                          String lastBudgetName) {
        return switch (type) {
            case FUNDED_WALLET_NO_BUDGET -> {
                String balance = walletBalance != null && walletBalance.compareTo(BigDecimal.ZERO) > 0
                        ? "Your NGN " + formatAmount(walletBalance) + " balance"
                        : "Your Wisemonie balance";
                yield balance + " is ready for structure. Create a simple plan and give the money a clear job.";
            }
            case POST_BUDGET_COMPLETION -> {
                String budgetName = lastBudgetName != null && !lastBudgetName.isBlank()
                        ? "'" + lastBudgetName + "'"
                        : "your last plan";
                yield budgetName + " has ended. Start the next money cycle with a fresh Wisemonie plan.";
            }
            case WALLET_READY_NO_BUDGET ->
                    "Your Wisemonie wallet is ready. Fund it and create a simple plan so every naira has a purpose.";
        };
    }

    private String budgetNudgeTag(BudgetEngagementNudgeType type) {
        return switch (type) {
            case FUNDED_WALLET_NO_BUDGET -> "Money waiting for structure";
            case POST_BUDGET_COMPLETION -> "Time for the next plan";
            case WALLET_READY_NO_BUDGET -> "Wallet ready";
        };
    }

    private String budgetNudgeHeadline(BudgetEngagementNudgeType type) {
        return switch (type) {
            case FUNDED_WALLET_NO_BUDGET -> "Give your money a job before it disappears";
            case POST_BUDGET_COMPLETION -> "Your next money cycle can feel lighter";
            case WALLET_READY_NO_BUDGET -> "Start with a small plan, not pressure";
        };
    }

    private String budgetNudgeSubheadline(BudgetEngagementNudgeType type) {
        return switch (type) {
            case FUNDED_WALLET_NO_BUDGET -> "Your money is already inside Wisemonie. Now give it direction.";
            case POST_BUDGET_COMPLETION -> "You have planned with Wisemonie before. This is the easy restart.";
            case WALLET_READY_NO_BUDGET -> "A wallet is useful. A wallet with a plan is calmer.";
        };
    }

    private List<String> budgetNudgeParagraphs(BudgetEngagementNudgeType type, String lastBudgetName) {
        return switch (type) {
            case FUNDED_WALLET_NO_BUDGET -> List.of(
                    "We know making more money is already challenging enough. Being intentional about how that money is spent should not become another challenge.",
                    "You already have money in your Wisemonie wallet. That is a strong start, but money without a plan can disappear through small unplanned decisions before you even notice.",
                    "Create a simple plan, split the balance into envelopes, apply sending rules, and let Wisemonie quietly hold the structure for you. The goal is simple: less pressure, fewer surprises, and more confidence before you spend."
            );
            case POST_BUDGET_COMPLETION -> {
                String budgetName = lastBudgetName != null && !lastBudgetName.isBlank()
                        ? "'" + lastBudgetName + "'"
                        : "your last";
                yield List.of(
                        "You have done this before. " + budgetName + " plan has ended, and the next money cycle deserves structure too.",
                        "We know making more money is already challenging enough. Being intentional about how that money is spent should not become another challenge.",
                        "A fresh Wisemonie plan helps you decide what is for spending, what should be protected, and what can go into savings. Then you can spend directly from each envelope and know what is safe to spend."
                );
            }
            case WALLET_READY_NO_BUDGET -> List.of(
                    "We know making more money is already challenging enough. Being intentional about how that money is spent should not become another challenge.",
                    "Your Wisemonie wallet is ready. Fund it, create a simple plan, and split the money into envelopes for the parts of life that usually pull on your balance: bills, food, transport, giving, enjoyment, and savings.",
                    "Once every envelope has a purpose, you do not have to keep calculating in your head. Wisemonie helps you see what is safe to spend, reduces financial pressure, and still leaves room to save."
            );
        };
    }

    private List<Map<String, String>> budgetNudgeAdviceItems(BudgetEngagementNudgeType type) {
        if (type == BudgetEngagementNudgeType.WALLET_READY_NO_BUDGET) {
            return List.of(
                    Map.of("title", "Fund your Wisemonie wallet", "description", "Start with any amount you can plan around, then let the money land where it belongs."),
                    Map.of("title", "Split it into envelopes", "description", "Give rent, food, transport, bills, savings, and enjoyment their own space."),
                    Map.of("title", "Spend from the right envelope", "description", "Each payment comes from the purpose you already chose, so safe-to-spend becomes clear.")
            );
        }

        return List.of(
                Map.of("title", "Turn balance into a plan", "description", "Move money from one big balance into clear envelopes with real intentions."),
                Map.of("title", "Apply sending rules", "description", "Let Wisemonie help you slow down impulse spending and protect money meant for later."),
                Map.of("title", "Save on Wisemonie too", "description", "Keep money for goals separate from everyday spending so progress is easier to protect.")
        );
    }

    private String budgetNudgeFooterNote(BudgetEngagementNudgeType type) {
        return switch (type) {
            case FUNDED_WALLET_NO_BUDGET -> "This is not pressure. It is a simple way to protect the money already sitting in your wallet.";
            case POST_BUDGET_COMPLETION -> "A completed plan is proof you can do this. The next one can be even easier.";
            case WALLET_READY_NO_BUDGET -> "Start small if you need to. The calm comes from giving your money a direction.";
        };
    }

    private void addAppDownloadContext(Context context) {
        String iosAppStoreUrl = deepLinkService.iosAppStoreUrl();
        context.setVariable("androidPlayStoreUrl", ANDROID_PLAY_STORE_URL);
        context.setVariable("iosAppStoreUrl", iosAppStoreUrl);
        context.setVariable("hasIosAppStore", iosAppStoreUrl != null && !iosAppStoreUrl.isBlank());
    }

    private OnboardingReminderCopy onboardingReminderCopy(int reminderNumber, boolean urgent) {
        if (urgent) {
            return new OnboardingReminderCopy(
                    "Your Wisemonie setup is almost out of time",
                    "Your Wisemonie account is still waiting, but incomplete profiles are cleared after a while for security and data hygiene. If you still want a calmer way to plan money, this is a good moment to finish the setup.",
                    "It only takes a few minutes to complete your profile, unlock your wallet, and keep the account active before it is removed.",
                    "Finish now so we can keep your account active.",
                    "\u23F3",
                    "Keep your account",
                    "Complete the profile before the cleanup window closes.",
                    "\uD83D\uDEE1\uFE0F",
                    "Protect your setup",
                    "We clear abandoned accounts so your details do not sit around unfinished.",
                    "\uD83E\uDDED",
                    "Start with direction",
                    "Once setup is complete, Wisemonie can help you fund, plan and spend with more clarity.",
                    "We would rather help you finish than lose the progress you already started."
            );
        }

        List<OnboardingReminderCopy> copies = List.of(
                new OnboardingReminderCopy(
                        "You are one step away from making Wisemonie useful",
                        "You already took the first step by creating your Wisemonie account. The next step is simply completing your profile so your wallet can be unlocked and your money can have a clearer structure.",
                        "A lot of people download money apps because they want control, then pause because the setup feels like one more task. We kept this simple: finish the profile, unlock the wallet, then start with one small plan.",
                        "Most of it is just confirming who you are.",
                        "\uD83E\uDDED",
                        "Find the first step",
                        "Complete your profile so the app can move from sign-up to actual money planning.",
                        "\uD83D\uDCB3",
                        "Unlock your wallet",
                        "Your Wisemonie wallet needs the final profile step before it can fully work for you.",
                        "\uD83C\uDF31",
                        "Start small",
                        "You do not need a perfect plan. One transport, food or savings instruction is enough to begin.",
                        "No pressure. Just one small step that makes the account useful."
                ),
                new OnboardingReminderCopy(
                        "Less mental maths starts with finishing setup",
                        "Money pressure often starts when everything is left in your head: bills, transport, food, family requests, savings and small spends all competing at once.",
                        "Wisemonie was built to reduce that mental load. Complete your profile so you can fund your wallet, split money into clear envelopes and know what is safe to spend before pressure arrives.",
                        "Finish setup and give your money a place to breathe.",
                        "\uD83E\uDDE0",
                        "Reduce the guessing",
                        "A complete account lets you turn one confusing balance into clearer spending lanes.",
                        "\uD83D\uDCE6",
                        "Use envelopes",
                        "Plan for food, transport, family, giving, enjoyment and savings before the month gets loud.",
                        "\uD83D\uDE0C",
                        "Spend with less pressure",
                        "When the money has a job, you do not have to calculate every decision from scratch.",
                        "Your future spending can feel softer than the old pattern."
                ),
                new OnboardingReminderCopy(
                        "Turn the account you opened into a money habit",
                        "Opening an account shows intention. Completing it turns that intention into something Wisemonie can actually help you practise.",
                        "The goal is not to become perfect with money overnight. The goal is to create one better habit: decide what the money is for before life starts pulling from it.",
                        "Complete your profile and make the account ready for that first habit.",
                        "\uD83C\uDFAF",
                        "Give money a purpose",
                        "Use Wisemonie to decide what each part of your money is meant to handle.",
                        "\uD83D\uDD01",
                        "Make discipline easier",
                        "Rules and envelopes help you follow the plan when impulse spending shows up.",
                        "\u2705",
                        "Get one quick win",
                        "Your first simple plan can be small and practical.",
                        "A small money habit today can save plenty stress later."
                ),
                new OnboardingReminderCopy(
                        "The old money pattern does not need another month",
                        "If you signed up because money has been feeling scattered, that reason still matters. It is easy to close the app and return to the same pattern, but the same pattern usually brings the same pressure.",
                        "Complete your setup so Wisemonie can help you plan ahead, protect important money and spend from the right envelope instead of doing mental maths every time.",
                        "Come back while the intention is still fresh.",
                        "\uD83D\uDD04",
                        "Break the loop",
                        "Do not let an unfinished setup send you back to old spending stress.",
                        "\uD83D\uDEE1\uFE0F",
                        "Protect important money",
                        "Create envelopes for the things that should not be accidentally touched.",
                        "\uD83D\uDC9A",
                        "Keep room for life",
                        "Plan essentials and enjoyment without guilt or confusion.",
                        "You started because something needed to feel different. Finish the step that makes different possible."
                ),
                new OnboardingReminderCopy(
                        "Your future self will like this small setup step",
                        "Future-you does not need a perfect financial plan today. Future-you just needs present-you to make the next right step a little easier.",
                        "Complete your profile now, then use Wisemonie to set up simple envelopes for the spending pressure you already know is coming: transport, food, bills, family, giving and savings.",
                        "It is a few minutes today for more clarity later.",
                        "\uD83D\uDD52",
                        "Save time later",
                        "A finished setup means you can plan quickly when money enters.",
                        "\uD83E\uDDFE",
                        "Name the real expenses",
                        "Put familiar spending categories into envelopes before they surprise you.",
                        "\uD83C\uDF24\uFE0F",
                        "Make the month feel lighter",
                        "Clarity does not remove every bill, but it makes decisions easier.",
                        "This is not pressure. It is a small favour for the version of you managing the next money cycle."
                ),
                new OnboardingReminderCopy(
                        "Your Wisemonie account can still become useful today",
                        "Your account is still here, but the real value begins after setup. Until then, Wisemonie cannot fully help you fund a wallet, create a plan or spend from the right envelope.",
                        "Finish the profile step, then start with one simple area: lunch at work, transport, family support, offering, savings or data. Small structure is still structure.",
                        "Complete setup and try one simple plan.",
                        "\uD83D\uDE80",
                        "Move from signup to action",
                        "The account becomes useful when setup is complete.",
                        "\uD83C\uDF71",
                        "Plan something familiar",
                        "Lunch, transport, data or savings is enough for a first plan.",
                        "\uD83E\uDDD8",
                        "Keep it simple",
                        "You do not need to plan the whole month before you start.",
                        "Start with one part of life that already asks you for money."
                )
        );

        int index = Math.floorMod(reminderNumber - 1, copies.size());
        return copies.get(index);
    }

    private record OnboardingReminderCopy(
            String subject,
            String introParagraph,
            String supportParagraph,
            String ctaSubtext,
            String firstPointIcon,
            String firstPointTitle,
            String firstPointBody,
            String secondPointIcon,
            String secondPointTitle,
            String secondPointBody,
            String thirdPointIcon,
            String thirdPointTitle,
            String thirdPointBody,
            String closingLine
    ) {}

    /** Sent once, about 7 days before a savings goal matures. */
    @Async
    public void sendSavingsMaturingSoonEmail(String email, String firstName, String goalName,
                                              BigDecimal principal, BigDecimal accruedInterest,
                                              BigDecimal projectedPayout, String maturityDateLabel,
                                              int daysRemaining) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Savings maturing-soon email for '{}' ({} day(s) left) to {}", goalName, daysRemaining, email);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("goalName", goalName);
            context.setVariable("principal", formatAmount(principal));
            context.setVariable("accruedInterest", formatAmount(accruedInterest));
            context.setVariable("projectedPayout", formatAmount(projectedPayout));
            context.setVariable("maturityDateLabel", maturityDateLabel);
            context.setVariable("daysRemaining", daysRemaining);
            // Zero interest → the template hides the interest row and shows the
            // "savings do not earn interest yet" disclaimer instead.
            context.setVariable("hasInterest",
                    accruedInterest != null && accruedInterest.compareTo(BigDecimal.ZERO) > 0);

            String htmlContent = templateEngine.process("savings-maturing-soon", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(email);
            helper.setSubject(daysRemaining == 1
                    ? goalName + " is tomorrow"
                    : goalName + " is " + daysRemaining + " days away");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent savings-maturing-soon email for '{}' ({} day(s) left) to {}",
                    goalName, daysRemaining, email);
        } catch (MessagingException e) {
            logger.error("Failed to send savings-maturing-soon email to {}", email, e);
        }
    }

    /**
     * Sent once, the day a savings goal's status flips to MATURED (see
     * SavingsLifeCycleManager). Distinct from sendSavingsMaturingSoonEmail()
     * above, which fires a week earlier as a heads-up, not on maturity itself.
     */
    @Async
    public void sendSavingsMaturedEmail(String email, String firstName, String goalName,
                                         BigDecimal principal, BigDecimal accruedInterest,
                                         BigDecimal totalPayout) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Savings matured email for '{}' to {}", goalName, email);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("firstName", firstName != null && !firstName.isBlank() ? firstName : "there");
            context.setVariable("goalName", goalName);
            context.setVariable("principal", formatAmount(principal));
            context.setVariable("accruedInterest", formatAmount(accruedInterest));
            context.setVariable("totalPayout", formatAmount(totalPayout));
            // Zero interest → hide the interest row, show the no-interest disclaimer.
            context.setVariable("hasInterest",
                    accruedInterest != null && accruedInterest.compareTo(BigDecimal.ZERO) > 0);

            String htmlContent = templateEngine.process("savings-matured", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(email);
            helper.setSubject(goalName + " is ready");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent savings-matured email for '{}' to {}", goalName, email);
        } catch (MessagingException e) {
            logger.error("Failed to send savings-matured email to {}", email, e);
        }
    }

    private String buildOnboardingReminderSubject(int daysSinceSignup, boolean urgent) {
        if (urgent) {
            return "Your Wisemonie account is waiting. Do not lose your spot";
        }
        if (daysSinceSignup <= 1) {
            return "👋 You're one step away from a calmer relationship with money";
        }
        return "Your Wisemonie account can still become useful today";
    }

    @Async
    public void sendOtpEmail(String email, String otpCode) {
        if ("stub".equals(activeProfile) || mailSender == null) return;
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("otpCode", otpCode);

            String htmlContent = templateEngine.process("otp-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(email);
            helper.setSubject("📩 Wisemonie Verification Code: " + otpCode);
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent OTP email to {}", email);
        } catch (Exception e) {
            logger.error("Failed to send OTP email to {}", email, e);
        }
    }
    @Async
    public void sendPasswordResetOtp(String to, String userName, String otpCode) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending Password Reset OTP {} to {}", otpCode, to);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("userName", userName);
            context.setVariable("otpCode", otpCode);

            String htmlContent = templateEngine.process("otp-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(to);
            helper.setSubject("🔓 Password Reset Code: " + otpCode);
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("🔓 Sent Password Reset OTP to {}", to);
        } catch (Exception e) {
            logger.error("⚠️ Failed to send Reset OTP to {}", to, e);
        }
    }
    /**
     * Sent immediately after a successful transaction PIN change.
     * Passive security alert — no action required if the user made the change.
     * Fired async so the API response is not delayed.
     */
    @Async
    public void sendTransactionPinChangedAlert(String to, String userName, String changedAt) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending Transaction PIN changed alert to {}", to);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("userName", userName);
            context.setVariable("email", to);
            context.setVariable("changedAt", changedAt);

            String htmlContent = templateEngine.process("pin-changed-alert", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(to);
            helper.setSubject("🔐 Your Wisemonie transaction PIN was changed");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent transaction PIN changed alert to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send transaction PIN changed alert to {}", to, e);
        }
    }

    /**
     * New-device sign-in alert — fired right after a successful login OTP
     * verification, which is the only door a brand-new device can enter
     * through. Passive: no action needed if the user recognises the sign-in.
     */
    @Async
    public void sendLoginAlertEmail(String to, String userName, String deviceName,
                                    String ipAddress, String loginTime, String loginDate) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending login alert to {}", to);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("userName", userName);
            context.setVariable("email", to);
            context.setVariable("deviceName", deviceName);
            context.setVariable("ipAddress", ipAddress);
            context.setVariable("loginTime", loginTime);
            context.setVariable("loginDate", loginDate);

            String htmlContent = templateEngine.process("login-alert", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(to);
            helper.setSubject("🔔 New sign-in to your Wisemonie account");
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent login alert to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send login alert to {}", to, e);
        }
    }

    /**
     * Farewell + feedback ask, sent once an account has actually closed (the
     * DELETED outcome — never on the interim withdrawal-required step). Echoes
     * the closure reason the user picked and invites a reply, Cardtonic-style.
     */
    @Async
    public void sendAccountClosedEmail(String to, String userName, String reason) {
        sendAccountClosedEmail(to, userName, reason, AccountClosureEmailScenario.GENERAL);
    }

    @Async
    public void sendAccountClosedEmail(String to,
                                       String userName,
                                       String reason,
                                       AccountClosureEmailScenario scenario) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending account closed email {} to {}", scenario, to);
            return;
        }
        AccountClosureEmailScenario safeScenario = scenario != null
                ? scenario
                : AccountClosureEmailScenario.GENERAL;
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("userName", userName != null && !userName.isBlank() ? userName : "there");
            context.setVariable("reason", reason != null ? reason.trim() : "");
            context.setVariable("scenarioTitle", accountClosureTitle(safeScenario));
            context.setVariable("scenarioBody", accountClosureBody(safeScenario));
            context.setVariable("feedbackPrompt", accountClosureFeedbackPrompt(safeScenario));
            context.setVariable("reasonOptions", accountClosureReasonOptions(safeScenario));
            context.setVariable("closingNote", accountClosureClosingNote(safeScenario));

            String htmlContent = templateEngine.process("account-closed", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(to);
            helper.setSubject(accountClosureSubject(safeScenario));
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent account closed email {} to {}", safeScenario, to);
        } catch (Exception e) {
            logger.error("Failed to send account closed email to {}", to, e);
        }
    }

    private String accountClosureSubject(AccountClosureEmailScenario scenario) {
        return switch (scenario) {
            case NO_WALLET -> "Was the wallet step unclear?";
            case WALLET_NOT_FUNDED -> "Did funding your Wisemonie wallet get confusing?";
            case FUNDED_NO_PLAN -> "What stopped Wisemonie from becoming useful?";
            case USED_WISEMONIE -> "What made you leave Wisemonie?";
            case GENERAL -> "Before you go, can you tell us what got in the way?";
        };
    }

    private String accountClosureTitle(AccountClosureEmailScenario scenario) {
        return switch (scenario) {
            case NO_WALLET -> "It looks like setup stopped at the wallet step";
            case WALLET_NOT_FUNDED -> "It looks like funding may have been the blocker";
            case FUNDED_NO_PLAN -> "You funded Wisemonie, but the next step did not stick";
            case USED_WISEMONIE -> "You gave Wisemonie a real try";
            case GENERAL -> "Thank you for trying Wisemonie";
        };
    }

    private String accountClosureBody(AccountClosureEmailScenario scenario) {
        return switch (scenario) {
            case NO_WALLET ->
                    "If BVN or wallet creation felt unclear, that is useful for us to know. BVN may be requested so our licensed banking partner can verify you, open your wallet account and provide your account number for funding.";
            case WALLET_NOT_FUNDED ->
                    "Many users get stuck because they search for Wisemonie in their bank app. The bank name is Rubies MFB, Rubies Microfinance Bank or Rubies. If this was the confusing part, please tell us the bank app you used.";
            case FUNDED_NO_PLAN ->
                    "Funding the wallet means the intent was there. If Wisemonie did not quickly show you how to give that money instructions, we need to understand where the experience fell short.";
            case USED_WISEMONIE ->
                    "Because you actually used Wisemonie, your feedback carries extra weight. We want to understand whether the issue was trust, charges, delays, product fit, too much friction or something we did not see.";
            case GENERAL ->
                    "We are not going to send a long sales pitch. We only want to understand what got in the way so the product can become more useful for real people with real money pressure.";
        };
    }

    private String accountClosureFeedbackPrompt(AccountClosureEmailScenario scenario) {
        return switch (scenario) {
            case NO_WALLET ->
                    "Reply with one line if you can: did you stop because of BVN, trust, a failed step, too much information, or something else?";
            case WALLET_NOT_FUNDED ->
                    "Reply with one line if you can: did your bank app fail to show Rubies, did the account number not resolve, or did the funding step feel risky?";
            case FUNDED_NO_PLAN ->
                    "Reply with one line if you can: did you not understand the next step, did envelopes feel like too much, or did you not see a reason to continue?";
            case USED_WISEMONIE ->
                    "Reply with one line if you can: what was the moment that made you decide Wisemonie was not worth keeping?";
            case GENERAL ->
                    "Reply with one line if you can. A human reads it, and it helps us fix the part that made people leave.";
        };
    }

    private List<String> accountClosureReasonOptions(AccountClosureEmailScenario scenario) {
        return switch (scenario) {
            case NO_WALLET -> List.of(
                    "BVN request did not feel clear",
                    "Wallet creation failed or took too long",
                    "I did not trust the setup yet",
                    "I got interrupted and did not see a reason to return"
            );
            case WALLET_NOT_FUNDED -> List.of(
                    "I could not find Rubies MFB in my bank app",
                    "The account number did not resolve",
                    "I was not sure the transfer would be safe",
                    "I wanted to fund later and forgot"
            );
            case FUNDED_NO_PLAN -> List.of(
                    "I did not know what to do after funding",
                    "Creating a plan felt like too much work",
                    "I wanted more guidance before locking money into envelopes",
                    "I funded the wallet, but the benefit was not obvious yet"
            );
            case USED_WISEMONIE -> List.of(
                    "The app had too much friction",
                    "A fee or transfer delay reduced my trust",
                    "I needed a feature Wisemonie does not have yet",
                    "The product did not match how I manage money"
            );
            case GENERAL -> List.of(
                    "Something felt unclear",
                    "I did not trust it enough yet",
                    "I did not see the value quickly",
                    "I had a bad app or payment experience"
            );
        };
    }

    private String accountClosureClosingNote(AccountClosureEmailScenario scenario) {
        return switch (scenario) {
            case NO_WALLET ->
                    "If you ever come back, we should make the wallet step feel clearer than it did the first time.";
            case WALLET_NOT_FUNDED ->
                    "If you ever come back, search Rubies MFB or Rubies Microfinance Bank when funding your wallet.";
            case FUNDED_NO_PLAN ->
                    "If you ever come back, start with one practical instruction for your money. Food, transport, bills or savings is enough.";
            case USED_WISEMONIE ->
                    "If you ever come back, your account can be reactivated by signing in with your registered email.";
            case GENERAL ->
                    "If you ever come back, your account can be reactivated by signing in with your registered email.";
        };
    }

    @Async
    public void sendTransactionPinResetOtp(String to, String userName, String otpCode) {
        if ("stub".equals(activeProfile) || mailSender == null) {
            logger.info("[STUB] Sending Transaction PIN Reset OTP {} to {}", otpCode, to);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("logoUrl", logoUrl());
            context.setVariable("userName", userName);
            context.setVariable("otpCode", otpCode);

            String htmlContent = templateEngine.process("otp-email", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);

            setWisemonieSender(helper);
            helper.setTo(to);
            helper.setSubject("Transaction PIN Reset Code: " + otpCode);
            helper.setText(htmlContent, true);
            attachLogo(helper);

            mailSender.send(mimeMessage);
            logger.info("Sent Transaction PIN Reset OTP to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send Transaction PIN Reset OTP to {}", to, e);
        }
    }

    /**
     * Delivers a single outbox event, idempotently, keeping FCM <b>outside</b> any DB
     * transaction:
     * <ol>
     *   <li>{@link #prepareDelivery} (short txn) applies the terminal/stale guards and
     *       writes the in-app inbox row atomically with the {@code inboxSaved} flag,
     *       then returns the push plan (or {@code null} when there's nothing to push);</li>
     *   <li>the FCM push loop runs here holding no transaction / row lock, so slow or
     *       failing FCM calls never tie up a DB connection;</li>
     *   <li>{@link #finalizeDelivery} (short txn) records which tokens were delivered and
     *       marks the event PROCESSED, or schedules a retry.</li>
     * </ol>
     * Safe to retry: the inbox row is written at most once, each device token is pushed
     * at most once, and failures never rethrow.
     */
    public void deliverOutboxEvent(Long eventId) {
        DeliveryPlan plan;
        try {
            plan = self.prepareDelivery(eventId);
        } catch (Exception e) {
            if (ExceptionClassifier.isDatabasePoolExhausted(e)) {
                logger.warn("[OUTBOX] DB pool busy while preparing event {}; will retry later: {}",
                        eventId, ExceptionClassifier.rootCauseMessage(e));
                return;
            }
            logger.error("[OUTBOX] Failed to prepare event {}", eventId, e);
            return;
        }
        if (plan == null) {
            return; // terminal, stale, or nothing to push — already finalized in-txn
        }

        // --- FCM push: NO DB transaction / row lock is held across these network calls ---
        Set<String> delivered = new java.util.LinkedHashSet<>(plan.alreadyDelivered);
        String transientError = null;
        for (AuthSessionService.PushTarget target : plan.tokensToPush) {
            try {
                String actionType = plan.actionTypeFor(target.devicePlatform());
                String redirectUrl = plan.redirectUrlFor(target.devicePlatform());
                sendFCMMessage(target.token(), plan.title, plan.message, actionType, redirectUrl,
                        plan.type, plan.userId, plan.budgetId, plan.envelopeId,
                        target.devicePlatform(), plan.eventId);
                delivered.add(target.token());
            } catch (DeadTokenException ignored) {
                // Token was permanently invalid — already cleaned up, don't count as delivered
            } catch (RuntimeException fcmError) {
                transientError = fcmError.getMessage(); // stop; persist progress + retry below
                break;
            }
        }

        try {
            self.finalizeDelivery(eventId, plan.notificationId, delivered, transientError);
        } catch (Exception e) {
            if (ExceptionClassifier.isDatabasePoolExhausted(e)) {
                logger.warn("[OUTBOX] DB pool busy while finalizing event {}; will retry later: {}",
                        eventId, ExceptionClassifier.rootCauseMessage(e));
                return;
            }
            logger.error("[OUTBOX] Failed to finalize event {}", eventId, e);
        }
    }

    /**
     * Short transaction: terminal/stale guards, atomic inbox write (+ inboxSaved), and
     * computes the push plan. Returns {@code null} when nothing needs pushing (in which
     * case the event is finalized here).
     */
    @Transactional
    public DeliveryPlan prepareDelivery(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null) {
            return null;
        }
        String currentStatus = event.getStatus();
        if ("PROCESSED".equals(currentStatus) || "STALE".equals(currentStatus)
                || "FAILED".equals(currentStatus)) {
            return null; // already resolved by another pass
        }

        NotificationType type = NotificationType.valueOf(event.getEventType());
        Map<String, Object> params = event.getPayload() != null
                ? new java.util.HashMap<>(event.getPayload())
                : new java.util.HashMap<>();
        Long notificationId = paramLong(params, "__notificationId");

        // Deleted-user guard — never deliver notifications to a closed account.
        if (type != NotificationType.ADMIN_RECONCILIATION_ALERT) {
            boolean userDeleted = userRepository.findById(event.getUserId())
                    .map(u -> u.isDeleted())
                    .orElse(true);
            if (userDeleted) {
                event.setStatus("STALE");
                event.setProcessedAt(LocalDateTime.now());
                event.setNextAttemptAt(null);
                event.setLockedAt(null);
                event.setLockedBy(null);
                event.setLastError("Skipped: user deleted (type=" + event.getEventType() + ")");
                outboxEventRepository.save(event);
                logger.info("[OUTBOX] Skipping event {} for deleted user {}", event.getId(), event.getUserId());
                return null;
            }
        }

        // Staleness guard — don't deliver an alert whose relevance window has passed.
        Long ttlSeconds = event.getTtlSeconds();
        if (ttlSeconds != null && ttlSeconds > 0
                && LocalDateTime.now().isAfter(event.getCreatedAt().plusSeconds(ttlSeconds))) {
            event.setStatus("STALE");
            event.setProcessedAt(LocalDateTime.now());
            event.setNextAttemptAt(null);
            event.setLockedAt(null);
            event.setLockedBy(null);
            event.setLastError("Skipped: expired (ttl=" + ttlSeconds + "s, type="
                    + event.getEventType() + ")");
            outboxEventRepository.save(event);
            logger.info("[OUTBOX] Skipping stale event {} type={}", event.getId(), event.getEventType());
            return null;
        }

        if (type == NotificationType.ADMIN_RECONCILIATION_ALERT) {
            deliverAdminReconciliationAlert(event, params);
            return null;
        }

        String message = resolveMessage(type, params);
        String title = resolveTitle(type, params);
        String redirectUrl = resolveRedirectUrl(event, params);
        String actionType = paramString(params, "__actionType");
        String androidExternalUrl = paramString(params, "__androidUrl");
        String iosExternalUrl = paramString(params, "__iosUrl");
        NotificationPriority priority = getPriority(type);
        boolean pushEligible = priority == NotificationPriority.HIGH
                || priority == NotificationPriority.MEDIUM;

        List<AuthSessionService.PushTarget> pushTargets = pushEligible
                ? getPushTargetsForUser(event.getUserId())
                : List.of();

        // In-app inbox — written once, atomically with the inboxSaved flag.
        if (!event.isInboxSaved() && shouldPersistToDatabase(type)) {
            Notification notification = new Notification();
            notification.setUserId(event.getUserId());
            notification.setMessage(message);
            notification.setTitle(title);
            notification.setType(type);
            notification.setCreatedAt(LocalDateTime.now());
            notification.setBudgetId(event.getBudgetId());
            notification.setEnvelopeId(event.getEnvelopeId());
            notification.setActionType(actionType);
            notification.setRedirectUrl(redirectUrl);
            notification.setRead(false);
            // Keep push-eligible inbox rows pending until Firebase accepts a token.
            notification.setPushSent(!pushEligible);
            notification = notificationRepository.save(notification);
            notificationId = notification.getId();
            if (notificationId != null) {
                params.put("__notificationId", notificationId);
                event.setPayload(params);
            }
            event.setInboxSaved(true);
        }

        // Which tokens still need a push (skip any a prior attempt already delivered).
        Set<String> alreadyDelivered = parseTokenSet(event.getDeliveredTokens());
        List<AuthSessionService.PushTarget> tokensToPush = new ArrayList<>();
        if (pushEligible && !"stub".equals(activeProfile) && firebaseMessaging != null) {
            for (AuthSessionService.PushTarget target : pushTargets) {
                if (!alreadyDelivered.contains(target.token())) {
                    tokensToPush.add(target);
                }
            }
        }

        if (tokensToPush.isEmpty()) {
            // Diagnose WHY no push is being sent — critical for HIGH-priority events.
            if (!pushEligible) {
                logger.info("[OUTBOX] Delivered event {} type={} (no push: priority=LOW)",
                        event.getId(), event.getEventType());
            } else if ("stub".equals(activeProfile)) {
                logger.warn("[OUTBOX] Delivered event {} type={} (no push: stub profile)",
                        event.getId(), event.getEventType());
            } else if (firebaseMessaging == null) {
                logger.error("[OUTBOX] Delivered event {} type={} (no push: Firebase NOT initialized)",
                        event.getId(), event.getEventType());
            } else if (pushTargets.isEmpty()) {
                logger.warn("[OUTBOX] Delivered event {} type={} (no push: no FCM token for user {}) — will redeliver on next token registration",
                        event.getId(), event.getEventType(), event.getUserId());
            } else {
                logger.info("[OUTBOX] Delivered event {} type={} (no push: all {} token(s) already delivered)",
                        event.getId(), event.getEventType(), alreadyDelivered.size());
            }
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            event.setNextAttemptAt(null);
            event.setLockedAt(null);
            event.setLockedBy(null);
            event.setLastError(null);
            outboxEventRepository.save(event);
            return null;
        }

        // Persist the inboxSaved flag now; the actual FCM happens outside this transaction.
        outboxEventRepository.save(event);

        DeliveryPlan plan = new DeliveryPlan();
        plan.message = message;
        plan.title = title;
        plan.redirectUrl = redirectUrl;
        plan.actionType = actionType;
        plan.androidExternalUrl = androidExternalUrl;
        plan.iosExternalUrl = iosExternalUrl;
        plan.type = type;
        plan.userId = event.getUserId();
        plan.eventId = event.getId();
        plan.notificationId = notificationId;
        plan.budgetId = event.getBudgetId();
        plan.envelopeId = event.getEnvelopeId();
        plan.tokensToPush = tokensToPush;
        plan.alreadyDelivered = alreadyDelivered;
        return plan;
    }

    /**
     * Short transaction: records the tokens actually delivered and marks the event
     * PROCESSED, or schedules a retry when a transient FCM error interrupted the push.
     */
    @Transactional
    public void finalizeDelivery(Long eventId, Long notificationId, Set<String> deliveredTokens, String transientError) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null) {
            return;
        }
        event.setDeliveredTokens(joinTokens(deliveredTokens));
        if (notificationId != null && deliveredTokens != null && !deliveredTokens.isEmpty()) {
            notificationRepository.markPushSent(notificationId);
        }
        if (transientError != null) {
            scheduleRetry(event, transientError);
        } else {
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            event.setNextAttemptAt(null);
            event.setLockedAt(null);
            event.setLockedBy(null);
            event.setLastError(null);
            logger.info("[OUTBOX] Delivered event {} type={}", event.getId(), event.getEventType());
        }
        outboxEventRepository.save(event);
    }

    /** Push plan produced by {@link #prepareDelivery}, consumed by the FCM loop. */
    private static final class DeliveryPlan {
        String message;
        String title;
        String redirectUrl;
        String actionType;
        String androidExternalUrl;
        String iosExternalUrl;
        NotificationType type;
        Long userId;
        Long eventId;
        Long notificationId;
        Long budgetId;
        Long envelopeId;
        List<AuthSessionService.PushTarget> tokensToPush;
        Set<String> alreadyDelivered;

        String actionTypeFor(String devicePlatform) {
            if (!ACTION_OPEN_EXTERNAL_URL.equals(actionType)) {
                return actionType;
            }
            String resolvedUrl = redirectUrlFor(devicePlatform);
            if (resolvedUrl == null && isIos(devicePlatform)) {
                return ACTION_IOS_APP_COMING_SOON;
            }
            return actionType;
        }

        String redirectUrlFor(String devicePlatform) {
            if (!ACTION_OPEN_EXTERNAL_URL.equals(actionType)) {
                return redirectUrl;
            }
            if (isIos(devicePlatform)) {
                return blankToNull(iosExternalUrl);
            }
            String androidUrl = blankToNull(androidExternalUrl);
            return androidUrl != null ? androidUrl : blankToNull(redirectUrl);
        }

        private static boolean isIos(String devicePlatform) {
            return "IOS".equalsIgnoreCase(devicePlatform);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    private void deliverAdminReconciliationAlert(OutboxEvent event, Map<String, Object> params) {
        String to = paramString(params, "adminEmail");
        if (to == null) {
            to = reconciliationAdminEmail;
        }
        if (to == null || to.isBlank()) {
            event.setStatus("STALE");
            event.setProcessedAt(LocalDateTime.now());
            event.setNextAttemptAt(null);
            event.setLockedAt(null);
            event.setLockedBy(null);
            event.setLastError("moniewise.reconciliation.admin-alert-email is not configured");
            outboxEventRepository.save(event);
            logger.warn("[OUTBOX] Reconciliation alert {} skipped: admin email is not configured", event.getId());
            return;
        }

        if ("stub".equals(activeProfile) || mailSender == null) {
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            event.setNextAttemptAt(null);
            event.setLockedAt(null);
            event.setLockedBy(null);
            event.setLastError(null);
            outboxEventRepository.save(event);
            logger.info("[STUB] Reconciliation admin alert {} would be emailed to {}", event.getId(), to);
            return;
        }

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
            setWisemonieSender(helper);
            helper.setTo(to);
            helper.setSubject("[Wisemonie Ops] Reconciliation review needed - run "
                    + safeText(params.get("runId"), "unknown"));
            helper.setText(buildReconciliationAlertHtml(params), true);
            mailSender.send(mimeMessage);

            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            event.setNextAttemptAt(null);
            event.setLockedAt(null);
            event.setLockedBy(null);
            event.setLastError(null);
            outboxEventRepository.save(event);
            logger.info("[OUTBOX] Reconciliation admin alert {} emailed to {}", event.getId(), to);
        } catch (Exception e) {
            scheduleRetry(event, "Admin reconciliation email failed: " + e.getMessage());
            outboxEventRepository.save(event);
        }
    }

    private String buildReconciliationAlertHtml(Map<String, Object> params) {
        return """
                <html>
                  <body style="font-family: Arial, sans-serif; color: #10201b; line-height: 1.5;">
                    <h2>Wisemonie reconciliation needs review</h2>
                    <p>The latest BaaS reconciliation found items that need ops attention.</p>
                    <table cellpadding="6" cellspacing="0" style="border-collapse: collapse;">
                """
                + adminAlertRow("Run ID", params.get("runId"))
                + adminAlertRow("Provider", params.get("providerName"))
                + adminAlertRow("Open items", params.get("openCount"))
                + adminAlertRow("Manual review items", params.get("manualReviewCount"))
                + adminAlertRow("Affected users", params.get("affectedUsers"))
                + adminAlertRow("Total gap amount", params.get("totalGapAmount"))
                + """
                    </table>
                    <h3>Summary payload</h3>
                    <pre style="white-space: pre-wrap; background: #f5f7f6; padding: 12px;">"""
                + escapeHtml(safeText(params.get("summaryJson"), "{}"))
                + """
                    </pre>
                  </body>
                </html>
                """;
    }

    private String adminAlertRow(String label, Object value) {
        return "<tr><td><strong>" + escapeHtml(label) + "</strong></td><td>"
                + escapeHtml(safeText(value, "-")) + "</td></tr>";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Bumps retry count and moves the event to PENDING (retry) or FAILED (exhausted). */
    private void scheduleRetry(OutboxEvent event, String error) {
        int retryCount = event.getRetryCount() + 1;
        event.setRetryCount(retryCount);
        event.setLastError(error);
        event.setLockedAt(null);
        event.setLockedBy(null);
        if (retryCount >= OUTBOX_MAX_ATTEMPTS) {
            event.setStatus("FAILED");
            event.setProcessedAt(LocalDateTime.now());
            event.setNextAttemptAt(null);
            logger.error("[OUTBOX] Event {} type={} FAILED permanently after {} attempts: {}",
                    event.getId(), event.getEventType(), retryCount, error);
        } else {
            long delayMinutes = retryDelayMinutes(retryCount);
            event.setStatus("PENDING");
            event.setProcessedAt(null);
            event.setNextAttemptAt(LocalDateTime.now().plusMinutes(delayMinutes));
            logger.warn("[OUTBOX] Event {} type={} attempt {} failed; will retry in {} minute(s): {}",
                    event.getId(), event.getEventType(), retryCount, delayMinutes, error);
        }
    }

    private long retryDelayMinutes(int retryCount) {
        int exponent = Math.min(Math.max(retryCount - 1, 0), 5);
        long delay = 1L << exponent;
        return Math.min(delay, OUTBOX_MAX_RETRY_DELAY_MINUTES);
    }

    /** Prefer the caller's pre-formatted message ("__message"); else generate one from params. */
    private String resolveMessage(NotificationType type, Map<String, Object> params) {
        String pre = paramString(params, "__message");
        return (pre != null) ? pre : generateMessage(type, params);
    }

    /** Prefer the caller's pre-formatted title ("__title"); else use the type title. */
    private String resolveTitle(NotificationType type, Map<String, Object> params) {
        String pre = paramString(params, "__title");
        return (pre != null) ? pre : getNotificationTitle(type);
    }

    /** Prefer an explicit "__redirectUrl" from the payload; else derive one from context ids. */
    private String resolveRedirectUrl(OutboxEvent event, Map<String, Object> params) {
        String pre = paramString(params, "__redirectUrl");
        return (pre != null) ? pre : buildRedirectUrl(event);
    }

    /** Null-safe read of a string payload value; returns null for a missing/blank entry. */
    private static String paramString(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object v = params.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private static Long paramLong(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object v = params.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v == null) {
            return null;
        }
        String s = v.toString();
        if (s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isExternalPushAction(String actionType) {
        return ACTION_OPEN_EXTERNAL_URL.equals(actionType)
                || ACTION_IOS_APP_COMING_SOON.equals(actionType);
    }

    private String buildRedirectUrl(OutboxEvent event) {
        if (event.getEnvelopeId() != null) {
            return "/envelopes/" + event.getEnvelopeId();
        }
        if (event.getBudgetId() != null) {
            return "/budgets/" + event.getBudgetId();
        }
        return "/notifications";
    }

    private static Set<String> parseTokenSet(String joined) {
        if (joined == null || joined.isBlank()) {
            return new java.util.LinkedHashSet<>();
        }
        return new java.util.LinkedHashSet<>(java.util.Arrays.asList(joined.split("\\|\\|")));
    }

    private static String joinTokens(Set<String> tokens) {
        return tokens.isEmpty() ? null : String.join("||", tokens);
    }

    private List<String> getPushTokensForUser(Long userId) {
        return getPushTargetsForUser(userId).stream()
                .map(AuthSessionService.PushTarget::token)
                .toList();
    }

    private List<AuthSessionService.PushTarget> getPushTargetsForUser(Long userId) {
        java.util.LinkedHashMap<String, AuthSessionService.PushTarget> targets = new java.util.LinkedHashMap<>();

        try {
            List<AuthSessionService.PushTarget> activeTargets = authSessionService.getActivePushTargets(userId);
            if (activeTargets != null) {
                for (AuthSessionService.PushTarget target : activeTargets) {
                    if (target != null && target.token() != null && !target.token().isBlank()) {
                        targets.putIfAbsent(target.token(), target);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch active FCM targets for user {}", userId, e);
        }

        return List.copyOf(targets.values());
    }

    /**
     * Sweeps recent notifications that were saved to the inbox but never pushed
     * (pushSent=false — no active FCM token at send time) and redelivers them.
     *
     * Called right after a fresh FCM token registers (see UserController's
     * POST /users/fcm-token), which is exactly the moment a device that was
     * unreachable becomes reachable again — e.g. a user who got auto-logged-out
     * by session timeout right as a scheduled disbursement fired, then logged
     * back in a few minutes later.
     *
     * Bounded to a 2-hour lookback so this never resurfaces stale notifications
     * (and, on first deploy, never floods a user with their entire history —
     * every pre-existing row defaults pushSent=false but falls outside the window).
     */
    @Transactional
    public void redeliverMissedPushes(Long userId) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime cutoff = now.minusHours(72);
            List<Notification> missed = notificationRepository
                    .findByUserIdAndPushSentFalseAndCreatedAtAfter(userId, cutoff);
            if (missed.isEmpty()) return;

            List<AuthSessionService.PushTarget> pushTargets = getPushTargetsForUser(userId);
            if (pushTargets.isEmpty()) return;

            int redelivered = 0;
            int stale = 0;
            int notPushEligible = 0;
            missed.sort(java.util.Comparator.comparing(
                    Notification::getCreatedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
            for (Notification n : missed) {
                NotificationPriority priority = getPriority(n.getType());
                if (priority != NotificationPriority.HIGH && priority != NotificationPriority.MEDIUM) {
                    n.setPushSent(true);
                    notPushEligible++;
                    continue;
                }
                if (isMissedPushStale(n, now)) {
                    n.setPushSent(true);
                    stale++;
                    continue;
                }
                if (redelivered >= 10) {
                    continue;
                }
                try {
                    String title = n.getTitle() != null && !n.getTitle().isBlank()
                            ? n.getTitle()
                            : getNotificationTitle(n.getType());
                    for (AuthSessionService.PushTarget target : pushTargets) {
                        sendFCMMessage(target.token(), title, n.getMessage(), n.getActionType(),
                                n.getRedirectUrl(), n.getType(), userId, n.getBudgetId(),
                                n.getEnvelopeId(), target.devicePlatform(), n.getId());
                    }
                    n.setPushSent(true);
                    redelivered++;
                } catch (Exception e) {
                    logger.warn("[FCM] Redelivery attempt failed for notification {} (user {})",
                            n.getId(), userId, e);
                }
            }
            notificationRepository.saveAll(missed);
            logger.info("[FCM] Redelivery sweep for user {}: {} sent, {} stale, {} not-push-eligible, {} candidate(s)",
                    userId, redelivered, stale, notPushEligible, missed.size());
        } catch (Exception e) {
            logger.warn("[FCM] Failed to redeliver missed pushes for user {}", userId, e);
        }
    }

    private boolean isMissedPushStale(Notification notification, LocalDateTime now) {
        if (notification == null || notification.getCreatedAt() == null) {
            return false;
        }
        long ttlMs = computeFcmTtlMs(notification.getType());
        return now.isAfter(notification.getCreatedAt().plusNanos(ttlMs * 1_000_000L));
    }
}
