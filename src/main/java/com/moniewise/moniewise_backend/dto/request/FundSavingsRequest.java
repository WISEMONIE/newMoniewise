package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

public class FundSavingsRequest {
    
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "100.00", message = "Minimum top-up is ₦100")
    private BigDecimal amount;

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}