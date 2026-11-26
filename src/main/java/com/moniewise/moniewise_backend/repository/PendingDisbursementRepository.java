package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.PendingDisbursement;
import com.moniewise.moniewise_backend.enums.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository

public interface PendingDisbursementRepository extends JpaRepository<PendingDisbursement, Long> {

    List<PendingDisbursement> findByStatus(Status status);

    @Query("SELECT p FROM PendingDisbursement p WHERE p.status = 'PENDING' AND p.expiresAt > :now")
    List<PendingDisbursement> findPendingActive(LocalDateTime now);

    @Query("SELECT p FROM PendingDisbursement p WHERE p.status = 'PENDING' AND p.expiresAt <= :now")
    List<PendingDisbursement> findExpired(LocalDateTime now);

    @Query("SELECT pd FROM PendingDisbursement pd WHERE pd.expiresAt <= :expiresAt AND pd.notifiedUser = false")
    List<PendingDisbursement> findByExpiresAtBeforeAndNotifiedUserFalse(@Param("expiresAt") LocalDateTime expiresAt);

    // Existing method for refundExpiredPendingDisbursements
    @Query("SELECT pd FROM PendingDisbursement pd WHERE pd.expiresAt <= :expiresAt AND pd.notifiedUser = true")
    List<PendingDisbursement> findByExpiresAtBeforeAndNotifiedUserTrue(@Param("expiresAt") LocalDateTime expiresAt, Pageable pageable);

    Optional<PendingDisbursement> findFirstByEnvelopeIdAndStatus(Long envelopeId, Status status);

    void deleteByUserId(Long userId);
}
