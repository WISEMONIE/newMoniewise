package com.moniewise.moniewise_backend.config;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SentryConfigTest {

    private final SentryConfig config = new SentryConfig();

    @Test
    void beforeSendDropsTransientDatabaseConnectionEvents() {
        SentryEvent event = new SentryEvent(new SQLException("Connection is closed", "08006"));

        assertNull(config.transientDatabaseDisconnectFilter().execute(event, new Hint()));
    }

    @Test
    void beforeSendKeepsNonTransientEvents() {
        SentryEvent event = new SentryEvent(new IllegalStateException("Unexpected state"));

        assertNotNull(config.transientDatabaseDisconnectFilter().execute(event, new Hint()));
    }
}
