package com.moniewise.moniewise_backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Locale;

@Component
public class RuntimeRoleService {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeRoleService.class);

    private final RuntimeRole role;

    public RuntimeRoleService(@Value("${moniewise.runtime.role:all}") String configuredRole) {
        this.role = RuntimeRole.from(configuredRole);
    }

    @PostConstruct
    public void logRole() {
        logger.info("[RuntimeRole] Moniewise runtime role is {}", role.name().toLowerCase(Locale.ROOT));
    }

    public boolean workerJobsEnabled() {
        return role == RuntimeRole.ALL || role == RuntimeRole.WORKER;
    }

    public boolean apiEnabled() {
        return role == RuntimeRole.ALL || role == RuntimeRole.API;
    }

    public String currentRole() {
        return role.name().toLowerCase(Locale.ROOT);
    }

    private enum RuntimeRole {
        API,
        WORKER,
        ALL;

        private static RuntimeRole from(String value) {
            if (value == null || value.isBlank()) {
                return ALL;
            }
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "API" -> API;
                case "WORKER" -> WORKER;
                case "ALL" -> ALL;
                default -> {
                    logger.warn("[RuntimeRole] Unknown moniewise.runtime.role='{}'; defaulting to all", value);
                    yield ALL;
                }
            };
        }
    }
}
