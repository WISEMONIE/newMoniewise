package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.request.AdminBroadcastRequest;
import com.moniewise.moniewise_backend.entity.AdminBroadcast;
import com.moniewise.moniewise_backend.dto.response.AdminBroadcastResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

@Service
public class AdminBroadcastService {

    private static final Logger logger = LoggerFactory.getLogger(AdminBroadcastService.class);
    /** Up to this many people are sent to inside the request; more become a background job. */
    static final int INLINE_LIMIT = 50;
    private static final long MAX_TTL_SECONDS = 604_800L;

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AdminBroadcastEmailDispatcher emailDispatcher;

    public AdminBroadcastService(UserRepository userRepository,
                                 NotificationService notificationService,
                                 AdminBroadcastEmailDispatcher emailDispatcher) {
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.emailDispatcher = emailDispatcher;
    }

    public AdminBroadcastResponse sendServiceOutage(AdminBroadcastRequest request, String adminEmail) {
        return sendBroadcast(
                NotificationType.SERVICE_OUTAGE,
                "Service alert",
                "Service Alert",
                request,
                adminEmail,
                true
        );
    }

    public AdminBroadcastResponse sendMaintenance(AdminBroadcastRequest request, String adminEmail) {
        return sendBroadcast(
                NotificationType.SCHEDULED_MAINTENANCE,
                "Maintenance",
                "Scheduled Maintenance",
                request,
                adminEmail,
                false
        );
    }

    public AdminBroadcastResponse sendSpecial(AdminBroadcastRequest request, String adminEmail) {
        return sendBroadcast(
                NotificationType.SPECIAL_ANNOUNCEMENT,
                "Wisemonie update",
                "Wisemonie Update",
                request,
                adminEmail,
                true
        );
    }

    private AdminBroadcastResponse sendBroadcast(NotificationType type,
                                                 String defaultTag,
                                                 String defaultTitle,
                                                 AdminBroadcastRequest request,
                                                 String adminEmail,
                                                 boolean defaultDeliverNow) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }

        String title = firstNonBlank(request.getTitle(), defaultTitle);
        String message = cleanRequired(request.getMessage(), "message is required");
        String emailSubject = firstNonBlank(request.getEmailSubject(), title);
        String emailBody = firstNonBlank(request.getEmailBody(), message);
        String tag = firstNonBlank(request.getTag(), defaultTag);
        String footerNote = firstNonBlank(request.getFooterNote(), defaultFooterNote(type));
        String ctaLabel = firstNonBlank(request.getCtaLabel(), "Open Wisemonie");
        String ctaUrl = blankToNull(request.getCtaUrl());
        if (ctaUrl != null && !isAllowedLink(ctaUrl)) {
            throw new IllegalArgumentException("The button link must start with https://");
        }
        String pushActionType = isExternalUrl(ctaUrl) ? "OPEN_EXTERNAL_URL" : null;
        boolean sendFcm = request.getSendFcm() == null || request.getSendFcm();
        boolean sendEmail = request.getSendEmail() == null || request.getSendEmail();
        boolean includeTestAccounts = Boolean.TRUE.equals(request.getIncludeTestAccounts());
        boolean deliverNow = request.getDeliverFcmNow() != null
                ? request.getDeliverFcmNow()
                : defaultDeliverNow;
        long ttlSeconds = resolveTtlSeconds(request.getTtlSeconds(), type);
        String imageUrl = blankToNull(request.getImageUrl());
        if (imageUrl != null && !imageUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException("The image must be a public https link");
        }
        String stillImageUrl = imageUrl == null ? null : blankToNull(request.getStillImageUrl());
        if (stillImageUrl != null && !stillImageUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException("The still image must be a public https link");
        }

        if (!sendFcm && !sendEmail) {
            throw new IllegalArgumentException("At least one of sendFcm or sendEmail must be true");
        }

        boolean hasTargets = hasValues(request.getUserIds()) || hasValues(request.getEmails());
        boolean allUsers = request.getAllUsers() == null ? !hasTargets : request.getAllUsers();

        if (!allUsers && !hasTargets) {
            throw new IllegalArgumentException("Provide allUsers=true, userIds, or emails");
        }
        String requestKey = blankToNull(request.getRequestKey());
        if (requestKey != null) {
            Optional<AdminBroadcast> earlier = broadcastHistory.findByRequestKey(requestKey);
            if (earlier.isPresent()) {
                return alreadySent(earlier.get());
            }
        }
        AdminBroadcast row = newHistory(type, title, message, imageUrl, ctaLabel, ctaUrl, sendFcm,
                sendEmail, includeTestAccounts, allUsers, request, adminEmail);
        row.setRequestKey(requestKey);
        row.setStillImageUrl(stillImageUrl);

        if (allUsers) {
            return startJob(row, null, emailSubject, emailBody, tag, footerNote, ttlSeconds, List.of());
        }

        RecipientSelection selection = resolveRecipients(request, includeTestAccounts);
        List<User> recipients = selection.users();

        if (recipients.size() > INLINE_LIMIT) {
            List<Long> recipientIds = recipients.stream().map(User::getId).sorted().toList();
            return startJob(row, recipientIds, emailSubject, emailBody, tag, footerNote, ttlSeconds,
                    selection.skippedTargets());
        }

        AdminBroadcast history;
        try {
            history = broadcastHistory.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            return alreadySentAfterRace(requestKey, e);
        }

        List<AdminBroadcastEmailDispatcher.Recipient> emailRecipients = new ArrayList<>();
        int fcmQueued = 0;
        int fcmDeliveryAttempts = 0;

        for (User user : recipients) {
            if (sendEmail && !isBlank(user.getEmail())) {
                emailRecipients.add(new AdminBroadcastEmailDispatcher.Recipient(
                        user.getEmail(),
                        firstName(user)
                ));
            }

            if (!sendFcm) {
                continue;
            }

            try {
                Long eventId = notificationService.enqueueAdminBroadcastNotification(
                        user.getId(),
                        type,
                        title,
                        message,
                        pushActionType,
                        ctaUrl,
                        ttlSeconds,
                        imageUrl,
                        stillImageUrl,
                        history.getId()
                );
                fcmQueued++;
                if (deliverNow && eventId != null) {
                    notificationService.deliverOutboxEvent(eventId);
                    fcmDeliveryAttempts++;
                }
            } catch (Exception e) {
                logger.error("[ADMIN-BROADCAST] Failed to enqueue FCM type={} for userId={}",
                        type, user.getId(), e);
            }
        }

        if (sendEmail && !emailRecipients.isEmpty()) {
            emailDispatcher.sendBroadcastEmails(
                    emailRecipients,
                    emailSubject,
                    tag,
                    title,
                    emailBody,
                    footerNote,
                    ctaLabel,
                    ctaUrl,
                    type.name()
            );
        }

        logger.info("[ADMIN-BROADCAST] admin={} type={} recipients={} fcmQueued={} fcmDeliveryAttempts={} emailsQueued={}",
                adminEmail, type, recipients.size(), fcmQueued, fcmDeliveryAttempts, emailRecipients.size());
        emailDispatcher.recordFinish(history.getId(), recipients.size(), fcmQueued, emailRecipients.size());

        return new AdminBroadcastResponse(
                true,
                type.name(),
                recipients.size(),
                fcmQueued,
                fcmDeliveryAttempts,
                emailRecipients.size(),
                deliverNow,
                selection.skippedTargets(),
                buildResponseMessage(type, sendFcm, sendEmail, deliverNow),
                false
        );
    }

    /**
     * Saves the send as a job and starts it in the background. Its pushes are
     * only queued, so they go out behind personal alerts. If the server
     * restarts, {@link AdminBroadcastResumer} carries on from where it stopped.
     */
    private AdminBroadcastResponse startJob(AdminBroadcast row, List<Long> recipientIds,
                                            String emailSubject, String emailBody, String tag,
                                            String footerNote, long ttlSeconds, List<String> skippedTargets) {
        row.setBackground(true);
        row.setAllUsers(recipientIds == null);
        row.setRecipientIds(recipientIds == null ? null
                : recipientIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        row.setEmailSubject(emailSubject);
        row.setEmailBody(emailBody);
        row.setTag(tag);
        row.setFooterNote(footerNote);
        row.setTtlSeconds(ttlSeconds);
        row.setRunnerToken(UUID.randomUUID().toString());
        AdminBroadcast saved;
        try {
            saved = broadcastHistory.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            return alreadySentAfterRace(row.getRequestKey(), e);
        }
        emailDispatcher.runJobAsync(saved.getId(), saved.getRunnerToken());
        logger.info("[ADMIN-BROADCAST] admin={} type={} broadcast={} started in the background ({})",
                saved.getSentBy(), saved.getType(), saved.getId(), saved.getAudience());
        return new AdminBroadcastResponse(
                true,
                saved.getType(),
                recipientIds == null ? -1 : recipientIds.size(),
                0,
                0,
                0,
                false,
                skippedTargets,
                "Sending in the background. Progress shows in the history.",
                false
        );
    }

    private AdminBroadcastResponse alreadySent(AdminBroadcast earlier) {
        return new AdminBroadcastResponse(
                true,
                earlier.getType(),
                earlier.getRecipients(),
                earlier.getPushQueued(),
                0,
                earlier.getEmailsQueued(),
                false,
                List.of(),
                "Already sent. It was not sent again.",
                true
        );
    }

    /** Two identical requests arrived together; the other one won. */
    private AdminBroadcastResponse alreadySentAfterRace(String requestKey, DataIntegrityViolationException e) {
        if (requestKey == null) {
            throw e;
        }
        return broadcastHistory.findByRequestKey(requestKey).map(this::alreadySent).orElseThrow(() -> e);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.moniewise.moniewise_backend.repository.AdminBroadcastRepository broadcastHistory;

    /** Saved before sending, so even a send that fails part-way shows in the history. */
    private AdminBroadcast newHistory(NotificationType type, String title, String message, String imageUrl,
                                      String ctaLabel, String ctaUrl, boolean sendPush, boolean sendEmail,
                                      boolean includeTestAccounts, boolean allUsers,
                                      AdminBroadcastRequest request, String adminEmail) {
        AdminBroadcast row = new AdminBroadcast();
        row.setType(type.name());
        row.setTitle(title);
        row.setMessage(message);
        row.setImageUrl(imageUrl);
        row.setCtaLabel(ctaUrl == null ? null : ctaLabel);
        row.setCtaUrl(ctaUrl);
        row.setSendPush(sendPush);
        row.setSendEmail(sendEmail);
        row.setIncludeTestAccounts(includeTestAccounts);
        row.setAudience(describeAudience(allUsers, request, adminEmail));
        row.setStatus(AdminBroadcast.Status.SENDING);
        row.setSentBy(adminEmail == null ? "unknown-admin" : adminEmail);
        row.setCreatedAt(java.time.LocalDateTime.now());
        row.setUpdatedAt(row.getCreatedAt());
        return row;
    }

    private static String describeAudience(boolean allUsers, AdminBroadcastRequest request, String adminEmail) {
        if (allUsers) {
            return "All users";
        }
        List<String> emails = request.getEmails() == null ? List.of() : request.getEmails();
        int ids = request.getUserIds() == null ? 0 : request.getUserIds().size();
        if (ids == 0 && emails.size() == 1 && adminEmail != null && emails.get(0).equalsIgnoreCase(adminEmail)) {
            return "Test to " + adminEmail;
        }
        int total = ids + emails.size();
        return total + (total == 1 ? " chosen user" : " chosen users");
    }

    /** For the "send to N people?" confirmation. */
    public java.util.Map<String, Object> audienceSize(boolean includeTestAccounts) {
        return java.util.Map.of(
                "allUsers", userRepository.countBroadcastRecipients(includeTestAccounts),
                "reachableByPush", userRepository.countBroadcastPushReachable(includeTestAccounts));
    }

    /** Queued bulk pushes older than this mean the sender is not running. */
    static final long STUCK_AFTER_MINUTES = 10;

    /**
     * What happened to the pushes of the recent broadcasts, and whether the
     * queue that sends big broadcasts is moving.
     *
     * <ul>
     *   <li>sent: Firebase accepted it for at least one of the person's phones</li>
     *   <li>noPhone: no phone signed in with notifications on; it is in their app inbox</li>
     *   <li>waiting: still queued</li>
     *   <li>expired: not sent in time, or the account was closed</li>
     *   <li>failed: Firebase kept refusing it</li>
     * </ul>
     */
    public Map<String, Object> delivery() {
        List<Long> ids = broadcastHistory.findTop100ByOrderByCreatedAtDesc().stream()
                .map(AdminBroadcast::getId).toList();
        Map<String, Map<String, Long>> byBroadcast = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : outboxEventRepository.countBroadcastDelivery(ids)) {
                String id = String.valueOf(((Number) row[0]).longValue());
                String bucket = deliveryBucket(String.valueOf(row[1]), Boolean.TRUE.equals(row[2]));
                long count = ((Number) row[3]).longValue();
                byBroadcast.computeIfAbsent(id, k -> emptyDelivery()).merge(bucket, count, Long::sum);
            }
        }
        Object[] queue = outboxEventRepository.bulkQueueState().get(0);
        long waiting = ((Number) queue[0]).longValue();
        java.time.LocalDateTime oldest = toLocalDateTime(queue[1]);
        long oldestMinutes = oldest == null ? 0
                : java.time.Duration.between(oldest, java.time.LocalDateTime.now()).toMinutes();
        Map<String, Object> queueState = new LinkedHashMap<>();
        queueState.put("waiting", waiting);
        queueState.put("oldestWaitingMinutes", oldestMinutes);
        queueState.put("stuck", waiting > 0 && oldestMinutes >= STUCK_AFTER_MINUTES);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("broadcasts", byBroadcast);
        body.put("queue", queueState);
        return body;
    }

    static String deliveryBucket(String status, boolean reachedAPhone) {
        return switch (status) {
            case "PENDING", "PROCESSING" -> "waiting";
            case "PROCESSED" -> reachedAPhone ? "sent" : "noPhone";
            case "FAILED" -> "failed";
            default -> "expired";
        };
    }

    private static Map<String, Long> emptyDelivery() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String bucket : List.of("sent", "noPhone", "waiting", "expired", "failed")) {
            counts.put(bucket, 0L);
        }
        return counts;
    }

    private static java.time.LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt;
        }
        return null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.moniewise.moniewise_backend.repository.OutboxEventRepository outboxEventRepository;

    public List<AdminBroadcast> history() {
        return broadcastHistory.findTop100ByOrderByCreatedAtDesc();
    }

    private RecipientSelection resolveRecipients(AdminBroadcastRequest request, boolean includeTestAccounts) {
        Map<Long, User> recipientsById = new LinkedHashMap<>();
        List<String> skippedTargets = new ArrayList<>();

        if (hasValues(request.getUserIds())) {
            List<Long> uniqueIds = request.getUserIds().stream()
                    .filter(id -> id != null && id > 0)
                    .distinct()
                    .toList();
            if (!uniqueIds.isEmpty()) {
                for (User user : userRepository.findBroadcastRecipientsByIds(uniqueIds, includeTestAccounts)) {
                    recipientsById.put(user.getId(), user);
                }
            }
            Set<Long> foundIds = recipientsById.keySet();
            uniqueIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .forEach(id -> skippedTargets.add("userId:" + id));
        }

        if (hasValues(request.getEmails())) {
            List<String> normalizedEmails = normalizeEmails(request.getEmails());
            if (!normalizedEmails.isEmpty()) {
                for (User user : userRepository.findBroadcastRecipientsByEmails(normalizedEmails, includeTestAccounts)) {
                    recipientsById.put(user.getId(), user);
                }
            }
            Set<String> foundEmails = new LinkedHashSet<>();
            recipientsById.values().stream()
                    .map(User::getEmail)
                    .filter(email -> email != null && !email.isBlank())
                    .map(email -> email.toLowerCase(Locale.ROOT))
                    .forEach(foundEmails::add);
            normalizedEmails.stream()
                    .filter(email -> !foundEmails.contains(email))
                    .forEach(email -> skippedTargets.add("email:" + email));
        }

        return new RecipientSelection(new ArrayList<>(recipientsById.values()), skippedTargets);
    }

    private long resolveTtlSeconds(Long requestedTtlSeconds, NotificationType type) {
        if (requestedTtlSeconds == null) {
            return switch (type) {
                case SCHEDULED_MAINTENANCE -> 259_200L;
                default -> 86_400L;
            };
        }
        if (requestedTtlSeconds <= 0 || requestedTtlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("ttlSeconds must be between 1 and " + MAX_TTL_SECONDS);
        }
        return requestedTtlSeconds;
    }

    private String defaultFooterNote(NotificationType type) {
        return switch (type) {
            case SERVICE_OUTAGE -> "We will keep things as clear as possible while our team works on this.";
            case SCHEDULED_MAINTENANCE -> "Thank you for bearing with us while we keep Wisemonie reliable.";
            case SPECIAL_ANNOUNCEMENT -> "Thanks for building calmer money habits with Wisemonie.";
            default -> "Thank you for using Wisemonie.";
        };
    }

    private String buildResponseMessage(NotificationType type,
                                        boolean sendFcm,
                                        boolean sendEmail,
                                        boolean deliverNow) {
        String channels = sendFcm && sendEmail
                ? "FCM and email"
                : sendFcm ? "FCM" : "email";
        if (sendFcm && deliverNow) {
            return type.name() + " broadcast started immediately via " + channels + ".";
        }
        return type.name() + " broadcast queued via " + channels + ".";
    }

    private String firstName(User user) {
        Map<String, Object> profileData = user.getProfileData();
        if (profileData == null) {
            return "there";
        }
        Object firstName = profileData.get("firstName");
        if (firstName != null && !firstName.toString().isBlank()) {
            return firstName.toString().trim();
        }
        Object name = profileData.get("name");
        if (name != null && !name.toString().isBlank()) {
            String cleanName = name.toString().trim();
            int firstSpace = cleanName.indexOf(' ');
            return firstSpace > 0 ? cleanName.substring(0, firstSpace) : cleanName;
        }
        return "there";
    }

    private List<String> normalizeEmails(Collection<String> emails) {
        return emails.stream()
                .filter(email -> email != null && !email.isBlank())
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String cleanRequired(String value, String error) {
        String cleaned = blankToNull(value);
        if (cleaned == null) {
            throw new IllegalArgumentException(error);
        }
        return cleaned;
    }

    private String firstNonBlank(String value, String fallback) {
        String cleaned = blankToNull(value);
        return cleaned != null ? cleaned : fallback;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean hasValues(Collection<?> values) {
        return values != null && values.stream().anyMatch(value -> value != null
                && !(value instanceof String text && text.isBlank()));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** An https link, or a path inside the app such as /budgets. */
    static boolean isAllowedLink(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            return lower.length() > "https://".length();
        }
        return lower.startsWith("/") && !lower.startsWith("//") && !lower.contains("\\");
    }

    private boolean isExternalUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private record RecipientSelection(List<User> users, List<String> skippedTargets) {
    }
}
