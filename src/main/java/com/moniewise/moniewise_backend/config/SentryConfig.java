package com.moniewise.moniewise_backend.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.moniewise.moniewise_backend.utils.ExceptionClassifier;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.exception.ExceptionMechanismException;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

@Configuration
public class SentryConfig {

    private static final String LOGBACK_EVENT_HINT = "syntheticException";

    @Bean
    public SentryOptions.BeforeSendCallback transientDatabaseDisconnectFilter() {
        return (event, hint) -> isTransientDatabaseDisconnect(event, hint) ? null : event;
    }

    private boolean isTransientDatabaseDisconnect(SentryEvent event, Hint hint) {
        if (event == null) {
            return false;
        }

        if (ExceptionClassifier.isTransientConnectionError(unwrapSentryThrowable(event.getThrowable()))) {
            return true;
        }

        ILoggingEvent loggingEvent = loggingEventFromHint(hint);
        if (loggingEvent != null) {
            Throwable throwable = throwableFrom(loggingEvent.getThrowableProxy());
            if (ExceptionClassifier.isTransientConnectionError(throwable)) {
                return true;
            }

            String loggerName = loggingEvent.getLoggerName();
            String message = loggingEvent.getFormattedMessage();
            return isDatabaseInfrastructureLogger(loggerName) && looksLikeTransientConnectionMessage(message);
        }

        return exceptionPayloadLooksTransient(event.getExceptions())
                || looksLikeTransientConnectionMessage(formattedMessage(event.getMessage()));
    }

    private Throwable unwrapSentryThrowable(Throwable throwable) {
        if (throwable instanceof ExceptionMechanismException mechanismException) {
            return mechanismException.getThrowable();
        }
        return throwable;
    }

    private ILoggingEvent loggingEventFromHint(Hint hint) {
        if (hint == null) {
            return null;
        }
        Object candidate = hint.get(LOGBACK_EVENT_HINT);
        return candidate instanceof ILoggingEvent loggingEvent ? loggingEvent : null;
    }

    private Throwable throwableFrom(IThrowableProxy proxy) {
        return proxy instanceof ThrowableProxy throwableProxy ? throwableProxy.getThrowable() : null;
    }

    private boolean exceptionPayloadLooksTransient(List<SentryException> exceptions) {
        if (exceptions == null) {
            return false;
        }
        return exceptions.stream().anyMatch(exception ->
                isSqlConnectionExceptionType(exception.getType())
                        && looksLikeTransientConnectionMessage(exception.getValue()));
    }

    private boolean isSqlConnectionExceptionType(String type) {
        String normalized = lower(type);
        return normalized.contains("sqlexception")
                || normalized.contains("psqlexception")
                || normalized.contains("jdbcconnectionexception");
    }

    private boolean isDatabaseInfrastructureLogger(String loggerName) {
        String normalized = lower(loggerName);
        return normalized.startsWith("org.hibernate.engine.jdbc")
                || normalized.startsWith("org.springframework.transaction")
                || normalized.startsWith("com.zaxxer.hikari");
    }

    private boolean looksLikeTransientConnectionMessage(String message) {
        String normalized = lower(message);
        return normalized.contains("connection is closed")
                || normalized.contains("connection has been closed")
                || normalized.contains("an i/o error occurred while sending to the backend")
                || normalized.contains("sqlstate: 08006")
                || normalized.contains("sqlstate(08006)")
                || normalized.contains("unable to commit against jdbc connection");
    }

    private String formattedMessage(Message message) {
        if (message == null) {
            return null;
        }
        return message.getFormatted() != null ? message.getFormatted() : message.getMessage();
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
