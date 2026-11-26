package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.enums.LegalDocumentType;

public record LegalAcceptanceStatusResponse(
        Long userId,
        LegalDocumentType docType,
        String latestVersion,
        boolean acceptedLatest
) {}