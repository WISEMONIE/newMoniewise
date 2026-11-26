package com.moniewise.moniewise_backend.dto.request;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class WalletP2PTransferRequest {

    @NotBlank(message = "Recipient identity is required")
    private String recipientIdentity;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1", message = "Minimum transfer amount is ₦1")
    private BigDecimal amount;

    private String note;

    @NotBlank(message = "Transaction PIN is required")
    private String transactionPin;
}
