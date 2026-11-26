package com.moniewise.moniewise_backend.dto.request;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class P2PTransferRequest {
    private Long sourceEnvelopeId;
    private String recipientIdentity; // Can be Email OR Phone
    private BigDecimal amount;
    private String note; // Optional description
    private String withdrawalReason; // New Field
    private String transactionPin;
}
