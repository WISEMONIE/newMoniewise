package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

public class WithdrawalQuoteRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "100.0", message = "Minimum withdrawal is ₦100")
    private BigDecimal amount;

    /** Set by the account-closure flow so the quoted fee reflects the flat
     *  closure charge rather than ordinary tiered pricing. */
    private boolean closure;

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public boolean isClosure() { return closure; }
    public void setClosure(boolean closure) { this.closure = closure; }
}
