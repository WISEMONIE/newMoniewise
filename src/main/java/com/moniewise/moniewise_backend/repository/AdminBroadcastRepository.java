package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.AdminBroadcast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AdminBroadcastRepository extends JpaRepository<AdminBroadcast, Long> {

    List<AdminBroadcast> findTop100ByOrderByCreatedAtDesc();

    Optional<AdminBroadcast> findByRequestKey(String requestKey);

    /** Sends that stopped moving: the server restarted or the runner died. */
    @Query(value = """
            SELECT * FROM admin_broadcasts
            WHERE status = 'SENDING'
              AND (updated_at IS NULL OR updated_at < :staleBefore)
            ORDER BY id ASC
            """, nativeQuery = true)
    List<AdminBroadcast> findStalled(@Param("staleBefore") LocalDateTime staleBefore);

    /**
     * Takes over a stalled send with a new lease. Only one caller can win, so
     * two servers never run the same send.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE admin_broadcasts
            SET runner_token = :token, updated_at = :now, attempts = attempts + 1
            WHERE id = :id
              AND status = 'SENDING'
              AND (updated_at IS NULL OR updated_at < :staleBefore)
            """, nativeQuery = true)
    int claimStalled(@Param("id") Long id, @Param("token") String token,
                     @Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);

    /** Records one more user handled. Returns 0 when this runner no longer holds the lease. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE admin_broadcasts
            SET cursor_user_id = :userId, recipients = recipients + 1,
                push_queued = push_queued + :pushed, updated_at = :now
            WHERE id = :id AND runner_token = :token AND status = 'SENDING'
            """, nativeQuery = true)
    int advance(@Param("id") Long id, @Param("token") String token, @Param("userId") Long userId,
                @Param("pushed") int pushed, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE admin_broadcasts SET emails_queued = emails_queued + 1 WHERE id = :id",
            nativeQuery = true)
    int addEmail(@Param("id") Long id);

    /** Ends this runner's send. {@code status} is SENT or FAILED. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE admin_broadcasts
            SET status = :status, finished_at = :now, updated_at = :now
            WHERE id = :id AND runner_token = :token AND status = 'SENDING'
            """, nativeQuery = true)
    int finish(@Param("id") Long id, @Param("token") String token,
               @Param("status") String status, @Param("now") LocalDateTime now);

    /** Ends a stalled send that cannot be resumed (a small send that ran inside the request). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE admin_broadcasts
            SET status = 'FAILED', finished_at = :now, updated_at = :now
            WHERE id = :id
              AND status = 'SENDING'
              AND (updated_at IS NULL OR updated_at < :staleBefore)
            """, nativeQuery = true)
    int failStalled(@Param("id") Long id, @Param("staleBefore") LocalDateTime staleBefore,
                    @Param("now") LocalDateTime now);
}
