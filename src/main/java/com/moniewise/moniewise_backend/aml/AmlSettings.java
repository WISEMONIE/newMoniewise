package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The limits from Rubies' AML guide. Each one can be changed in the
 * system_config table without a deploy. The defaults are the ones agreed
 * for an early-stage platform.
 */
@Component
public class AmlSettings {

    public static final String ENABLED = "aml.enabled";
    public static final String INFLOW_THRESHOLD = "aml.inflow.threshold";
    public static final String INFLOW_THRESHOLD_YOUNG = "aml.inflow.threshold.young_account";
    public static final String YOUNG_ACCOUNT_DAYS = "aml.inflow.young_account_days";
    public static final String HOLD_OUTFLOW_CAP = "aml.hold.outflow_cap";
    public static final String FIRST_DAY_CAP = "aml.first_day.cap";
    public static final String SILENCE_HOURS = "aml.hold.silence_hours";

    static final BigDecimal DEFAULT_INFLOW_THRESHOLD = new BigDecimal("1000000");
    static final BigDecimal DEFAULT_INFLOW_THRESHOLD_YOUNG = new BigDecimal("500000");
    static final int DEFAULT_YOUNG_ACCOUNT_DAYS = 30;
    static final BigDecimal DEFAULT_HOLD_OUTFLOW_CAP = new BigDecimal("50000");
    static final BigDecimal DEFAULT_FIRST_DAY_CAP = new BigDecimal("20000");
    static final int DEFAULT_SILENCE_HOURS = 24;

    /** The first 24 hours after the account opens (CBN, March 2026). */
    public static final int FIRST_DAY_HOURS = 24;

    private final SystemConfigService config;

    public AmlSettings(SystemConfigService config) {
        this.config = config;
    }

    public boolean enabled() {
        String raw = config.getString(ENABLED);
        return raw == null || !"false".equalsIgnoreCase(raw.trim());
    }

    /** Single credit or 24-hour total that starts a hold, for accounts past their first month. */
    public BigDecimal inflowThreshold() {
        return positive(INFLOW_THRESHOLD, DEFAULT_INFLOW_THRESHOLD);
    }

    /** Lower threshold for accounts younger than {@link #youngAccountDays()}. */
    public BigDecimal youngAccountInflowThreshold() {
        return positive(INFLOW_THRESHOLD_YOUNG, DEFAULT_INFLOW_THRESHOLD_YOUNG);
    }

    public int youngAccountDays() {
        return positive(YOUNG_ACCOUNT_DAYS, DEFAULT_YOUNG_ACCOUNT_DAYS);
    }

    /** What a held customer can still send out, all channels together. */
    public BigDecimal holdOutflowCap() {
        return positive(HOLD_OUTFLOW_CAP, DEFAULT_HOLD_OUTFLOW_CAP);
    }

    /** In and out limit for the first 24 hours after the account opens. */
    public BigDecimal firstDayCap() {
        return positive(FIRST_DAY_CAP, DEFAULT_FIRST_DAY_CAP);
    }

    /** No contact from the customer for this long escalates the case. */
    public int silenceHours() {
        return positive(SILENCE_HOURS, DEFAULT_SILENCE_HOURS);
    }

    private BigDecimal positive(String key, BigDecimal fallback) {
        BigDecimal value = config.getBigDecimal(key, fallback);
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private int positive(String key, int fallback) {
        BigDecimal value = config.getBigDecimal(key, BigDecimal.valueOf(fallback));
        return value == null || value.signum() <= 0 ? fallback : value.intValue();
    }
}
