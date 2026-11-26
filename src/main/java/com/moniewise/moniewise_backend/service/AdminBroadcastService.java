package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.request.AdminBroadcastRequest;
import com.moniewise.moniewise_backend.dto.response.AdminBroadcastResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AdminBroadcastService {

    private static final Logger logger = LoggerFactory.getLogger(AdminBroadcastService.class);
    private static final int PAGE_SIZE = 500;
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
        String pushActionType = isExternalUrl(ctaUrl) ? "OPEN_EXTERNAL_URL" : null;
        boolean sendFcm = request.getSendFcm() == null || request.getSendFcm();
        boolean sendEmail = request.getSendEmail() == null || request.getSendEmail();
        boolean includeTestAccounts = Boolean.TRUE.equals(request.getIncludeTestAccounts());
        boolean deliverNow = request.getDeliverFcmNow() != null
                ? request.getDeliverFcmNow()
                : defaultDeliverNow;
        long ttlSeconds = resolveTtlSeconds(request.getTtlSeconds(), type);

        if (!sendFcm && !sendEmail) {
            throw new IllegalArgumentException("At least one of sendFcm or sendEmail must be true");
        }

        boolean hasTargets = hasValues(request.getUserIds()) || hasValues(request.getEmails());
        boolean allUsers = request.getAllUsers() == null ? !hasTargets : request.getAllUsers();

        if (allUsers) {
            emailDispatcher.executeBroadcastAsync(
                    type, title, message, emailSubject, emailBody, tag,
                    footerNote, ctaLabel, ctaUrl, pushActionType,
                    sendFcm, sendEmail, includeTestAccounts, deliverNow,
                    ttlSeconds, adminEmail, null, null
            );

            logger.info("[ADMIN-BROADCAST] admin={} type={} allUsers=true — dispatched to background",
                    adminEmail, type);

            return new AdminBroadcastResponse(
                    true,
                    type.name(),
                    -1,
                    0,
                    0,
                    0,
                    deliverNow,
                    List.of(),
                    type.name() + " broadcast accepted — delivering in background via "
                            + (sendFcm && sendEmail ? "FCM and email" : sendFcm ? "FCM" : "email") + "."
            );
        }

        RecipientSelection selection = resolveRecipients(request, includeTestAccounts);
        List<User> recipients = selection.users();

        if (recipients.size() > 50) {
            List<Long> recipientIds = recipients.stream().map(User::getId).toList();
            emailDispatcher.executeBroadcastAsync(
                    type, title, message, emailSubject, emailBody, tag,
                    footerNote, ctaLabel, ctaUrl, pushActionType,
                    sendFcm, sendEmail, includeTestAccounts, deliverNow,
                    ttlSeconds, adminEmail, recipientIds, null
            );

            return new AdminBroadcastResponse(
                    true,
                    type.name(),
                    recipients.size(),
                    0,
                    0,
                    0,
                    deliverNow,
                    selection.skippedTargets(),
                    type.name() + " broadcast accepted for " + recipients.size() + " recipients — delivering in background."
            );
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
                        ttlSeconds
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

        return new AdminBroadcastResponse(
                true,
                type.name(),
                recipients.size(),
                fcmQueued,
                fcmDeliveryAttempts,
                emailRecipients.size(),
                deliverNow,
                selection.skippedTargets(),
                buildResponseMessage(type, sendFcm, sendEmail, deliverNow)
        );
    }

    private RecipientSelection resolveRecipients(AdminBroadcastRequest request, boolean includeTestAccounts) {
        boolean hasTargets = hasValues(request.getUserIds()) || hasValues(request.getEmails());
        boolean allUsers = request.getAllUsers() == null
                ? !hasTargets
                : request.getAllUsers();

        if (allUsers) {
            return new RecipientSelection(loadAllRecipients(includeTestAccounts), List.of());
        }

        if (!hasTargets) {
            throw new IllegalArgumentException("Provide allUsers=true, userIds, or emails");
        }

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

    private List<User> loadAllRecipients(boolean includeTestAccounts) {
        List<User> users = new ArrayList<>();
        Pageable pageable = PageRequest.of(0, PAGE_SIZE);
        Slice<User> slice;
        do {
            slice = userRepository.findBroadcastRecipients(includeTestAccounts, pageable);
            users.addAll(slice.getContent());
            pageable = slice.nextPageable();
        } while (slice.hasNext());
        return users;
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
