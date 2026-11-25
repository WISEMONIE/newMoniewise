package com.moniewise.moniewise_backend.aml;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InflowHoldRepository extends JpaRepository<InflowHold, Long> {

    /** Locked, so two transfers at the same moment cannot both fit under the cap. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from InflowHold h where h.userId = :userId and h.status in :statuses order by h.id")
    List<InflowHold> lockByUserAndStatusIn(@Param("userId") Long userId,
                                           @Param("statuses") Collection<InflowHold.Status> statuses);

    List<InflowHold> findByUserIdAndStatusInOrderByIdDesc(Long userId, Collection<InflowHold.Status> statuses);

    Optional<InflowHold> findFirstByUserIdAndReasonAndStatusInOrderByIdDesc(
            Long userId, InflowHold.Reason reason, Collection<InflowHold.Status> statuses);

    boolean existsByUserIdAndReason(Long userId, InflowHold.Reason reason);

    List<InflowHold> findByStatusInOrderByStartedAtAsc(Collection<InflowHold.Status> statuses);

    List<InflowHold> findByUserIdOrderByIdDesc(Long userId);

    /** Large-inflow holds with no word from the customer since they started. */
    List<InflowHold> findByReasonAndStatusAndCustomerContactedAtIsNullAndStartedAtBefore(
            InflowHold.Reason reason, InflowHold.Status status, LocalDateTime before);

    List<InflowHold> findByReasonAndStatusAndEndsAtBefore(
            InflowHold.Reason reason, InflowHold.Status status, LocalDateTime now);
}
