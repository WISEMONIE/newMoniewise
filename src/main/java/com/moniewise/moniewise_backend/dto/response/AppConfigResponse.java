package com.moniewise.moniewise_backend.dto.response;

import java.math.BigDecimal;

public record AppConfigResponse(
        BudgetConfig budget
) {
    public record BudgetConfig(
            BigDecimal minAmount,
            String currency,
            String displayMinimumText
    ) {
    }
}
