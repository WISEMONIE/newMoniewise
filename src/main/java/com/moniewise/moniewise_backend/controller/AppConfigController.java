package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.AppConfigResponse;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

@RestController
@RequestMapping("/app")
public class AppConfigController {

    private static final String DEFAULT_CURRENCY = "NGN";

    private final SystemConfigService systemConfig;

    public AppConfigController(SystemConfigService systemConfig) {
        this.systemConfig = systemConfig;
    }

    @GetMapping("/config")
    public ResponseEntity<AppConfigResponse> getConfig() {
        BigDecimal budgetMinAmount = systemConfig.getBudgetMinAmount();

        return ResponseEntity.ok(new AppConfigResponse(
                new AppConfigResponse.BudgetConfig(
                        budgetMinAmount,
                        DEFAULT_CURRENCY,
                        formatMinimumText(budgetMinAmount)
                )
        ));
    }

    private String formatMinimumText(BigDecimal amount) {
        NumberFormat formatter = NumberFormat.getNumberInstance(Locale.US);
        formatter.setMinimumFractionDigits(0);
        formatter.setMaximumFractionDigits(amount.stripTrailingZeros().scale() > 0 ? 2 : 0);
        return "Minimum ₦" + formatter.format(amount);
    }
}
