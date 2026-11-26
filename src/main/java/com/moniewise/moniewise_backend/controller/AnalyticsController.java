package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.AnalyticsBatchRequest;
import com.moniewise.moniewise_backend.dto.response.AnalyticsSummaryResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.service.AnalyticsService;
import com.moniewise.moniewise_backend.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class AnalyticsController {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsController.class);

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private UserService userService;

    @PostMapping("/analytics/events")
    public ResponseEntity<?> ingestEvents(@RequestBody AnalyticsBatchRequest request,
                                          Authentication authentication) {
        try {
            String email = authentication.getName();
            User user = userService.findByEmail(email);
            int count = analyticsService.ingestBatch(user.getId(), request);
            return ResponseEntity.ok(Map.of("ingested", count));
        } catch (Exception e) {
            logger.error("Error ingesting analytics events", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to ingest events"));
        }
    }

    @GetMapping("/admin/analytics/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getSummary(@RequestParam(defaultValue = "30") int days) {
        try {
            if (days < 1 || days > 365) {
                return ResponseEntity.badRequest().body(Map.of("error", "days must be between 1 and 365"));
            }
            AnalyticsSummaryResponse summary = analyticsService.getSummary(days);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            logger.error("Error fetching analytics summary", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to fetch analytics summary"));
        }
    }
}
