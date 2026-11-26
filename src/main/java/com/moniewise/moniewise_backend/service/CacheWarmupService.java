package com.moniewise.moniewise_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Warms all Redis caches after the server finishes starting up.
 *
 * <h3>What gets warmed and why</h3>
 * <ul>
 *   <li><b>system_config</b> — every config row is bulk-loaded into Redis so
 *       the very first user request of the day reads from Redis (&lt; 5ms)
 *       instead of hitting the DB (10–50ms).</li>
 *   <li><b>Bank list</b> — Rubies' bank-list API is called once at startup and
 *       the result stored in Redis for 24 hours. No user ever waits on a live
 *       upstream call; the bank picker opens instantly every time.</li>
 * </ul>
 *
 * <h3>Design principles</h3>
 * <ul>
 *   <li>Runs <em>after</em> the full Spring context is ready
 *       ({@code ApplicationReadyEvent}) so the server is already accepting
 *       requests before warming begins.</li>
 *   <li>Runs <em>asynchronously</em> — a slow upstream call (e.g. Rubies
 *       sandbox) never delays server startup or the first user request.</li>
 *   <li>Fail-safe — every warm method catches its own exceptions. A partial
 *       failure just means that data lazy-loads on first use as before.</li>
 * </ul>
 */
@Component
public class CacheWarmupService implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(CacheWarmupService.class);

    private final SystemConfigService systemConfigService;
    private final WalletService       walletService;

    public CacheWarmupService(SystemConfigService systemConfigService,
                               @Lazy WalletService walletService) {
        this.systemConfigService = systemConfigService;
        this.walletService       = walletService;
    }

    // ── ApplicationReadyEvent ──────────────────────────────────────────────────

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        logger.info("[CacheWarmup] Server ready — starting background cache warm-up...");
        CompletableFuture
                .runAsync(this::warmAll)
                .exceptionally(ex -> {
                    logger.error("[CacheWarmup] Unexpected warm-up failure: {}", ex.getMessage(), ex);
                    return null;
                });
    }

    // ── Warm-up orchestrator ───────────────────────────────────────────────────

    private void warmAll() {
        long start = System.currentTimeMillis();

        // ── Step 1: system_config table → Redis ───────────────────────────────
        // One DB round-trip for ALL config keys instead of N lazy round-trips.
        logger.info("[CacheWarmup] Warming system config...");
        systemConfigService.warmCache();

        // ── Step 2: Bank list (PSP upstream) → Redis ──────────────────────────
        // Rubies' bank-list API is slow on first call (~2–3s on sandbox).
        // After this, every bank picker in the app opens instantly from Redis.
        logger.info("[CacheWarmup] Warming bank list...");
        walletService.warmBankListCache();

        // Note: MONNIE insight cards are now keyed with a startup-tag
        // (MONNIE_CACHE_PREFIX + STARTUP_TAG + ":" + email). Every server
        // restart produces a new tag, making all previous entries permanently
        // invisible without any explicit deletion step. No flush needed here.

        long elapsed = System.currentTimeMillis() - start;
        logger.info("[CacheWarmup] All caches warmed successfully in {}ms", elapsed);
    }
}
