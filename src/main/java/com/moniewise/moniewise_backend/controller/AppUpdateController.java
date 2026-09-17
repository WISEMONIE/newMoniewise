package com.moniewise.moniewise_backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.response.AppUpdateStatusResponse;
import com.moniewise.moniewise_backend.service.AppUpdateService;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/app")
public class AppUpdateController {

    private static final Logger logger = LoggerFactory.getLogger(AppUpdateController.class);

    private final AppUpdateService appUpdateService;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    public AppUpdateController(AppUpdateService appUpdateService,
                               SystemConfigService systemConfigService,
                               ObjectMapper objectMapper) {
        this.appUpdateService = appUpdateService;
        this.systemConfigService = systemConfigService;
        this.objectMapper = objectMapper;
    }

    @GetMapping({"/version-check", "/update-status"})
    public ResponseEntity<AppUpdateStatusResponse> checkVersion(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String build,
            @RequestHeader(value = "X-App-Platform", required = false) String platformHeader,
            @RequestHeader(value = "X-App-Version", required = false) String versionHeader,
            @RequestHeader(value = "X-App-Build", required = false) String buildHeader) {

        String resolvedPlatform = firstNonBlank(platform, platformHeader);
        String resolvedVersion = firstNonBlank(version, versionHeader);
        String resolvedBuild = firstNonBlank(build, buildHeader);

        return ResponseEntity.ok(appUpdateService.checkUpdate(
                resolvedPlatform,
                resolvedVersion,
                resolvedBuild
        ));
    }

    @GetMapping("/whats-new")
    public ResponseEntity<Map<String, Object>> getWhatsNew() {
        String title = systemConfigService.getString(
                SystemConfigService.WHATS_NEW_TITLE, "What's new in Wisemonie");
        String itemsJson = systemConfigService.getString(
                SystemConfigService.WHATS_NEW_ITEMS, null);

        List<Map<String, String>> items;
        if (itemsJson != null && !itemsJson.isBlank()) {
            try {
                items = objectMapper.readValue(itemsJson,
                        new TypeReference<List<Map<String, String>>>() {});
            } catch (Exception e) {
                logger.warn("[WhatsNew] Failed to parse items JSON: {}", e.getMessage());
                items = Collections.emptyList();
            }
        } else {
            items = Collections.emptyList();
        }

        return ResponseEntity.ok(Map.of("title", title, "items", items));
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }
}
