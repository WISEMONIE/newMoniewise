package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT
                id,
                event_type,
                user_id,
                budget_id,
                envelope_id,
                payload,
                status,
                retry_count,
                last_error,
                locked_at,
                locked_by,
                next_attempt_at,
                processed_at,
                created_at,
                ttl_seconds,
                inbox_saved,
                delivered_tokens
            FROM outbox_events
            WHERE (
                (
                    status = 'PENDING'
                    AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
                )
                OR (
                    status = 'PROCESSING'
                    AND locked_at IS NOT NULL
                    AND locked_at < (CURRENT_TIMESTAMP - INTERVAL '10 minutes')
                )
            )
            AND retry_count < 5
            ORDER BY COALESCE(next_attempt_at, created_at) ASC, created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimPendingEvents(@Param("limit") int limit);

    /** Count of events in a given status — used for dead-letter (FAILED) visibility. */
    long countByStatus(String status);

    @Modifying
    @Query("""
        UPDATE OutboxEvent e
        SET e.status = 'STALE',
            e.processedAt = CURRENT_TIMESTAMP,
            e.nextAttemptAt = NULL,
            e.lockedAt = NULL,
            e.lockedBy = NULL,
            e.lastError = 'Cancelled: account deleted'
        WHERE e.userId = :userId
          AND e.status IN ('PENDING', 'PROCESSING')
    """)
    void cancelPendingByUserId(@Param("userId") Long userId);

    /** Per-type breakdown of permanently-FAILED (dead-letter) events, busiest first. */
    @Query(value = """
            SELECT event_type AS type, COUNT(*) AS cnt
            FROM outbox_events
            WHERE status = 'FAILED'
            GROUP BY event_type
            ORDER BY cnt DESC
            """, nativeQuery = true)
    List<Object[]> countFailedByType();
}
