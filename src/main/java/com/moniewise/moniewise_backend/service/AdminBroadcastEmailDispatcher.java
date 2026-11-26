package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.AdminBroadcast;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AdminBroadcastEmailDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(AdminBroadcastEmailDispatcher.class);
    private static final int PAGE_SIZE = 200;

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private PlatformTransactionManager transactionManager;

    public AdminBroadcastEmailDispatcher(NotificationService notificationService,
                                         UserRepository userRepository) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    /** Runs a saved broadcast job on a background thread. See {@link #runJob}. */
    @Async
    public void runJobAsync(Long broadcastId, String token) {
        runJob(broadcastId, token);
    }

    /**
     * Sends a saved broadcast to everyone (or a long chosen list), in user-id
     * order, carrying on after {@code cursor_user_id}.
     *
     * <p>For each user, queuing the push and moving the cursor commit together,
     * so after a restart nobody gets the push twice and nobody is skipped. An
     * email goes out after that commit, so a crash at that exact moment can
     * miss one email but never sends one twice.
     *
     * <p>Pushes are only queued here. The outbox worker sends them, after any
     * personal alerts (credits, debits), with its usual retries.
     *
     * <p>{@code token} is this runner's lease. If another runner took over,
     * the next cursor update fails and this one stops.
     */
    public void runJob(Long broadcastId, String token) {
        AdminBroadcast job = broadcastHistory == null ? null : broadcastHistory.findById(broadcastId).orElse(null);
        if (job == null || job.getStatus() != AdminBroadcast.Status.SENDING || !token.equals(job.getRunnerToken())) {
            return;
        }
        NotificationType type = NotificationType.valueOf(job.getType());
        String pushActionType = isExternalUrl(job.getCtaUrl()) ? "OPEN_EXTERNAL_URL" : null;
        long ttlSeconds = job.getTtlSeconds() == null ? 86_400L : job.getTtlSeconds();
        List<Long> chosen = parseIds(job.getRecipientIds());
        long cursor = job.getCursorUserId() == null ? 0L : job.getCursorUserId();
        logger.info("[ADMIN-BROADCAST-JOB] broadcast={} type={} starting after user {}", broadcastId, type, cursor);

        try {
            while (true) {
                Pageable page = PageRequest.of(0, PAGE_SIZE);
                List<User> users = job.isAllUsers()
                        ? userRepository.findBroadcastRecipientsAfter(job.isIncludeTestAccounts(), cursor, page)
                        : userRepository.findBroadcastRecipientsByIdsAfter(chosen, job.isIncludeTestAccounts(), cursor, page);
                if (users.isEmpty()) {
                    break;
                }
                for (User user : users) {
                    Boolean stillMine = transactions().execute(status -> {
                        int pushed = job.isSendPush()
                                ? queuePush(user, type, job.getTitle(), job.getMessage(), pushActionType,
                                        job.getCtaUrl(), ttlSeconds, job.getImageUrl())
                                : 0;
                        if (broadcastHistory.advance(broadcastId, token, user.getId(), pushed, LocalDateTime.now()) == 0) {
                            status.setRollbackOnly(); // lease lost: undo this user's push
                            return false;
                        }
                        return true;
                    });
                    if (!Boolean.TRUE.equals(stillMine)) {
                        logger.warn("[ADMIN-BROADCAST-JOB] broadcast={} taken over by another runner; stopping", broadcastId);
                        return;
                    }
                    if (job.isSendEmail() && processEmail(user, true, job.getEmailSubject(), job.getTag(),
                            job.getTitle(), job.getEmailBody(), job.getFooterNote(), job.getCtaLabel(),
                            job.getCtaUrl(), type) == 1) {
                        broadcastHistory.addEmail(broadcastId);
                    }
                    cursor = user.getId();
                }
            }
            broadcastHistory.finish(broadcastId, token, AdminBroadcast.Status.SENT.name(), LocalDateTime.now());
            logger.info("[ADMIN-BROADCAST-JOB] broadcast={} finished", broadcastId);
        } catch (Exception e) {
            // Left as SENDING: AdminBroadcastResumer picks it up from the cursor.
            logger.error("[ADMIN-BROADCAST-JOB] broadcast={} stopped after user {}; it will resume", broadcastId, cursor, e);
        }
    }

    /** Queues one push. A bad user is logged and skipped, not retried forever. */
    private int queuePush(User user, NotificationType type, String title, String message,
                          String pushActionType, String ctaUrl, long ttlSeconds, String imageUrl) {
        try {
            notificationService.enqueueAdminBroadcastNotification(
                    user.getId(), type, title, message, pushActionType, ctaUrl, ttlSeconds, imageUrl);
            return 1;
        } catch (IllegalArgumentException e) {
            logger.error("[ADMIN-BROADCAST-JOB] push skipped userId={}: {}", user.getId(), e.getMessage());
            return 0;
        }
    }

    static List<Long> parseIds(String text) {
        List<Long> ids = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return ids;
        }
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                ids.add(Long.parseLong(part.trim()));
            }
        }
        return ids;
    }

    private static boolean isExternalUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.moniewise.moniewise_backend.repository.AdminBroadcastRepository broadcastHistory;

    void recordFinish(Long historyId, int recipients, int pushQueued, int emailsQueued) {
        recordFinish(historyId, recipients, pushQueued, emailsQueued, false);
    }

    void recordFinish(Long historyId, int recipients, int pushQueued, int emailsQueued, boolean failed) {
        if (historyId == null || broadcastHistory == null) {
            return;
        }
        try {
            broadcastHistory.findById(historyId).ifPresent(row -> {
                row.setRecipients(recipients);
                row.setPushQueued(pushQueued);
                row.setEmailsQueued(emailsQueued);
                row.setStatus(failed ? AdminBroadcast.Status.FAILED : AdminBroadcast.Status.SENT);
                row.setFinishedAt(LocalDateTime.now());
                row.setUpdatedAt(LocalDateTime.now());
                broadcastHistory.save(row);
            });
        } catch (Exception e) {
            logger.warn("[ADMIN-BROADCAST] Could not record finish for broadcast {}: {}", historyId, e.getMessage());
        }
    }
}
