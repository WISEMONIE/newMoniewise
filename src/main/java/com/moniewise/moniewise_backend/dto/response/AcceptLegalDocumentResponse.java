package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.enums.LegalDocumentType;

import java.time.LocalDateTime;

public record AcceptLegalDocumentResponse(
        Long acceptanceId,
        Long userId,
        Long legalDocumentId,
        LegalDocumentType docType,
        String version,
        LocalDateTime acceptedAt
) {}