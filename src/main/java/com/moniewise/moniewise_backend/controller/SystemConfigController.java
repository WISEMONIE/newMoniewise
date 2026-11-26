package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.service.AiInsightService;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin-only endpoints for reading and updating runtime system configuration.
 *
 * <p>All endpoints require {@code ROLE_ADMIN}. Never expose these to regular users.
 *
 * <h3>Common use cases</h3>
 * <ul>
 *   <li>Switch PSP: {@code PUT /admin/config/psp.active} body {@code {"value":"RUBIES"}}</li>
 *   <li>Update markup fee: {@code PUT /admin/config/transfer.markup.tier1.fee} body {@code {"value":"100"}}</li>
 *   <li>Change premium price: {@code PUT /admin/config/premium.monthly.price} body {@code {"value":"2500"}}</li>
 *   <li>Disable budget fee: {@code PUT /admin/config/budget.creation.fee} body {@code {"value":"0"}}</li>
 *   <li>Change budget minimum: {@code PUT /admin/config/budget.min.amount} body {@code {"value":"1000"}}</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/config")
@PreAuthorize("hasRole('ADMIN')")
public class SystemConfigController {

    private static final Logger logger = LoggerFactory.getLogger(SystemConfigController.class);

    private final SystemConfigService systemConfig;
    private final AiInsightService    aiInsightService;

    public SystemConfigController(SystemConfigService systemConfig,
                                   AiInsightService aiInsightService) {
        this.systemConfig     = systemConfig;
        this.aiInsightService = aiInsightService;
    }

    /**
     * GET /admin/config/{key}
     *
     * <p>Returns the current value of a config key.
     *
     * <p>Response:
     * <pre>
     * { "key": "psp.active", "value": "RUBIES" }
     * </pre>
     */
    @GetMapping("/{key}")
    public ResponseEntity<?> getConfig(@PathVariable String key) {
        String value = systemConfig.getString(key);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    /**
     * PUT /admin/config/{key}
     *
     * <p>Updates or creates a config entry. The Redis cache is evicted immediately
     * so the new value takes effect within one DB round-trip.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "value": "RUBIES",
     *   "description": "Switched to Rubies MFB"   ← optional
     * }
     * </pre>
     *
     * <p>Response:
     * <pre>
     * { "status": true, "key": "psp.active", "value": "RUBIES" }
     * </pre>
     */
    @PutMapping("/{key}")
    public ResponseEntity<?> setConfig(@PathVariable String key,
                                       @RequestBody Map<String, String> body) {
        String value       = body.get("value");
        String description = body.get("description");

        if (value == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", false, "error", "'value' field is required"));
        }

        logger.info("[Admin] Config update: key='{}' → '{}'", key, value);
        systemConfig.set(key, value, description);

        // When the active PSP changes, stale MONNIE cards must be flushed
        // immediately — otherwise users see incorrect actions (e.g. "Set Account"
        // shown to Rubies users who don't need a pre-registered payout account).
        if (SystemConfigService.PSP_ACTIVE.equals(key)) {
            aiInsightService.evictAllMonnieCaches();
            logger.info("[Admin] PSP switched to '{}' — all MONNIE insight caches flushed", value);
        }

        return ResponseEntity.ok(Map.of(
                "status", true,
                "key",    key,
                "value",  value,
                "message", "Config updated. Change is live immediately."
        ));
    }

    /**
     * POST /admin/config/cache/evict/{key}
     *
     * <p>Manually evicts the Redis cache for a key without changing its value.
     * Useful when the DB was updated directly (e.g. via SQL).
     */
    @PostMapping("/cache/evict/{key}")
    public ResponseEntity<?> evictCache(@PathVariable String key) {
        systemConfig.evictCache(key);
        logger.info("[Admin] Cache evicted for key='{}'", key);
        return ResponseEntity.ok(Map.of(
                "status", true,
                "message", "Cache evicted for key: " + key
        ));
    }

    /**
     * POST /admin/ai/flush-monnie-caches
     *
     * <p>Wipes every user's cached MONNIE insight card from Redis immediately.
     * Call this after any change to the MONNIE prompt so users get fresh
     * cards on their next dashboard load rather than waiting up to 15 minutes
     * for the TTL to expire naturally.
     */
    @PostMapping("/ai/flush-monnie-caches")
    public ResponseEntity<?> flushMonnieCaches() {
        aiInsightService.evictAllMonnieCaches();
        logger.info("[Admin] All MONNIE insight caches flushed via admin endpoint");
        return ResponseEntity.ok(Map.of(
                "status",  true,
                "message", "All MONNIE insight caches flushed. Users will get fresh cards on next dashboard load."
        ));
    }
}
