package com.moniewise.moniewise_backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SensitiveLoggingGuardTest {

    private final LoggingSystem logging = mock(LoggingSystem.class);

    @Test
    void lowersTheHttpClientWhenItLogsHeaders() {
        level("sun.net.www.protocol.http.HttpURLConnection", LogLevel.DEBUG);

        List<String> lowered = SensitiveLoggingGuard.enforce(logging);

        assertTrue(lowered.contains("sun.net.www.protocol.http.HttpURLConnection"));
        verify(logging).setLogLevel("sun.net.www.protocol.http.HttpURLConnection", LogLevel.INFO);
    }

    @Test
    void aDebugRootLowersEverySensitiveLogger() {
        when(logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME))
                .thenReturn(new LoggerConfiguration("ROOT", LogLevel.DEBUG, LogLevel.DEBUG));

        List<String> lowered = SensitiveLoggingGuard.enforce(logging);

        assertTrue(lowered.containsAll(SensitiveLoggingGuard.SENSITIVE_LOGGERS));
    }

    @Test
    void lowersAChildSetToDebugOnItsOwn() {
        level("org.hibernate", LogLevel.INFO);
        when(logging.getLoggerConfigurations()).thenReturn(List.of(
                new LoggerConfiguration("org.hibernate.SQL", LogLevel.DEBUG, LogLevel.DEBUG),
                new LoggerConfiguration("com.moniewise.moniewise_backend", LogLevel.DEBUG, LogLevel.DEBUG)));

        List<String> lowered = SensitiveLoggingGuard.enforce(logging);

        verify(logging).setLogLevel("org.hibernate.SQL", LogLevel.INFO);
        assertFalse(lowered.contains("com.moniewise.moniewise_backend"));
        verify(logging, never()).setLogLevel(eq("com.moniewise.moniewise_backend"), eq(LogLevel.INFO));
    }

    @Test
    void leavesQuietLoggersAlone() {
        level("org.hibernate", LogLevel.WARN);
        when(logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME))
                .thenReturn(new LoggerConfiguration("ROOT", LogLevel.INFO, LogLevel.INFO));

        SensitiveLoggingGuard.enforce(logging);

        verify(logging, never()).setLogLevel(anyString(), eq(LogLevel.INFO));
    }

    @Test
    void worksOnTheRealLogbackSetup() {
        LoggingSystem real = LoggingSystem.get(getClass().getClassLoader());
        real.setLogLevel("sun.net.www.protocol.http.HttpURLConnection", LogLevel.DEBUG);
        real.setLogLevel("org.hibernate.engine.internal.TwoPhaseLoad", LogLevel.TRACE);

        SensitiveLoggingGuard.enforce(real);

        org.junit.jupiter.api.Assertions.assertEquals(LogLevel.INFO, real
                .getLoggerConfiguration("sun.net.www.protocol.http.HttpURLConnection").getEffectiveLevel());
        org.junit.jupiter.api.Assertions.assertEquals(LogLevel.INFO, real
                .getLoggerConfiguration("org.hibernate.engine.internal.TwoPhaseLoad").getEffectiveLevel());
    }

    private void level(String name, LogLevel level) {
        when(logging.getLoggerConfiguration(name)).thenReturn(new LoggerConfiguration(name, level, level));
    }
}
