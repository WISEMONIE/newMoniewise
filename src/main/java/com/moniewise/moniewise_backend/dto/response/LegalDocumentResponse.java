package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.enums.LegalDocumentType;

import java.time.LocalDateTime;

public record LegalDocumentResponse(
        Long id,
        LegalDocumentType docType,
        String title,
        String content,
        String version,
        Boolean active,
        LocalDateTime effectiveAt,
        LocalDateTime updatedAt
) {}