package com.moniewise.moniewise_backend.aml;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface InflowEventRepository extends JpaRepository<InflowEvent, Long> {

    boolean existsByUserIdAndReference(Long userId, String reference);

    @Query("select coalesce(sum(e.amount), 0) from InflowEvent e where e.userId = :userId and e.receivedAt >= :since")
    BigDecimal sumSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    List<InflowEvent> findByUserIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(Long userId, LocalDateTime since);

    List<InflowEvent> findByHoldIdOrderByReceivedAtAsc(Long holdId);
}
