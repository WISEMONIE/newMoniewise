package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AdminBroadcastEmailDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(AdminBroadcastEmailDispatcher.class);
    private static final int PAGE_SIZE = 200;

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public AdminBroadcastEmailDispatcher(NotificationService notificationService,
                                         UserRepository userRepository) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @Async
    public void executeBroadcastAsync(NotificationType type,
                                      String title,
                                      String message,
                                      String emailSubject,
                                      String emailBody,
                                      String tag,
                                      String footerNote,
                                      String ctaLabel,
                                      String ctaUrl,
                                      String pushActionType,
                                      boolean sendFcm,
                                      boolean sendEmail,
                                      boolean includeTestAccounts,
                                      boolean deliverNow,
                                      long ttlSeconds,
                                      String adminEmail,
                                      List<Long> recipientIds,
                                      Void unused) {
        logger.info("[ADMIN-BROADCAST-ASYNC] Starting background broadcast type={} admin={}",
                type, adminEmail);

        int fcmQueued = 0;
        int fcmDelivered = 0;
        int emailsSent = 0;
        int totalUsers = 0;

        try {
            if (recipientIds != null) {
                List<User> users = userRepository.findBroadcastRecipientsByIds(recipientIds, includeTestAccounts);
                totalUsers = users.size();
                for (User user : users) {
                    fcmQueued += processFcm(user, sendFcm, type, title, message, pushActionType, ctaUrl, ttlSeconds, deliverNow);
                    emailsSent += processEmail(user, sendEmail, emailSubject, tag, title, emailBody, footerNote, ctaLabel, ctaUrl, type);
                }
                if (deliverNow && sendFcm) fcmDelivered = fcmQueued;
            } else {
                Pageable pageable = PageRequest.of(0, PAGE_SIZE);
                Slice<User> slice;
                do {
                    slice = userRepository.findBroadcastRecipients(includeTestAccounts, pageable);
                    for (User user : slice.getContent()) {
                        totalUsers++;
                        fcmQueued += processFcm(user, sendFcm, type, title, message, pushActionType, ctaUrl, ttlSeconds, deliverNow);
                        emailsSent += processEmail(user, sendEmail, emailSubject, tag, title, emailBody, footerNote, ctaLabel, ctaUrl, type);
                    }
                    pageable = slice.nextPageable();
                } while (slice.hasNext());
                if (deliverNow && sendFcm) fcmDelivered = fcmQueued;
            }
        } catch (Exception e) {
            logger.error("[ADMIN-BROADCAST-ASYNC] Background broadcast failed type={} after processing {} users",
                    type, totalUsers, e);
        }

        logger.info("[ADMIN-BROADCAST-ASYNC] Finished type={} users={} fcmQueued={} fcmDelivered={} emailsSent={}",
                type, totalUsers, fcmQueued, fcmDelivered, emailsSent);
    }

    private int processFcm(User user, boolean sendFcm,
                           NotificationType type, String title, String message,
                           String pushActionType, String ctaUrl, long ttlSeconds,
                           boolean deliverNow) {
        if (!sendFcm) return 0;
        try {
            Long eventId = notificationService.enqueueAdminBroadcastNotification(
                    user.getId(), type, title, message, pushActionType, ctaUrl, ttlSeconds);
            if (deliverNow && eventId != null) {
                notificationService.deliverOutboxEvent(eventId);
            }
            return 1;
        } catch (Exception e) {
            logger.error("[ADMIN-BROADCAST-ASYNC] FCM failed userId={}", user.getId(), e);
            return 0;
        }
    }

    private int processEmail(User user, boolean sendEmail,
                             String subject, String tag, String title, String body,
                             String footerNote, String ctaLabel, String ctaUrl,
                             NotificationType type) {
        if (!sendEmail) return 0;
        String email = user.getEmail();
        if (email == null || email.isBlank()) return 0;
        try {
            notificationService.sendAdminBroadcastEmail(
                    email, firstName(user), subject, tag, title, body,
                    footerNote, ctaLabel, ctaUrl);
            return 1;
        } catch (Exception e) {
            logger.error("[ADMIN-BROADCAST-ASYNC] Email failed userId={}", user.getId(), e);
            return 0;
        }
    }

    @Async
    public void sendBroadcastEmails(List<Recipient> recipients,
                                    String subject,
                                    String tag,
                                    String title,
                                    String body,
                                    String footerNote,
                                    String ctaLabel,
                                    String ctaUrl,
                                    String broadcastType) {
        int sent = 0;
        int skipped = 0;

        for (Recipient recipient : recipients) {
            if (recipient.email() == null || recipient.email().isBlank()) {
                skipped++;
                continue;
            }

            try {
                notificationService.sendAdminBroadcastEmail(
                        recipient.email(),
                        recipient.firstName(),
                        subject,
                        tag,
                        title,
                        body,
                        footerNote,
                        ctaLabel,
                        ctaUrl
                );
                sent++;
            } catch (Exception e) {
                skipped++;
                logger.error("[ADMIN-BROADCAST] Failed to queue email type={} to {}",
                        broadcastType, recipient.email(), e);
            }
        }

        logger.info("[ADMIN-BROADCAST] Email dispatch finished type={} sent={} skipped={}",
                broadcastType, sent, skipped);
    }

    private String firstName(User user) {
        Map<String, Object> profileData = user.getProfileData();
        if (profileData == null) return "there";
        Object firstName = profileData.get("firstName");
        if (firstName != null && !firstName.toString().isBlank()) return firstName.toString().trim();
        Object name = profileData.get("name");
        if (name != null && !name.toString().isBlank()) {
            String cleanName = name.toString().trim();
            int space = cleanName.indexOf(' ');
            return space > 0 ? cleanName.substring(0, space) : cleanName;
        }
        return "there";
    }

    public record Recipient(String email, String firstName) {
    }
}
