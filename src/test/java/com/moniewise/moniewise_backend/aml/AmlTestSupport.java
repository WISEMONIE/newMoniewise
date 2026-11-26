package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.service.SystemConfigService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class AmlTestSupport {

    static final ZoneId ZONE = ZoneId.of("Africa/Lagos");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 14, 0);

    private AmlTestSupport() {
    }

    static Clock clock() {
        return Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    }

    /** Settings that return the agreed defaults. */
    static AmlSettings defaults() {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getString(anyString())).thenReturn(null);
        when(config.getBigDecimal(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        return new AmlSettings(config);
    }

    static BigDecimal naira(long amount) {
        return BigDecimal.valueOf(amount);
    }
}
