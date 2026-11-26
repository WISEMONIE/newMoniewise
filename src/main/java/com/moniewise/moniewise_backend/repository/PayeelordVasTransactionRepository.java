package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.PayeelordVasTransaction;
import com.moniewise.moniewise_backend.enums.VasTransactionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayeelordVasTransactionRepository extends JpaRepository<PayeelordVasTransaction, Long> {

    Optional<PayeelordVasTransaction> findByReference(String reference);

    Optional<PayeelordVasTransaction> findByPayeelordTransactionId(String payeelordTransactionId);

    List<PayeelordVasTransaction> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    /** Purchases left in a given status (e.g. PENDING) since before {@code cutoff} —
     *  used by the recovery sweeper to find deliveries that never finalized. */
    List<PayeelordVasTransaction> findByStatusAndCreatedAtBefore(VasTransactionStatus status,
                                                                 LocalDateTime cutoff);

    @Query("""
        SELECT v FROM PayeelordVasTransaction v
        WHERE v.status = :status
          AND v.createdAt < :cutoff
          AND (v.failureReason IS NULL OR v.failureReason NOT LIKE %:recoveryMarker%)
        ORDER BY v.createdAt ASC
    """)
    List<PayeelordVasTransaction> findStalePendingForRecovery(@Param("status") VasTransactionStatus status,
                                                              @Param("cutoff") LocalDateTime cutoff,
                                                              @Param("recoveryMarker") String recoveryMarker,
                                                              Pageable pageable);

    @Query("SELECT SUM(v.sellingAmount) FROM PayeelordVasTransaction v " +
           "WHERE v.envelopeId = :envelopeId AND v.status = :status AND v.createdAt >= :since")
    BigDecimal sumSettledSellingAmount(@Param("envelopeId") Long envelopeId,
                                       @Param("status") VasTransactionStatus status,
                                       @Param("since") LocalDateTime since);
}
