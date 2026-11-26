package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.LegalDocument;
import com.moniewise.moniewise_backend.enums.LegalDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LegalDocumentRepository extends JpaRepository<LegalDocument, Long> {

    Optional<LegalDocument> findFirstByDocTypeAndActiveTrueOrderByEffectiveAtDesc(
            LegalDocumentType docType
    );

    Optional<LegalDocument> findByDocTypeAndVersion(
            LegalDocumentType docType,
            String version
    );

    List<LegalDocument> findByDocTypeOrderByEffectiveAtDesc(
            LegalDocumentType docType
    );
}