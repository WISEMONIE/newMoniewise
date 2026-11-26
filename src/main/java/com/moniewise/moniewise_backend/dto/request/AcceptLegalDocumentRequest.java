package com.moniewise.moniewise_backend.dto.request;

import com.moniewise.moniewise_backend.enums.LegalDocumentType;

import javax.validation.constraints.NotNull;

public record AcceptLegalDocumentRequest(
        @NotNull LegalDocumentType docType,
        @NotNull Long legalDocumentId
) {}