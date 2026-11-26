package com.moniewise.moniewise_backend.dto;

import javax.validation.constraints.NotBlank;

public record UpdateLegalDocumentRequest(
        @NotBlank String content,
        @NotBlank String version
) {}