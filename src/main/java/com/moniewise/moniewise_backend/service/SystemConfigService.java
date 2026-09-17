package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.SystemConfig;
import com.moniewise.moniewise_backend.repository.SystemConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Typed access to the {@code system_config} table.
 *
 * <p>All values are cached in Redis with a 5-minute TTL. Cache is evicted on
 * every {@link #set} call. Falls back to DB when Redis is unreachable
 * (fail-open — same pattern as AbuseProtectionService).
 *
 * <h3>Well-known keys</h3>
 * Use the public constants below to avoid string literals scattered across the codebase.
 */
@Service
public class SystemConfigService {

    private static final Logger logger = LoggerFactory.getLogger(SystemConfigService.class);
    private static final String CACHE_PREFIX = "syscfg:";
    private static final Duration CACHE_TTL   = Duration.ofMinutes(5);
    public static final BigDecimal DEFAULT_BUDGET_MIN_AMOUNT = new BigDecimal("5000");

    // ── Well-known config keys ─────────────────────────────────────────────────
    /** Active PSP: "PROVIDUS", "SECUREWAVE", or "RUBIES" */
    public static final String PSP_ACTIVE            = "psp.active";
    /** Rubies path parameter: "dev" or "prod" */
    public static final String RUBIES_STAGE          = "rubies.stage";
    /**
     * Moniewise's own Rubies MFB account number — the wallet that receives the
     * markup fee on every successful Rubies transfer.
     *
     * <p>Set via: {@code PUT /admin/config/rubies.revenue.account.number}
     * with body {@code {"value":"7012345678"}}.
     *
     * <p>Until this is configured, the markup fee is tracked in the internal
     * revenue wallet only (no actual Rubies transfer is made for the fee).
     */
    public static final String RUBIES_REVENUE_ACCOUNT_NUMBER = "rubies.revenue.account.number";
    /** Display name for Moniewise's Rubies revenue wallet (used in narration). */
    public static final String RUBIES_REVENUE_ACCOUNT_NAME   = "rubies.revenue.account.name";

    /**
     * NOTE: The Rubies webhook auth header pair ("Live Header Key"/"Live
     * Header Value") is intentionally NOT a system_config entry — secrets
     * belong in environment variables, not DB rows readable through a
     * generic admin-config endpoint. See {@code RUBIES_WEBHOOK_HEADER_KEY}
     * / {@code RUBIES_WEBHOOK_HEADER_VALUE} env vars, wired via {@code @Value}
     * directly on {@code WebhookService} (mirrors {@code RUBIES_WEBHOOK_SECRET}
     * / {@code RUBIES_API_KEY} on {@link RubiesGateway}).
     */

    /**
     * Flat Moniewise markup fee (NGN) applied uniformly to every external transfer,
     * regardless of amount.  Default: ₦2.25.
     *
     * <p>The old per-tier keys below are kept for backward-compat (they exist in the DB)
     * but are no longer read by {@link MarkupCalculatorService}.
     */
    public static final String MARKUP_FLAT_FEE       = "transfer.markup.flat_fee";

    /** @deprecated Replaced by {@link #MARKUP_FLAT_FEE} — markup is now a flat fee */
    @Deprecated
    public static final String MARKUP_TIER1_MAX      = "transfer.markup.tier1.max_amount";
    /** @deprecated Replaced by {@link #MARKUP_FLAT_FEE} */
    @Deprecated
    public static final String MARKUP_TIER1_FEE      = "transfer.markup.tier1.fee";
    /** @deprecated Replaced by {@link #MARKUP_FLAT_FEE} */
    @Deprecated
    public static final String MARKUP_TIER2_MAX      = "transfer.markup.tier2.max_amount";
    /** @deprecated Replaced by {@link #MARKUP_FLAT_FEE} */
    @Deprecated
    public static final String MARKUP_TIER2_FEE      = "transfer.markup.tier2.fee";
    /** @deprecated Replaced by {@link #MARKUP_FLAT_FEE} */
    @Deprecated
    public static final String MARKUP_TIER3_FEE      = "transfer.markup.tier3.fee";

    // ── NIBSS NIP interbank transfer fee tiers (charged by Rubies at BaaS level) ───────────
    // These go to Rubies / NIBSS automatically — Moniewise does NOT collect them.
    // Defaults match the current NIBSS published schedule (June 2024).
    /** NIP tier 1 upper bound — transfers ≤ ₦5,000 attract the tier 1 NIP fee */
    public static final String NIP_TIER1_MAX         = "transfer.nip.tier1.max_amount";
    /** NIP fee for tier 1 transfers (NGN) — default ₦10.75 */
    public static final String NIP_TIER1_FEE         = "transfer.nip.tier1.fee";
    /** NIP tier 2 upper bound — transfers ≤ ₦50,000 attract the tier 2 NIP fee */
    public static final String NIP_TIER2_MAX         = "transfer.nip.tier2.max_amount";
    /** NIP fee for tier 2 transfers (NGN) — default ₦26.88 */
    public static final String NIP_TIER2_FEE         = "transfer.nip.tier2.fee";
    /** NIP fee for tier 3 transfers (NGN — above tier2 max) — default ₦53.75 */
    public static final String NIP_TIER3_FEE         = "transfer.nip.tier3.fee";

    /** Stamp duty threshold (NGN) — transfers above this attract stamp duty. Default ₦10,000. */
    public static final String STAMP_DUTY_THRESHOLD   = "transfer.stamp_duty.threshold";
    /** Stamp duty amount (NGN) — flat charge on qualifying transfers. Default ₦50. */
    public static final String STAMP_DUTY_AMOUNT      = "transfer.stamp_duty.amount";

    /** Budget creation fee per 30-day interval (NGN). Set to 0 to disable. */
    public static final String BUDGET_CREATION_FEE   = "budget.creation.fee";

    /** Minimum amount a user can use to create a budget (NGN). */
    public static final String BUDGET_MIN_AMOUNT     = "budget.min.amount";

    /**
     * Maximum budget duration in days.
     * Default: 730 (2 years) — covers goal budgets, project budgets, annual plans.
     * Can be lowered via admin to e.g. 90 for more conservative policies.
     */
    public static final String BUDGET_MAX_DURATION_DAYS = "budget.max.duration.days";

    /** Minimum number of envelopes per budget (server-side guard). Default: 3. */
    public static final String BUDGET_MIN_ENVELOPES = "budget.min.envelopes";

    /** Maximum number of envelopes per budget (server-side guard). Default: 15. */
    public static final String BUDGET_MAX_ENVELOPES = "budget.max.envelopes";

    /** Monthly premium subscription price (NGN) */
    public static final String PREMIUM_MONTHLY_PRICE = "premium.monthly.price";
    /** Comma-separated PremiumFeature values included in premium plan */
    public static final String PREMIUM_FEATURES      = "premium.features";

    // ── Payeelord VAS (airtime & data) ─────────────────────────────────────────
    /**
     * Wholesale discount Payeelord grants on airtime — e.g. {@code 0.975} means
     * Payeelord charges our float 97.5% of face value (a 2.5% wholesale discount).
     * We pass airtime to users at face value, so {@code costAmount = amount × this}
     * and the margin is simply {@code amount − costAmount}. Seeded to "0.975".
     */
    public static final String PAYEELORD_AIRTIME_DISCOUNT_RATE = "payeelord.airtime.discount_rate";
    /**
     * Optional flat markup ON TOP of face value for airtime (NGN). Defaults to 0
     * (we currently sell airtime at face value and keep only the wholesale-discount
     * margin — see {@link #PAYEELORD_AIRTIME_DISCOUNT_RATE}). Admins can raise this
     * later without a code change.
     */
    public static final String PAYEELORD_AIRTIME_MARKUP_AMOUNT = "payeelord.airtime.markup_amount";
    /**
     * Payeelord API key — stored in system_config so the admin UI can set it without a redeploy.
     * The key name matches exactly what the admin panel shows ({@code PAYEELORD_API_KEY}).
     * Falls back to the {@code PAYEELORD_API_KEY} env var if not set here.
     */
    public static final String PAYEELORD_API_KEY = "PAYEELORD_API_KEY";
    /**
     * Payeelord API base URL — runtime-overridable without redeploying.
     * Accepts both {@code PAYEELORD_BASE_URL} (admin-UI style) and {@code payeelord.api.base_url}
     * (dotted style). Either key works; just the domain is enough — {@code /api} is appended
     * automatically if missing (so {@code https://api.payeelord.com} and
     * {@code https://api.payeelord.com/api} are both valid).
     */
    public static final String PAYEELORD_API_BASE_URL = "payeelord.api.base_url";
    public static final String PAYEELORD_BASE_URL     = "PAYEELORD_BASE_URL";
    /** Master switch for the periodic data-plan catalog sync job. Default: false (off until verified). */
    public static final String PAYEELORD_CATALOG_SYNC_ENABLED = "payeelord.catalog.sync.enabled";

    /**
     * Whether to send the Payeelord API key as {@code Authorization: Bearer <key>}
     * (true, default) or as a raw {@code Authorization: <key>} header (false).
     * Payeelord's docs are inconsistent across endpoints; flip this if purchases
     * start returning 401.
     */
    public static final String PAYEELORD_AUTH_USE_BEARER = "payeelord.auth.use_bearer";

    /** Master switch for the Payeelord float low-balance admin alert job. Default: true. */
    public static final String PAYEELORD_BALANCE_ALERT_ENABLED = "payeelord.balance.alert.enabled";
    /** Threshold (NGN) below which the admin low-balance alert fires. Default: 5000. */
    public static final String PAYEELORD_BALANCE_ALERT_THRESHOLD = "payeelord.balance.alert.threshold";
    /** Minutes to suppress repeat low-balance alerts after one fires. Default: 360 (6h). */
    public static final String PAYEELORD_BALANCE_ALERT_COOLDOWN_MINUTES = "payeelord.balance.alert.cooldown_minutes";

    // App update gate. The mobile app calls /app/version-check on startup.
    public static final String APP_UPDATE_ENABLED = "app.update.enabled";
    public static final String APP_UPDATE_REQUIRED_TITLE = "app.update.required_title";
    public static final String APP_UPDATE_REQUIRED_MESSAGE = "app.update.required_message";
    public static final String APP_UPDATE_OPTIONAL_TITLE = "app.update.optional_title";
    public static final String APP_UPDATE_OPTIONAL_MESSAGE = "app.update.optional_message";
    public static final String APP_UPDATE_ANDROID_MIN_VERSION = "app.update.android.min_version";
    public static final String APP_UPDATE_ANDROID_MIN_BUILD = "app.update.android.min_build";
    public static final String APP_UPDATE_ANDROID_LATEST_VERSION = "app.update.android.latest_version";
    public static final String APP_UPDATE_ANDROID_LATEST_BUILD = "app.update.android.latest_build";
    public static final String APP_UPDATE_ANDROID_STORE_URL = "app.update.android.store_url";
    public static final String APP_UPDATE_IOS_MIN_VERSION = "app.update.ios.min_version";
    public static final String APP_UPDATE_IOS_MIN_BUILD = "app.update.ios.min_build";
    public static final String APP_UPDATE_IOS_LATEST_VERSION = "app.update.ios.latest_version";
    public static final String APP_UPDATE_IOS_LATEST_BUILD = "app.update.ios.latest_build";
    public static final String APP_UPDATE_IOS_STORE_URL = "app.update.ios.store_url";

    // ── "What's New" modal (shown to users after app update) ─────────────────
    /** Headline text for the What's New modal. */
    public static final String WHATS_NEW_TITLE = "app.whats_new.title";
    /** JSON array of {@code {"title":"…","body":"…"}} items displayed in the modal. */
    public static final String WHATS_NEW_ITEMS = "app.whats_new.items";

    // ──────────────────────────────────────────────────────────────────────────

    private final SystemConfigRepository repository;
    private final StringRedisTemplate redis;

    public SystemConfigService(SystemConfigRepository repository, StringRedisTemplate redis) {
        this.repository = repository;
        this.redis      = redis;
    }

    // ── Typed getters ─────────────────────────────────────────────────────────

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defaultValue) {
        String cached = getFromCache(key);
        if (cached != null) return cached;

        return repository.findByConfigKey(key)
                .map(cfg -> {
                    putInCache(key, cfg.getConfigValue());
                    return cfg.getConfigValue();
                })
                .orElse(defaultValue);
    }

    public BigDecimal getBigDecimal(String key, BigDecimal defaultValue) {
        String raw = getString(key);
        if (raw == null) return defaultValue;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("[SystemConfig] Cannot parse '{}' as BigDecimal for key='{}' — using default", raw, key);
            return defaultValue;
        }
    }

    public BigDecimal getBudgetMinAmount() {
        BigDecimal amount = getBigDecimal(BUDGET_MIN_AMOUNT, DEFAULT_BUDGET_MIN_AMOUNT);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("[SystemConfig] Invalid budget minimum '{}' — using default", amount);
            return DEFAULT_BUDGET_MIN_AMOUNT;
        }
        return amount;
    }

    public int getInt(String key, int defaultValue) {
        String raw = getString(key);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("[SystemConfig] Cannot parse '{}' as int for key='{}' — using default", raw, key);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = getString(key);
        if (raw == null) return defaultValue;
        return Boolean.parseBoolean(raw.trim());
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Upserts a config entry and immediately evicts it from the Redis cache
     * so the new value is visible within one DB round-trip.
     */
    @Transactional
    public void set(String key, String value, String description) {
        SystemConfig config = repository.findByConfigKey(key)
                .orElseGet(() -> {
                    SystemConfig c = new SystemConfig();
                    c.setConfigKey(key);
                    return c;
                });
        config.setConfigValue(value);
        if (description != null) config.setDescription(description);
        repository.save(config);
        evictCache(key);
        logger.info("[SystemConfig] key='{}' updated to '{}'", key, value);
    }

    public void evictCache(String key) {
        try {
            redis.delete(CACHE_PREFIX + key);
        } catch (Exception e) {
            logger.warn("[SystemConfig] Redis evict failed for key='{}': {}", key, e.getMessage());
        }
    }

    /**
     * Bulk-loads every row in the system_config table into Redis in one shot.
     * Called at server startup by CacheWarmupService so the very first request
     * of the day hits Redis (< 5ms) rather than the DB (10–50ms).
     *
     * <p>Safe to call multiple times — existing Redis entries are simply overwritten.
     */
    public void warmCache() {
        try {
            List<SystemConfig> all = repository.findAll();
            int count = 0;
            for (SystemConfig cfg : all) {
                if (cfg.getConfigKey() != null && cfg.getConfigValue() != null) {
                    putInCache(cfg.getConfigKey(), cfg.getConfigValue());
                    count++;
                }
            }
            logger.info("[SystemConfig] Warmed {} config key(s) into Redis", count);
        } catch (Exception e) {
            logger.warn("[SystemConfig] Startup cache warm failed — " +
                    "keys will lazy-load on first use: {}", e.getMessage());
        }
    }

    // ── Redis helpers ─────────────────────────────────────────────────────────

    private String getFromCache(String key) {
        try {
            return redis.opsForValue().get(CACHE_PREFIX + key);
        } catch (Exception e) {
            logger.warn("[SystemConfig] Redis get failed for key='{}': {}", key, e.getMessage());
            return null;
        }
    }

    private void putInCache(String key, String value) {
        try {
            redis.opsForValue().set(CACHE_PREFIX + key, value, CACHE_TTL);
        } catch (Exception e) {
            logger.warn("[SystemConfig] Redis put failed for key='{}': {}", key, e.getMessage());
        }
    }
}
