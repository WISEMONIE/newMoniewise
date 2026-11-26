package com.moniewise.moniewise_backend.dto.request;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class ExternalTransferRequest {
    private Long sourceEnvelopeId;
    private BigDecimal amount;
    
    // Bank Details
    private String accountNumber;
    private String bankCode;      // e.g., "058"
    private String bankName;      // e.g., "GTBank"
    private String recipientName; // The name resolved from the "Resolve Account" step
    private String narration;     // Optional note
    private String withdrawalReason; // New Field
    private String transactionPin;
}
