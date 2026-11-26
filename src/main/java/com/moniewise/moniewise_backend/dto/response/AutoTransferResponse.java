package com.moniewise.moniewise_backend.dto.response;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AutoTransferResponse {
    private Long id;
    private Long envelopeId;
    private String bankCode;
    private String bankName;
    private String accountNumber;
    private String accountName;
    private boolean isAutomated;
    private boolean processing;
    private String processStatus;
    private String processMessage;
    private LocalDateTime createdAt;
}
