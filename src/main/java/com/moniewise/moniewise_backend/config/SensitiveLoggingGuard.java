package com.moniewise.moniewise_backend.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps loggers that write secrets or customer data at INFO or quieter,
 * whatever the server's settings say.
 *
 * <p>At DEBUG, the HTTP client logs request headers (the Rubies secret key),
 * RestTemplate logs request bodies (BVN, names, date of birth), Hibernate
 * logs column values (BVN) and Lettuce logs Redis commands (OTPs, cached
 * data). A {@code LOGGING_LEVEL_*} variable on the host can turn these on,
 * so this runs right after Spring sets the log levels and again once the app
 * is ready, and lowers any of them that ended up at DEBUG or TRACE.
 *
 * <p>For local debugging only, set
 * {@code moniewise.logging.allow-sensitive-debug=true}.
 */
public class SensitiveLoggingGuard implements GenericApplicationListener {

    static final String ALLOW_PROPERTY = "moniewise.logging.allow-sensitive-debug";

    static final List<String> SENSITIVE_LOGGERS = List.of(
            // HTTP clients: request and response headers, including Authorization
            "sun.net.www.protocol.http.HttpURLConnection",
            "sun.net.www.protocol.https",
            "org.apache.http",
            "org.apache.hc",
            "httpclient.wire",
            "okhttp3",
            // RestTemplate and message converters: request and response bodies
            "org.springframework.web.client",
            "org.springframework.http",
            // JPA and Hibernate: column values such as the BVN
            "org.hibernate",
            "org.springframework.orm.jpa",
            // Redis: command arguments and values
            "io.lettuce.core",
            "org.springframework.data.redis",
            // TLS handshakes and certificates
            "jdk.event.security",
            "javax.net.ssl");

    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        Class<?> type = eventType.getRawClass();
        return type != null
                && (ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(type)
                || ApplicationReadyEvent.class.isAssignableFrom(type));
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        Environment environment;
        if (event instanceof ApplicationEnvironmentPreparedEvent prepared) {
            environment = prepared.getEnvironment();
        } else if (event instanceof ApplicationReadyEvent ready) {
            environment = ready.getApplicationContext().getEnvironment();
        } else {
            return;
        }
        if (environment.getProperty(ALLOW_PROPERTY, Boolean.class, false)) {
            return;
        }
        LoggingSystem loggingSystem = LoggingSystem.get(SensitiveLoggingGuard.class.getClassLoader());
        List<String> lowered = enforce(loggingSystem);
        if (!lowered.isEmpty()) {
            // stderr: logging itself is what is being corrected here.
            System.err.println("[SensitiveLoggingGuard] Raised to INFO because they were at DEBUG/TRACE: "
                    + String.join(", ", lowered));
        }
    }

    /**
     * Lowers each sensitive logger, and any logger under one of them, that is
     * at DEBUG or TRACE. Returns their names.
     */
    static List<String> enforce(LoggingSystem loggingSystem) {
        List<String> lowered = new ArrayList<>();
        for (String name : SENSITIVE_LOGGERS) {
            if (tooVerbose(loggingSystem, name)) {
                loggingSystem.setLogLevel(name, LogLevel.INFO);
                lowered.add(name);
            }
        }
        // A child set on its own (org.hibernate.SQL=DEBUG) keeps its level
        // when the parent changes, so lower those one by one.
        List<LoggerConfiguration> all = loggingSystem.getLoggerConfigurations();
        if (all != null) {
            for (LoggerConfiguration config : all) {
                String name = config.getName();
                LogLevel configured = config.getConfiguredLevel();
                if (name != null && !lowered.contains(name) && isUnderSensitive(name)
                        && (configured == LogLevel.DEBUG || configured == LogLevel.TRACE)) {
                    loggingSystem.setLogLevel(name, LogLevel.INFO);
                    lowered.add(name);
                }
            }
        }
        return lowered;
    }

    private static boolean isUnderSensitive(String name) {
        for (String prefix : SENSITIVE_LOGGERS) {
            if (name.equals(prefix) || name.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean tooVerbose(LoggingSystem loggingSystem, String name) {
        LoggerConfiguration config = loggingSystem.getLoggerConfiguration(name);
        LogLevel effective = config != null ? config.getEffectiveLevel() : null;
        if (effective == null) {
            LoggerConfiguration root = loggingSystem.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME);
            effective = root != null ? root.getEffectiveLevel() : null;
        }
        return effective == LogLevel.DEBUG || effective == LogLevel.TRACE;
    }

    /** Right after LoggingApplicationListener has applied the configured levels. */
    @Override
    public int getOrder() {
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }

}
