package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.EnvelopeAutoTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EnvelopeAutoTransferRepository extends JpaRepository<EnvelopeAutoTransfer, Long> {

    Optional<EnvelopeAutoTransfer> findByEnvelopeId(Long envelopeId);

    Optional<EnvelopeAutoTransfer> findByEnvelopeIdAndUserId(Long envelopeId, Long userId);

    void deleteByEnvelopeId(Long envelopeId);

    boolean existsByEnvelopeId(Long envelopeId);
}
