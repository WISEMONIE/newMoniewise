package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.UserLegalAcceptance;
import com.moniewise.moniewise_backend.enums.LegalDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserLegalAcceptanceRepository extends JpaRepository<UserLegalAcceptance, Long> {

    boolean existsByUserIdAndDocTypeAndVersion(
            Long userId,
            LegalDocumentType docType,
            String version
    );

    Optional<UserLegalAcceptance> findFirstByUserIdAndDocTypeOrderByAcceptedAtDesc(
            Long userId,
            LegalDocumentType docType
    );
}