package com.moniewise.moniewise_backend.dto.request;

import lombok.Data;
import java.math.BigDecimal;

/**
 * Send money from a MATURED savings pot directly to another Wisemonie user.
 * Mirror of {@link P2PTransferRequest} with the savings goal (path variable)
 * as the source instead of an envelope.
 */
@Data
public class SavingsP2PTransferRequest {
    private String recipientIdentity; // Email OR Phone
    private BigDecimal amount;
    private String note;              // Optional description
    private String transactionPin;
}
