package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.exception.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed rate limiter.
 *
 * <p>Two Redis keys are used per (action, caller-key) pair:
 * <ul>
 *   <li>{@code rl:{action}:{key}:cnt} — failure/request counter, TTL = window duration</li>
 *   <li>{@code rl:{action}:{key}:lck} — lockout sentinel, TTL = lockout duration</li>
 * </ul>
 *
 * <p>The service fails <b>open</b> when Redis is unreachable — legitimate users are
 * never blocked by infrastructure failures.  Attackers only benefit briefly; Redis
 * reconnection restores protection automatically.
 */
@Service
public class AbuseProtectionService {

    private static final Logger logger = LoggerFactory.getLogger(AbuseProtectionService.class);
    private static final String PREFIX = "rl:";
    private static final String CNT    = ":cnt";
    private static final String LCK    = ":lck";

    // ── Auth ─────────────────────────────────────────────────────────────────
    public static final String LOGIN            = "auth.login";
    public static final String SIGNUP           = "auth.signup";
    public static final String SIGNUP_VERIFY    = "auth.signup_verify";
    public static final String FORGOT_PASSWORD  = "auth.forgot_password";
    public static final String RESET_VERIFY     = "auth.verify_reset_otp";
    public static final String RESET_PASSWORD   = "auth.reset_password";
    public static final String CHANGE_PASSWORD  = "auth.change_password";
    public static final String GOOGLE_LOGIN     = "auth.google";

    // ── OTP ──────────────────────────────────────────────────────────────────
    public static final String OTP_GENERATE = "otp.generate";
    public static final String OTP_VERIFY   = "otp.verify";

    // ── Wallet ───────────────────────────────────────────────────────────────
    public static final String WALLET_WITHDRAW        = "wallet.withdraw";
    public static final String WALLET_RESOLVE_ACCOUNT = "wallet.resolve_account";
    public static final String WALLET_DETECT_BANKS    = "wallet.detect_banks";
    public static final String WALLET_BANK_INFO       = "wallet.bank_info";
    public static final String P2P_USER_SEARCH        = "p2p.user_search";

    // ── Transaction PIN ───────────────────────────────────────────────────────
    public static final String PIN_VERIFY         = "pin.verify";
    public static final String PIN_CHANGE         = "pin.change";
    public static final String PIN_FORGOT_REQUEST = "pin.forgot_request";
    public static final String PIN_FORGOT_VERIFY  = "pin.forgot_verify";
    public static final String PIN_FORGOT_RESET   = "pin.forgot_reset";

    // ── Transactions / Transfers ──────────────────────────────────────────────
    public static final String TXN_WITHDRAW = "txn.withdraw";
    public static final String TXN_TRANSFER = "txn.transfer";

    // ── Payeelord VAS (airtime & data) ────────────────────────────────────────
    public static final String VAS_AIRTIME_PURCHASE = "vas.airtime_purchase";
    public static final String VAS_DATA_PURCHASE    = "vas.data_purchase";

    // ── Disbursements ─────────────────────────────────────────────────────────
    public static final String DISBURSEMENT_CLAIM = "disbursement.claim";

    // ── AI (volume-limited — counts every call, not just failures) ────────────
    /** Expensive AI calls: budget generation, allocation, assistant turns — 20/hour. */
    public static final String AI_QUERY = "ai.query";
    /**
     * Dashboard nudge endpoint — mostly server-ranked (no Gemini cost), called on
     * every dashboard load/refresh.  Intentionally higher limit so normal browsing
     * never triggers a lockout.  Keeps the 1-hour window but raises the cap to 120.
     */
    public static final String AI_DASHBOARD_ACTION = "ai.dashboard_action";

    // ── Beneficiaries ─────────────────────────────────────────────────────────
    public static final String BENEFICIARY_ADD = "beneficiary.add";

    // ── Blog admin ─────────────────────────────────────────────────────────────
    public static final String BLOG_LOGIN = "auth.blog_login";

    // ── KYC / BVN ─────────────────────────────────────────────────────────────
    /** Public pre-verify step during signup — 3 attempts per 10 min before lockout. */
    public static final String BVN_PRE_VERIFY = "kyc.bvn_pre_verify";

    private final StringRedisTemplate redis;

    public AbuseProtectionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Checks whether this action+key is currently locked out.
     * Throws {@link TooManyRequestsException} if it is.
     * Call this at the very start of the request handler, before any work.
     */
    public void checkAllowed(String action, String key) {
        try {
            String lck = redis.opsForValue().get(lockKey(action, key));
            if (lck != null) {
                Long ttl = redis.getExpire(lockKey(action, key), TimeUnit.SECONDS);
                long retryAfter = (ttl != null && ttl > 0) ? ttl : 1L;
                throw new TooManyRequestsException(
                        "Too many attempts. Please wait before trying again.", retryAfter);
            }
        } catch (TooManyRequestsException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("[RateLimit] Redis unavailable during checkAllowed action={} — failing open: {}",
                    action, e.getMessage());
        }
    }

    /**
     * Records a <b>failed</b> attempt.  When the failure count inside the
     * current window reaches the policy threshold, a lockout key is written.
     */
    public void recordFailure(String action, String key) {
        AttemptPolicy policy = policyFor(action);
        try {
            String cntKey = countKey(action, key);
            Long count = redis.opsForValue().increment(cntKey);
            if (count != null && count == 1L) {
                // First failure in this window — attach window TTL
                redis.expire(cntKey, policy.windowDuration());
            }
            if (count != null && count >= policy.maxAttempts()) {
                redis.opsForValue().set(lockKey(action, key), "1", policy.lockoutDuration());
                redis.delete(cntKey);
            }
        } catch (Exception e) {
            logger.warn("[RateLimit] Redis unavailable during recordFailure action={}: {}", action, e.getMessage());
        }
    }

    /**
     * Resets the failure window on a successful operation.
     */
    public void recordSuccess(String action, String key) {
        try {
            redis.delete(countKey(action, key));
            redis.delete(lockKey(action, key));
        } catch (Exception e) {
            logger.warn("[RateLimit] Redis unavailable during recordSuccess action={}: {}", action, e.getMessage());
        }
    }

    /**
     * Counts <b>every</b> request (success or failure) against the rate limit.
     * Use for volume-limited endpoints such as AI queries where even successful
     * calls consume an external quota or incur cost.
     */
    public void recordRequest(String action, String key) {
        recordFailure(action, key); // identical mechanics — just semantically different call site
    }

    /**
     * Builds a composite throttle key from the caller's identity (e.g. email)
     * and their remote IP address.  Either alone can be spoofed; together they
     * make abuse significantly harder.
     */
    public String buildKey(String identity, String remoteAddress) {
        String id = (identity == null || identity.isBlank()) ? "anon" : identity.trim().toLowerCase();
        String ip = (remoteAddress == null || remoteAddress.isBlank()) ? "unknown" : remoteAddress.trim();
        return id + "|" + ip;
    }

    // ── Key builders ──────────────────────────────────────────────────────────

    private String countKey(String action, String key) {
        return PREFIX + action + ":" + key + CNT;
    }

    private String lockKey(String action, String key) {
        return PREFIX + action + ":" + key + LCK;
    }

    // ── Policy table ──────────────────────────────────────────────────────────

    private AttemptPolicy policyFor(String action) {
        return switch (action) {

            // Auth endpoints — moderate limits, meaningful lockouts
            case LOGIN           -> new AttemptPolicy(5,  Duration.ofMinutes(15), Duration.ofMinutes(15));
            case SIGNUP          -> new AttemptPolicy(5,  Duration.ofHours(1),    Duration.ofHours(1));
            case SIGNUP_VERIFY   -> new AttemptPolicy(5,  Duration.ofMinutes(15), Duration.ofMinutes(30));
            case OTP_GENERATE    -> new AttemptPolicy(3,  Duration.ofMinutes(10), Duration.ofMinutes(10));
            case OTP_VERIFY      -> new AttemptPolicy(5,  Duration.ofMinutes(15), Duration.ofMinutes(15));
            case FORGOT_PASSWORD -> new AttemptPolicy(3,  Duration.ofMinutes(15), Duration.ofMinutes(15));
            case RESET_VERIFY    -> new AttemptPolicy(5,  Duration.ofMinutes(15), Duration.ofMinutes(15));
            // Token is random/unguessable, but still cap attempts as defence-in-depth
            case RESET_PASSWORD  -> new AttemptPolicy(5,  Duration.ofMinutes(15), Duration.ofMinutes(30));
            // Authenticated endpoint — wrong current-password is the threat
            case CHANGE_PASSWORD -> new AttemptPolicy(5,  Duration.ofMinutes(15), Duration.ofMinutes(30));
            case GOOGLE_LOGIN    -> new AttemptPolicy(5,  Duration.ofMinutes(10), Duration.ofMinutes(10));

            // Wallet — generous limits for normal use, blocks abuse
            case WALLET_WITHDRAW        -> new AttemptPolicy(10, Duration.ofHours(1),   Duration.ofHours(1));
            case WALLET_RESOLVE_ACCOUNT -> new AttemptPolicy(30, Duration.ofMinutes(5), Duration.ofMinutes(10));
            // Each detect call probes several banks server-side but counts as ONE request here.
            case WALLET_DETECT_BANKS    -> new AttemptPolicy(20, Duration.ofMinutes(5), Duration.ofMinutes(10));
            case WALLET_BANK_INFO       -> new AttemptPolicy(10, Duration.ofMinutes(15),Duration.ofMinutes(15));
            case P2P_USER_SEARCH        -> new AttemptPolicy(120, Duration.ofMinutes(1), Duration.ofMinutes(5));

            // PIN — tight limits; a 4-digit PIN is the main brute-force surface
            case PIN_VERIFY         -> new AttemptPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(30));
            case PIN_CHANGE         -> new AttemptPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(30));
            case PIN_FORGOT_REQUEST -> new AttemptPolicy(3, Duration.ofMinutes(10), Duration.ofMinutes(10));
            case PIN_FORGOT_VERIFY  -> new AttemptPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(30));
            case PIN_FORGOT_RESET   -> new AttemptPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(30));

            // Transactions
            case TXN_WITHDRAW -> new AttemptPolicy(10, Duration.ofHours(1),    Duration.ofHours(1));
            case TXN_TRANSFER -> new AttemptPolicy(20, Duration.ofHours(1),    Duration.ofMinutes(30));

            // Disbursements
            case DISBURSEMENT_CLAIM -> new AttemptPolicy(10, Duration.ofMinutes(15), Duration.ofMinutes(15));

            // AI — every call counts (Gemini costs money); 20 req/hour per user+IP
            case AI_QUERY -> new AttemptPolicy(20, Duration.ofHours(1), Duration.ofMinutes(30));

            // Dashboard nudge — mostly free server-ranked path; allow 120 req/hour
            // so normal dashboard browsing never triggers a lockout
            case AI_DASHBOARD_ACTION -> new AttemptPolicy(120, Duration.ofHours(1), Duration.ofMinutes(15));

            // Beneficiaries
            case BENEFICIARY_ADD -> new AttemptPolicy(10, Duration.ofHours(1), Duration.ofMinutes(30));

            // Blog admin login — tighter than normal login: only admins should ever call this
            case BLOG_LOGIN -> new AttemptPolicy(3, Duration.ofMinutes(15), Duration.ofMinutes(30));

            // KYC / BVN — tight: 3 attempts per window, 10-minute lockout
            // Each call hits SecureWave; abuse wastes real money and is an enumeration risk
            case BVN_PRE_VERIFY -> new AttemptPolicy(3, Duration.ofMinutes(10), Duration.ofMinutes(10));

            // Safe fallback
            default -> new AttemptPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));
        };
    }

    private record AttemptPolicy(int maxAttempts, Duration windowDuration, Duration lockoutDuration) {}
}
