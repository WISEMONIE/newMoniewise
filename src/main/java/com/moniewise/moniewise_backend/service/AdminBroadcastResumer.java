package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.AdminBroadcast;
import com.moniewise.moniewise_backend.repository.AdminBroadcastRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Finishes admin broadcasts that stopped part-way, usually because the server
 * restarted during a deploy.
 *
 * <p>A running send moves its {@code updated_at} after every user. One that
 * has not moved for {@code stale-minutes} is taken over with a new lease and
 * carries on after its last user. The old runner, if it is somehow still
 * alive, loses the lease and stops, so a send never runs twice at once.
 *
 * <p>A small send that ran inside the request cannot be resumed; it is marked
 * failed so the history does not say "Sending" forever. After
 * {@code max-attempts} takeovers a send is also marked failed.
 */
@Service
public class AdminBroadcastResumer {

    private static final Logger logger = LoggerFactory.getLogger(AdminBroadcastResumer.class);

    private final AdminBroadcastRepository broadcasts;
    private final AdminBroadcastEmailDispatcher dispatcher;

    @Value("${moniewise.admin-broadcast.stale-minutes:5}")
    private long staleMinutes;

    @Value("${moniewise.admin-broadcast.max-attempts:5}")
    private int maxAttempts;

    public AdminBroadcastResumer(AdminBroadcastRepository broadcasts, AdminBroadcastEmailDispatcher dispatcher) {
        this.broadcasts = broadcasts;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${moniewise.admin-broadcast.resume-check-ms:60000}",
            initialDelayString = "${moniewise.admin-broadcast.resume-initial-delay-ms:60000}")
    public void resumeStalled() {
        try {
            resumeStalledNow();
        } catch (RuntimeException e) {
            logger.error("[ADMIN-BROADCAST-RESUME] check failed", e);
        }
    }

    /** Returns how many sends were taken over. */
    public int resumeStalledNow() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusMinutes(Math.max(1, staleMinutes));
        int resumed = 0;
        for (AdminBroadcast row : broadcasts.findStalled(staleBefore)) {
            if (!row.isBackground() || row.getAttempts() >= maxAttempts) {
                if (broadcasts.failStalled(row.getId(), staleBefore, now) == 1) {
                    logger.error("[ADMIN-BROADCAST-RESUME] broadcast={} stopped part-way and was marked failed "
                            + "(background={}, attempts={}, users done={})",
                            row.getId(), row.isBackground(), row.getAttempts(), row.getRecipients());
                }
                continue;
            }
            String token = UUID.randomUUID().toString();
            if (broadcasts.claimStalled(row.getId(), token, staleBefore, now) == 1) {
                logger.warn("[ADMIN-BROADCAST-RESUME] broadcast={} resuming after user {}",
                        row.getId(), row.getCursorUserId());
                dispatcher.runJobAsync(row.getId(), token);
                resumed++;
            }
        }
        return resumed;
    }
}
