package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.ScheduledTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

    @Query(value = """
        SELECT *
        FROM scheduled_tasks
        WHERE status = 'PENDING'
          AND trigger_time <= :now
        ORDER BY trigger_time ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<ScheduledTask> claimDueTasks(@Param("now") LocalDateTime now,
                                      @Param("limit") int limit);

    @Modifying
    @Query("""
        UPDATE ScheduledTask st
        SET st.status = :status,
            st.lockedBy = :lockedBy,
            st.lockedAt = :lockedAt
        WHERE st.id IN :ids
    """)
    void markTasksAsProcessing(@Param("ids") List<Long> ids,
                               @Param("status") String status,
                               @Param("lockedBy") String lockedBy,
                               @Param("lockedAt") LocalDateTime lockedAt);

    @Modifying
    @Query("""
        UPDATE ScheduledTask st
        SET st.status = 'COMPLETED',
            st.processedAt = :processedAt
        WHERE st.id IN :ids
    """)
    void markTasksCompleted(@Param("ids") List<Long> ids,
                            @Param("processedAt") LocalDateTime processedAt);

    @Modifying
    @Query("""
        UPDATE ScheduledTask st
        SET st.status = 'FAILED',
            st.retryCount = st.retryCount + 1,
            st.lastError = :error
        WHERE st.id = :id
    """)
    void markTaskFailed(@Param("id") Long id,
                        @Param("error") String error);

    void deleteByEnvelopeId(Long envelopeId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ScheduledTask st WHERE st.triggerTime < :threshold")
    void deleteByTriggerTimeBefore(LocalDateTime threshold);

    List<ScheduledTask> findByEnvelopeId(Long envelopeId);

    @Query("""
        SELECT st FROM ScheduledTask st
        WHERE st.envelopeId = :envelopeId
          AND st.taskType = 'DISBURSEMENT'
          AND st.triggerTime > :now
          AND st.status = 'PENDING'
        ORDER BY st.triggerTime ASC
    """)
    List<ScheduledTask> findNextDisbursementTask(@Param("envelopeId") Long envelopeId,
                                                 @Param("now") LocalDateTime now);

    void deleteByEnvelopeIdAndTaskType(Long envelopeId, String taskType);

    @Modifying
    @Query("""
    UPDATE ScheduledTask st
    SET st.status = 'CANCELLED'
    WHERE st.envelopeId = :envelopeId
      AND st.taskType = :taskType
      AND st.status = 'PENDING'
""")
    void cancelPendingByEnvelopeIdAndTaskType(@Param("envelopeId") Long envelopeId,
                                              @Param("taskType") String taskType);
}