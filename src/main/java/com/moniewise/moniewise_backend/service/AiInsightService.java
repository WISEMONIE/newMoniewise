package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.response.AiDashboardNextActionResponse;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.enums.Gender;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.repository.SavingsGoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AiInsightService {

    private static final Logger logger = LoggerFactory.getLogger(AiInsightService.class);
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter NEXT_AVAILABLE_FORMATTER =
        DateTimeFormatter.ofPattern("EEE, d MMM - h:mm a");
    private static final Duration SOON_DISBURSEMENT_WINDOW     = Duration.ofHours(2);  // "releases soon" label — within 2 h
    private static final Duration IMMINENT_DISBURSEMENT_WINDOW = Duration.ofHours(3);  // hard gate — events beyond 3 h are never surfaced
    private static final Duration RECENT_RELEASE_WINDOW        = Duration.ofHours(24);

    // ── MONNIE card Redis cache ────────────────────────────────────────────────
    private static final String MONNIE_CACHE_PREFIX    = "monnie:action:";
    private static final long   MONNIE_CACHE_TTL_MIN   = 15;

    /**
     * A short tag derived from the JVM startup time, embedded in every cache key.
     *
     * <p>Every deploy (server restart) produces a different tag, so all cache
     * entries written by the previous instance are permanently invisible to the
     * new instance — they just expire naturally after 15 minutes.
     *
     * <p>This replaces the fragile "flush all keys on startup" approach: no Redis
     * pattern-scan needed, no race conditions, works 100% of the time without any
     * manual intervention after a deploy.
     */
    private static final String STARTUP_TAG =
            String.valueOf(System.currentTimeMillis() / 1000); // seconds since epoch

    /** Full cache key for a user: prefix + startup-tag + email */
    private String monnieCacheKey(String email) {
        return MONNIE_CACHE_PREFIX + STARTUP_TAG + ":" + email;
    }

    // Minimum number of outgoing transfers needed before we trust a pattern
    private static final int TRANSFER_PATTERN_MIN_SAMPLES = 3;

    private final UserService userService;
    private final BudgetRepository budgetRepository;
    private final WalletRepository walletRepository;
    private final GeminiService geminiService;
    private final AiPromptService aiPromptService;
    private final ObjectMapper objectMapper;
    private final SystemConfigService systemConfigService;
    private final TransactionLogRepository transactionLogRepository;
    private final SavingsGoalRepository savingsGoalRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public AiInsightService(
        UserService userService,
        BudgetRepository budgetRepository,
        WalletRepository walletRepository,
        GeminiService geminiService,
        AiPromptService aiPromptService,
        ObjectMapper objectMapper,
        SystemConfigService systemConfigService,
        TransactionLogRepository transactionLogRepository,
        SavingsGoalRepository savingsGoalRepository,
        RedisTemplate<String, String> redisTemplate
    ) {
        this.userService = userService;
        this.budgetRepository = budgetRepository;
        this.walletRepository = walletRepository;
        this.geminiService = geminiService;
        this.aiPromptService = aiPromptService;
        this.objectMapper = objectMapper;
        this.systemConfigService = systemConfigService;
        this.transactionLogRepository = transactionLogRepository;
        this.savingsGoalRepository = savingsGoalRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Evict MONNIE's cached card for a user. Call this after any wallet/budget
     * transaction so the next dashboard load reflects the latest state.
     */
    /**
     * Evict a single user's MONNIE card — call after a transaction or budget
     * change so their next dashboard load reflects the latest state.
     */
    public void evictMonnieCache(String email) {
        try {
            redisTemplate.delete(monnieCacheKey(email));
            logger.debug("[Monnie] Cache evicted for {}", email);
        } catch (Exception e) {
            logger.warn("[Monnie] Cache eviction failed for {}: {}", email, e.getMessage());
        }
    }

    /**
     * No-op kept for compatibility (PSP switch, admin endpoint).
     *
     * <p>With startup-tag versioning, a server restart already makes ALL previous
     * cache entries permanently invisible — no explicit deletion needed.
     * This method is retained so callers don't break; on PSP switches within the
     * same JVM lifetime it still performs a best-effort pattern-based flush.
     */
    public void evictAllMonnieCaches() {
        try {
            // Attempt a pattern flush for the current startup tag only —
            // entries from previous restarts are already unreachable.
            var keys = redisTemplate.keys(MONNIE_CACHE_PREFIX + STARTUP_TAG + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                logger.info("[Monnie] Flushed {} insight cache(s) (tag={})", keys.size(), STARTUP_TAG);
            }
        } catch (Exception e) {
            logger.warn("[Monnie] Bulk cache flush failed: {}", e.getMessage());
        }
    }

    public AiDashboardNextActionResponse getDashboardNextAction(String email) {
        // ── Redis cache check ─────────────────────────────────────────────────
        String cacheKey = monnieCacheKey(email);
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                AiDashboardNextActionResponse hit =
                    objectMapper.readValue(cached, AiDashboardNextActionResponse.class);
                logger.debug("[Monnie] Cache hit for {}", email);
                return hit;
            }
        } catch (Exception e) {
            logger.warn("[Monnie] Redis read failed — will build fresh: {}", e.getMessage());
        }

        // ── Build context ─────────────────────────────────────────────────────
        DashboardActionContext context;
        try {
            context = buildContext(email);
        } catch (Exception e) {
            logger.warn("AiInsightService: buildContext failed for {}: {}", email, e.getMessage());
            return buildFallback(new DashboardActionContext());
        }

        AiDashboardNextActionResponse deterministic = buildDeterministicRecommendation(context);
        if (context.candidates.isEmpty()) {
            return deterministic != null ? deterministic : buildFallback(context);
        }

        // ── Call Gemini ───────────────────────────────────────────────────────
        AiDashboardNextActionResponse result;
        try {
            String candidatesJson = objectMapper.writeValueAsString(buildCandidatePayloads(context.candidates));
            String prompt = aiPromptService.buildDashboardNextActionPrompt(
                context.firstName,
                context.gender,
                context.occupation,
                context.age,
                context.ageGroup,
                context.demographicContext,
                context.dayOfWeek,
                context.currentTimeFormatted,
                context.isUsualTransferTime,
                context.transferPatternDesc,
                context.activeBudgetCount,
                context.envelopesNearLimit,
                context.hasBudgetDrift,
                context.driftingEnvelopeName,
                context.walletBalance.doubleValue(),
                context.activeBudget != null,
                context.completedBudget != null,
                context.hasLinkedSettlementAccount,
                context.isPspRubies,
                context.activeBudget != null ? context.activeBudget.getName() : "",
                context.daysUntilActiveBudgetEnds,
                context.budgetPctElapsed,
                context.activeBudgetNames,
                context.allEnvelopes,
                context.savingsSnapshot,
                candidatesJson
            );

            String rawText = geminiService.generateText(prompt);
            String cleanedText = cleanJson(rawText);
            AiDashboardNextActionResponse response =
                objectMapper.readValue(cleanedText, AiDashboardNextActionResponse.class);
            result = sanitizeResponse(response, context, deterministic);
        } catch (Exception e) {
            logger.error("[Monnie] Gemini call failed for {} — using deterministic fallback. Error type={} msg={}",
                    email, e.getClass().getSimpleName(), e.getMessage());
            result = deterministic != null ? deterministic : buildFallback(context);
        }

        // ── Cache result ──────────────────────────────────────────────────────
        try {
            redisTemplate.opsForValue().set(
                cacheKey,
                objectMapper.writeValueAsString(result),
                MONNIE_CACHE_TTL_MIN, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.warn("[Monnie] Redis write failed — result still returned: {}", e.getMessage());
        }

        return result;
    }

    /**
     * A truthful, compact summary of the user's savings pots for Monnie — one
     * line each: balance saved, target, status, and (for active pots) the exact
     * maturity date + days remaining. Withdrawn/cancelled pots are omitted.
     * Returns "none" when there are no live pots. Never throws — a savings hiccup
     * must not break the dashboard card.
     */
    private String buildSavingsSnapshot(Long userId) {
        try {
            List<SavingsGoal> goals = savingsGoalRepository.findByUserIdOrderByCreatedAtDesc(userId);
            if (goals == null || goals.isEmpty()) return "none";

            java.time.LocalDate today = java.time.LocalDate.now(LAGOS_ZONE);
            StringBuilder sb = new StringBuilder();
            for (SavingsGoal g : goals) {
                if (g.getStatus() == SavingsStatus.WITHDRAWN || g.getStatus() == SavingsStatus.CANCELLED) {
                    continue; // closed pots aren't actionable context
                }
                BigDecimal balance = g.getCurrentBalance() != null ? g.getCurrentBalance() : BigDecimal.ZERO;
                BigDecimal interest = g.getAccruedInterest() != null ? g.getAccruedInterest() : BigDecimal.ZERO;
                BigDecimal value = balance.add(interest);
                BigDecimal target = g.getTargetAmount() != null ? g.getTargetAmount() : BigDecimal.ZERO;

                if (sb.length() > 0) sb.append('\n');
                sb.append("  • ").append(g.getName())
                  .append(" — ₦").append(String.format("%,.0f", value))
                  .append(" saved of ₦").append(String.format("%,.0f", target)).append(" target");

                if (g.getStatus() == SavingsStatus.MATURED) {
                    sb.append(" | MATURED — ready to withdraw");
                } else if (g.getMaturityDate() != null) {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(today, g.getMaturityDate());
                    String when = g.getMaturityDate().format(
                            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));
                    if (days <= 0) {
                        sb.append(" | matures today (").append(when).append(")");
                    } else {
                        sb.append(" | matures ").append(when)
                          .append(" (").append(days).append(days == 1 ? " day" : " days").append(" left) — LOCKED until then");
                    }
                }
            }
            return sb.length() == 0 ? "none" : sb.toString();
        } catch (Exception e) {
            logger.warn("[Monnie] savings snapshot failed for user {}: {}", userId, e.getMessage());
            return "none";
        }
    }

    private DashboardActionContext buildContext(String email) {
        User user = userService.findByEmail(email);
        Long userId = user.getId();

        // Keep dashboard context reads sequential so one user request cannot fan out
        // into several concurrent database connections and starve critical paths
        // like FCM token registration.
        Optional<Wallet> walletOpt = walletRepository.findByUserId(userId);
        Budget activeBudget = budgetRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, BudgetStatus.ACTIVE)
                .orElse(null);
        Budget completedBudget = budgetRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, BudgetStatus.COMPLETED)
                .orElse(null);
        List<Budget> budgetsWithEnvelopes = budgetRepository.findByUserIdWithEnvelopes(userId);

        List<Budget> activeBudgets = budgetsWithEnvelopes.stream()
            .filter(budget -> budget.getStatus() == BudgetStatus.ACTIVE)
            .toList();

        LocalDateTime nowWat = LocalDateTime.now(LAGOS_ZONE);

        DashboardActionContext context = new DashboardActionContext();
        context.savingsSnapshot = buildSavingsSnapshot(userId);

        // ── Identity ──────────────────────────────────────────────────────────
        context.userName = user.getName() != null && !user.getName().isBlank()
            ? user.getName() : "there";

        // Extract firstName from profileData; fall back to splitting full name
        if (user.getProfileData() != null) {
            Object fnObj = user.getProfileData().get("firstName");
            if (fnObj != null && !fnObj.toString().isBlank()) {
                context.firstName = fnObj.toString().trim();
            } else if (context.userName.contains(" ")) {
                context.firstName = context.userName.substring(0, context.userName.indexOf(" "));
            } else {
                context.firstName = context.userName;
            }
            Object occObj = user.getProfileData().get("occupation");
            if (occObj != null && !occObj.toString().isBlank()) {
                context.occupation = occObj.toString().trim();
            }
            Object expObj = user.getProfileData().get("mainExpense");
            if (expObj != null && !expObj.toString().isBlank()) {
                context.mainExpense = expObj.toString().trim();
            }
            Object goalObj = user.getProfileData().get("savingsGoal");
            if (goalObj != null && !goalObj.toString().isBlank()) {
                context.savingsGoal = goalObj.toString().trim();
            }

            // ── Age / demographic ─────────────────────────────────────────────
            LocalDate dob = extractDateOfBirth(user.getProfileData());
            if (dob != null) {
                context.age = (int) ChronoUnit.YEARS.between(dob, LocalDate.now(LAGOS_ZONE));
                context.ageGroup         = classifyAgeGroup(context.age);
                context.demographicContext = buildDemographicContext(context.age);
            }
        } else {
            context.firstName = context.userName;
        }

        context.gender = user.getGender() != null ? user.getGender().name().toLowerCase() : "unknown";

        // ── Time & day ────────────────────────────────────────────────────────
        context.currentTimeFormatted = formatTime12hr(nowWat);
        context.dayOfWeek = nowWat.getDayOfWeek()
            .getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        // ── PSP awareness ─────────────────────────────────────────────────────
        try {
            String activePsp = systemConfigService.getString(SystemConfigService.PSP_ACTIVE, "");
            context.isPspRubies = "RUBIES".equalsIgnoreCase(activePsp);
        } catch (Exception e) {
            logger.warn("[Monnie] PSP check failed: {}", e.getMessage());
        }

        // ── Wallet ────────────────────────────────────────────────────────────
        context.walletBalance = walletOpt.map(Wallet::getBalance).orElse(BigDecimal.ZERO);
        context.hasLinkedSettlementAccount = walletOpt
            .map(wallet -> wallet.getSettlementAccountNumber() != null
                && !wallet.getSettlementAccountNumber().isBlank())
            .orElse(false);

        // ── Budget state ──────────────────────────────────────────────────────
        context.activeBudget = activeBudget;
        context.completedBudget = completedBudget;
        context.hasBudgetHistory = !activeBudgets.isEmpty() || completedBudget != null;
        context.activeBudgets = activeBudgets;
        context.activeBudgetCount = activeBudgets.size();
        if (activeBudget != null && activeBudget.getEndDate() != null) {
            long days = ChronoUnit.DAYS.between(LocalDate.now(LAGOS_ZONE), activeBudget.getEndDate());
            context.daysUntilActiveBudgetEnds = (int) Math.max(0, days);
        }

        // ── Envelope health (overspend / drift) ───────────────────────────────
        detectEnvelopeIssues(activeBudgets, nowWat, context);

        // ── Budget % elapsed (primary active budget) ──────────────────────────
        if (activeBudget != null
                && activeBudget.getStartDate() != null
                && activeBudget.getEndDate() != null) {
            long totalDays = ChronoUnit.DAYS.between(
                    activeBudget.getStartDate(), activeBudget.getEndDate());
            long daysElapsed = ChronoUnit.DAYS.between(
                    activeBudget.getStartDate(), nowWat.toLocalDate());
            if (totalDays > 0) {
                long clamped = Math.max(0, Math.min(daysElapsed, totalDays));
                context.budgetPctElapsed = (int) Math.round(100.0 * clamped / totalDays);
            }
        }

        // ── All active budget names ────────────────────────────────────────────
        context.activeBudgetNames = activeBudgets.stream()
                .map(b -> b.getName() != null ? b.getName() : "Budget")
                .collect(Collectors.toList());

        // ── Full envelope snapshot across all active budgets ───────────────────
        List<EnvelopeSummaryDto> allEnvelopes = new ArrayList<>();
        for (Budget budget : activeBudgets) {
            if (budget.getEnvelopes() == null) continue;
            String budgetLabel = budget.getName() != null ? budget.getName() : "Budget";
            for (Envelope envelope : budget.getEnvelopes()) {
                // ── Period view (today's spendable pocket vs daily/weekly limit) ──
                BigDecimal periodLimit = resolveReferenceAmount(envelope);
                if (periodLimit == null || periodLimit.compareTo(BigDecimal.ZERO) <= 0) {
                    periodLimit = envelope.getAmount() != null ? envelope.getAmount() : BigDecimal.ZERO;
                }
                if (periodLimit.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal periodRemaining = resolveSpendableAmount(envelope);

                // ── Vault view (total_remaining_amount vs initial_amount) ─────────
                BigDecimal vaultRemaining = envelope.getTotalRemainingAmount() != null
                        ? envelope.getTotalRemainingAmount() : BigDecimal.ZERO;
                BigDecimal vaultInitial = envelope.getInitialAmount() != null
                        && envelope.getInitialAmount().compareTo(BigDecimal.ZERO) > 0
                        ? envelope.getInitialAmount()
                        : (envelope.getAmount() != null ? envelope.getAmount() : periodLimit);

                allEnvelopes.add(new EnvelopeSummaryDto(
                        envelope.getName() != null ? envelope.getName() : "Unnamed",
                        budgetLabel,
                        periodRemaining.doubleValue(),
                        periodLimit.doubleValue(),
                        vaultRemaining.doubleValue(),
                        vaultInitial.doubleValue(),
                        buildDisbursementText(envelope, nowWat)));
            }
        }
        context.allEnvelopes = allEnvelopes;

        // ── Transfer pattern ──────────────────────────────────────────────────
        detectTransferPattern(user.getId(), nowWat, context);

        context.candidates = rankCandidates(context);
        return context;
    }

    /** Detects envelopes near their spending limit ahead of schedule and budget drift. */
    private void detectEnvelopeIssues(List<Budget> activeBudgets, LocalDateTime now, DashboardActionContext ctx) {
        List<String> nearLimit = new ArrayList<>();
        for (Budget budget : activeBudgets) {
            if (budget.getEnvelopes() == null || budget.getStartDate() == null || budget.getEndDate() == null) {
                continue;
            }
            long totalDays = ChronoUnit.DAYS.between(budget.getStartDate(), budget.getEndDate());
            long daysElapsed = ChronoUnit.DAYS.between(budget.getStartDate(), now.toLocalDate());
            if (totalDays <= 0) continue;
            double pctElapsed = Math.max(0, Math.min(1.0, (double) daysElapsed / totalDays));

            for (Envelope envelope : budget.getEnvelopes()) {
                if (!isEnvelopeSpendable(envelope)) {
                    continue;
                }

                BigDecimal allocated = resolveReferenceAmount(envelope);
                BigDecimal remaining = resolveSpendableAmount(envelope);
                if (allocated == null || allocated.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal spent = allocated.subtract(remaining != null ? remaining : allocated);
                if (spent.compareTo(BigDecimal.ZERO) <= 0) continue;

                double pctSpent = spent.divide(allocated, 4, RoundingMode.HALF_UP).doubleValue();

                // Near-limit: > 80% spent before 60% of budget period has elapsed
                if (pctSpent > 0.80 && pctElapsed < 0.60 && !nearLimit.contains(envelope.getName())) {
                    nearLimit.add(envelope.getName());
                }
                // Budget drift: spending at least 40% faster than the time elapsed
                if (!ctx.hasBudgetDrift && pctElapsed > 0.10
                        && pctSpent > (pctElapsed * 1.40)) {
                    ctx.hasBudgetDrift = true;
                    ctx.driftingEnvelopeName = envelope.getName() != null ? envelope.getName() : "";
                }
            }
        }
        ctx.envelopesNearLimit = nearLimit;
    }

    /** Detects the user's habitual outgoing-transfer day + time-of-day slot. */
    private void detectTransferPattern(Long userId, LocalDateTime now, DashboardActionContext ctx) {
        try {
            LocalDateTime since = now.minusDays(60);
            List<TransactionLog> transfers = transactionLogRepository
                .findRecentOutgoingTransfers(userId, since);

            if (transfers.size() < TRANSFER_PATTERN_MIN_SAMPLES) return;

            Map<String, Integer> patternCounts = new HashMap<>();
            for (TransactionLog tx : transfers) {
                if (tx.getCreatedAt() == null) continue;
                String day  = tx.getCreatedAt().getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                String slot = getTimeSlot(tx.getCreatedAt().getHour());
                patternCounts.merge(day + " " + slot, 1, Integer::sum);
            }

            patternCounts.entrySet().stream()
                .filter(e -> e.getValue() >= TRANSFER_PATTERN_MIN_SAMPLES)
                .max(Map.Entry.comparingByValue())
                .ifPresent(top -> {
                    String currentPattern = now.getDayOfWeek()
                        .getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                        + " " + getTimeSlot(now.getHour());
                    if (currentPattern.equals(top.getKey())) {
                        ctx.isUsualTransferTime = true;
                        ctx.transferPatternDesc = top.getKey() + "s"; // e.g. "Friday evenings"
                    }
                });
        } catch (Exception e) {
            logger.warn("[Monnie] Transfer pattern detection failed: {}", e.getMessage());
        }
    }

    /** Maps an hour (0-23) to a human-friendly time slot name. */
    private String getTimeSlot(int hour) {
        if (hour >= 5  && hour < 12) return "mornings";
        if (hour >= 12 && hour < 17) return "afternoons";
        if (hour >= 17 && hour < 21) return "evenings";
        return "nights";
    }

    // ── Demographic helpers ───────────────────────────────────────────────────

    /**
     * Extracts the user's date of birth from profileData.
     *
     * <p>Two storage formats are supported (both may exist simultaneously):
     * <ul>
     *   <li>{@code "dateOfBirth"} → String {@code "YYYY-MM-DD"} (set during KYC / profile update)</li>
     *   <li>{@code "dob"} → {@code List<Integer>} {@code [year, month, day]} (set during onboarding)</li>
     * </ul>
     * Returns {@code null} when neither key is present or parseable.
     */
    private LocalDate extractDateOfBirth(java.util.Map<String, Object> profileData) {
        // Prefer the ISO string form written by KYC / profile-update path
        Object dobStr = profileData.get("dateOfBirth");
        if (dobStr != null && !dobStr.toString().isBlank()) {
            try {
                return LocalDate.parse(dobStr.toString().trim());
            } catch (Exception ignored) { /* fall through */ }
        }
        // Fallback: onboarding stores DOB as a 3-element list [year, month, day]
        Object dobList = profileData.get("dob");
        if (dobList instanceof java.util.List<?> list && list.size() >= 3) {
            try {
                int year  = ((Number) list.get(0)).intValue();
                int month = ((Number) list.get(1)).intValue();
                int day   = ((Number) list.get(2)).intValue();
                return LocalDate.of(year, month, day);
            } catch (Exception ignored) { /* fall through */ }
        }
        return null;
    }

    /** Maps an age in years to a short, labelled age-group string. */
    private String classifyAgeGroup(int age) {
        if (age < 18) return "under 18";
        if (age < 25) return "18–24 (young adult)";
        if (age < 33) return "25–32 (young professional)";
        if (age < 43) return "33–42 (family stage)";
        if (age < 56) return "43–55 (peak earner)";
        return "56+ (senior)";
    }

    /**
     * Returns a concise Nigerian-context life-stage description for the given age.
     * This is injected verbatim into the Gemini prompt so Monnie can tailor both
     * tone and advice without needing to infer demographics from indirect signals.
     */
    private String buildDemographicContext(int age) {
        if (age < 18) {
            return "Under 18 — student or dependent. Light, encouraging tone. "
                 + "Focus on building saving habits and setting small goals. Keep language simple and supportive.";
        }
        if (age < 25) {
            return "18–24 young adult — likely first job or still in school in Nigeria. "
                 + "May have irregular or entry-level income. Common spend pressures: airtime/data, social outings, "
                 + "sending money home to family, peer lifestyle FOMO. "
                 + "Use a casual, energetic, peer-friendly tone — this is a discovery and independence phase. "
                 + "Celebrate small wins; don't lecture.";
        }
        if (age < 33) {
            return "25–32 young professional — career building phase. "
                 + "Typical Nigerian priorities at this stage: saving for rent (especially in Lagos/Abuja), "
                 + "buying a car, funding a wedding, starting a side hustle. "
                 + "FOMO lifestyle spending (owambe parties, weekend trips, fashion) is real but manageable. "
                 + "Tone: aspirational yet grounded — affirm their ambition while anchoring them to the plan.";
        }
        if (age < 43) {
            return "33–42 family stage — Nigeria's sandwich generation. "
                 + "Most people at this age are simultaneously supporting young children AND aging parents. "
                 + "Major spend categories: school fees, spouse expenses, family medical bills, rent or mortgage. "
                 + "Financial pressure is often high; budget discipline is genuinely hard. "
                 + "Tone: warm, empathetic, and practical — acknowledge the load, celebrate discipline, "
                 + "and offer realistic tips rather than idealised advice.";
        }
        if (age < 56) {
            return "43–55 peak earner — more financially established but often stretched. "
                 + "Children may be in secondary school or university. Health spending starts rising. "
                 + "Investment-consciousness increases but retirement planning is often delayed in Nigeria. "
                 + "Tone: professional and forward-thinking — surface long-term implications of near-term spend choices. "
                 + "Treat them as financially mature; skip the basics.";
        }
        return "56+ senior / pre-retirement — fixed or declining income, health is the top priority. "
             + "May still be supporting adult children or grandchildren (common in Nigeria). "
             + "Conservative financial stance; every naira must work harder. "
             + "Tone: respectful, calm, and clear — avoid jargon, prioritise stability over growth narratives.";
    }

    /** Formats a LocalDateTime as 12-hour time (e.g. "5pm", "9:30am"). */
    private String formatTime12hr(LocalDateTime dt) {
        int hour   = dt.getHour();
        int minute = dt.getMinute();
        String amPm = hour < 12 ? "am" : "pm";
        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;
        if (minute == 0) return displayHour + amPm;
        return displayHour + ":" + String.format("%02d", minute) + amPm;
    }

    private List<ActionCandidate> rankCandidates(DashboardActionContext context) {
        List<ActionCandidate> candidates = new ArrayList<>();

        // ── Fund-wallet nudge ─────────────────────────────────────────────────
        // Only shown under two specific conditions (not every time balance hits 0):
        //
        //   1. Balance = 0 AND no active budget at all
        //      → new user who hasn't started, OR user between budget cycles.
        //      Nudging makes sense: there is nothing running that could be spending
        //      the wallet balance anyway.
        //
        //   2. Balance = 0 AND an active budget is ending within 7 days
        //      → user should top up now so they're ready for the next cycle.
        //
        // If the user has a healthy active budget with time left AND their wallet
        // happens to be 0, we stay quiet — their money is already in envelopes and
        // they don't need us nagging them about it.
        final boolean noActiveBudget  = context.activeBudget == null;
        final boolean budgetEndingSoon = context.activeBudget != null
                && context.daysUntilActiveBudgetEnds >= 0
                && context.daysUntilActiveBudgetEnds <= 7;

        if (context.walletBalance.compareTo(BigDecimal.ZERO) <= 0
                && (noActiveBudget || budgetEndingSoon)) {

            final String fundTitle;
            final String fundMsg;
            final String fundReason;

            if (budgetEndingSoon) {
                String daysText = context.daysUntilActiveBudgetEnds == 0
                        ? "today"
                        : "in " + context.daysUntilActiveBudgetEnds + " day(s)";
                fundTitle = context.activeBudget.getName() + " ends " + daysText + " — wallet's empty 👀";
                fundMsg   = "Your current budget closes " + daysText + " and your wallet is at zero. "
                        + "Top up now so you're set for the next cycle without any gap.";
                fundReason = "Active budget ending within 7 days and wallet balance is zero.";
            } else if (!context.hasBudgetHistory) {
                fundTitle  = "Step 1: fund your wallet first 💰";
                fundMsg    = "Your wallet is empty. Add money using your unique account number — "
                        + "budgets, envelopes and smart spending all unlock the moment your first kobo lands.";
                fundReason = "Brand new user: no budget history and wallet is empty.";
            } else {
                fundTitle  = "Your wallet needs a top-up 👀";
                fundMsg    = "No active budget is running and your wallet is at zero. "
                        + "Fund your wallet so you can kick off a fresh budget cycle.";
                fundReason = "No active budget and wallet balance is zero (between cycles).";
            }

            candidates.add(baseCandidate(fundTitle, fundMsg, "Fund wallet",
                    "fund_wallet", "high", fundReason, 1000));
        }

        List<ActionCandidate> spendableEnvelopeCandidates = buildSpendableEnvelopeCandidates(context.activeBudgets);
        candidates.addAll(spendableEnvelopeCandidates);
        if (spendableEnvelopeCandidates.isEmpty()) {
            List<ActionCandidate> dueDisbursementCandidates = buildDueDisbursementCandidates(context.activeBudgets);
            candidates.addAll(dueDisbursementCandidates.isEmpty()
                ? buildNoSpendableEnvelopeCandidates(context)
                : dueDisbursementCandidates);
        } else {
            candidates.addAll(buildUpcomingEnvelopeCandidates(context.activeBudgets));
        }

        if (context.activeBudget != null
            && context.daysUntilActiveBudgetEnds >= 0
            && context.daysUntilActiveBudgetEnds <= 3) {
            String daysText = context.daysUntilActiveBudgetEnds == 0
                ? "today"
                : "in " + context.daysUntilActiveBudgetEnds + " day(s)";
            ActionCandidate endingSoon = baseCandidate(
                context.activeBudget.getName() + " budget ends " + daysText,
                "Your " + context.activeBudget.getName() + " budget closes " + daysText + ". Review your envelope spending before it wraps up.",
                "Review budget",
                "review_active_budget",
                "urgent",
                "Active budget ends within 3 days.",
                900
            );
            endingSoon.budgetId = context.activeBudget.getId();
            endingSoon.budgetName = context.activeBudget.getName();
            candidates.add(endingSoon);
        }

        if (context.activeBudget == null && context.completedBudget == null) {
            candidates.add(baseCandidate(
                "Let's build your first budget 🎯",
                "Give your wallet balance a real job — build a spending plan with envelopes that match how you actually live.",
                "Start budget",
                "create_budget",
                "high",
                "The user has no budget history yet.",
                800
            ));
        }

        if (context.activeBudget == null && context.completedBudget != null) {
            candidates.add(baseCandidate(
                "New cycle, new plan — let's go 📅",
                "Your last budget wrapped up. Set up a fresh one so your money doesn't float around without a job this month.",
                "Create budget",
                "create_budget",
                "high",
                "The user has budget history but no active budget right now.",
                700
            ));
        }

        // User has active budget(s) but hasn't added any envelopes yet — step 3 of the journey.
        // Uses context.allEnvelopes (aggregated across ALL active budgets) so we never trigger
        // this for users who have envelopes in a different budget.
        if (!context.activeBudgets.isEmpty() && context.allEnvelopes.isEmpty()) {
            Budget firstActive = context.activeBudgets.get(0);
            String budgetLabel = firstActive.getName() != null ? firstActive.getName() : "your budget";
            ActionCandidate addEnvelopes = baseCandidate(
                "Split " + budgetLabel + " into envelopes ✉️",
                "Budget created — now give each naira a specific job. Add envelopes like Rent, Food, Transport, Savings so your money knows exactly where to go.",
                "Add envelopes",
                "review_active_budget",
                "high",
                "User has an active budget but no envelopes yet — they must add envelopes before spending can begin.",
                850
            );
            addEnvelopes.budgetId = firstActive.getId();
            addEnvelopes.budgetName = firstActive.getName();
            candidates.add(addEnvelopes);
        }

        // set_account is irrelevant for Rubies — users enter destination at transfer time
        if (!context.hasLinkedSettlementAccount && !context.isPspRubies) {
            double score = context.walletBalance.compareTo(new BigDecimal("50000")) > 0 ? 620 : 420;
            candidates.add(baseCandidate(
                "Link your payout account",
                "Add your account details so when it's time to withdraw, everything's ready and friction-free.",
                "Set account",
                "set_account",
                "normal",
                "The user has not linked a payout account yet.",
                score
            ));
        }

        // ── Envelope near-limit (spending too fast ahead of schedule) ─────────
        if (!context.envelopesNearLimit.isEmpty()) {
            String first = context.envelopesNearLimit.get(0);
            String extra = context.envelopesNearLimit.size() > 1
                ? " (+" + (context.envelopesNearLimit.size() - 1) + " more)"
                : "";
            ActionCandidate nearLimitCandidate = baseCandidate(
                first + " is almost gone" + extra,
                first + " has used over 80% of its allocation — and the budget period isn't close to ending.",
                "Review " + first,
                "review_active_budget",
                "high",
                "Envelope(s) spending ahead of the budget schedule: " + String.join(", ", context.envelopesNearLimit),
                750
            );
            // Wire to the first active budget that contains this envelope
            context.activeBudgets.stream()
                .filter(b -> b.getEnvelopes() != null && b.getEnvelopes().stream()
                    .anyMatch(e -> first.equals(e.getName())))
                .findFirst()
                .ifPresent(b -> {
                    nearLimitCandidate.budgetId = b.getId();
                    nearLimitCandidate.budgetName = b.getName();
                    b.getEnvelopes().stream()
                        .filter(e -> first.equals(e.getName()))
                        .findFirst()
                        .ifPresent(e -> {
                            nearLimitCandidate.envelopeId = e.getId();
                            nearLimitCandidate.envelopeName = e.getName();
                        });
                });
            candidates.add(nearLimitCandidate);
        }

        // ── Budget drift (spending materially faster than time elapsed) ───────
        if (context.hasBudgetDrift && !context.driftingEnvelopeName.isBlank()) {
            ActionCandidate driftCandidate = baseCandidate(
                context.driftingEnvelopeName + " spending faster than planned",
                "You're ahead of your spending pace in " + context.driftingEnvelopeName + ". It may run out before the budget ends.",
                "Review " + context.driftingEnvelopeName,
                "review_active_budget",
                "high",
                "Spending velocity in " + context.driftingEnvelopeName + " exceeds the budget timeline by over 40%.",
                760
            );
            context.activeBudgets.stream()
                .filter(b -> b.getEnvelopes() != null && b.getEnvelopes().stream()
                    .anyMatch(e -> context.driftingEnvelopeName.equals(e.getName())))
                .findFirst()
                .ifPresent(b -> {
                    driftCandidate.budgetId = b.getId();
                    driftCandidate.budgetName = b.getName();
                    b.getEnvelopes().stream()
                        .filter(e -> context.driftingEnvelopeName.equals(e.getName()))
                        .findFirst()
                        .ifPresent(e -> {
                            driftCandidate.envelopeId = e.getId();
                            driftCandidate.envelopeName = e.getName();
                        });
                });
            candidates.add(driftCandidate);
        }

        // ── Usual transfer time nudge ─────────────────────────────────────────
        if (context.isUsualTransferTime && context.walletBalance.compareTo(BigDecimal.ZERO) > 0) {
            candidates.add(baseCandidate(
                "It's your usual transfer time",
                "You typically make transfers on " + context.transferPatternDesc + ". Ready when you are.",
                "Go to budget",
                "review_active_budget",
                "normal",
                "Current day and time matches the user's historical transfer pattern.",
                380
            ));
        }

        Map<String, ActionCandidate> unique = new LinkedHashMap<>();
        for (ActionCandidate candidate : candidates) {
            unique.putIfAbsent(candidate.uniqueKey(), candidate);
        }

        List<ActionCandidate> ranked = new ArrayList<>(unique.values());
        ranked.sort(Comparator.comparingDouble(ActionCandidate::score).reversed());
        return ranked.size() > 5 ? ranked.subList(0, 5) : ranked;
    }

    private List<ActionCandidate> buildSpendableEnvelopeCandidates(List<Budget> activeBudgets) {
        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        List<ActionCandidate> candidates = new ArrayList<>();

        for (Budget budget : activeBudgets) {
            if (budget.getEnvelopes() == null) {
                continue;
            }
            for (Envelope envelope : budget.getEnvelopes()) {
                if (!isEnvelopeSpendable(envelope) || !hasHealthyRemaining(envelope)) {
                    continue;
                }

                BigDecimal availableAmount = resolveSpendableAmount(envelope);
                double score = scoreSpendableEnvelope(budget, envelope, now, availableAmount);
                boolean disbursementReached = hasDisbursementReached(envelope, now) || wasRecentlyReleased(envelope, now);

                // A scheduled envelope whose disbursement time hasn't arrived is LOCKED —
                // its money is not spendable yet (this is exactly what the app shows with
                // the padlock icon). It must NEVER be surfaced as a "spend now / available
                // now / unlocked" candidate — doing so is what made Monnie tell the user a
                // locked Offering envelope was unlocked and ₦X was ready. When it's locked
                // we skip it here; the upcoming-disbursement path below still announces the
                // exact time it unlocks.
                boolean lockedUntilDisbursement = !disbursementReached
                    && envelope.getNextDisbursementAt() != null
                    && envelope.getNextDisbursementAt().isAfter(now);
                if (lockedUntilDisbursement) {
                    continue;
                }

                String message = disbursementReached
                    ? envelope.getName() + " has reached disbursement time. NGN "
                        + formatMoney(availableAmount) + " is ready to spend from " + budget.getName() + "."
                    : budget.getName() + " has NGN " + formatMoney(availableAmount)
                        + " available now in " + envelope.getName() + ".";
                ActionCandidate candidate = baseCandidate(
                    "Spend from " + envelope.getName(),
                    message,
                    "Go to " + envelope.getName(),
                    "review_active_budget",
                    "high",
                    disbursementReached
                        ? "This envelope's disbursement time has reached and it has spendable balance."
                        : "This envelope is currently unlocked and still has healthy spendable balance.",
                    score
                );
                candidate.budgetId = budget.getId();
                candidate.budgetName = budget.getName();
                candidate.envelopeId = envelope.getId();
                candidate.envelopeName = envelope.getName();
                candidate.amountValue = availableAmount != null ? availableAmount.doubleValue() : null;
                if (disbursementReached) {
                    candidate.nextAvailableAt = "now";
                    candidate.countdownText = "right now";
                }
                candidates.add(candidate);
            }
        }

        candidates.sort(Comparator.comparingDouble(ActionCandidate::score).reversed());
        return candidates.size() > 3 ? candidates.subList(0, 3) : candidates;
    }

    private List<ActionCandidate> buildUpcomingEnvelopeCandidates(List<Budget> activeBudgets) {
        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        List<ActionCandidate> candidates = new ArrayList<>();

        for (Budget budget : activeBudgets) {
            if (budget.getEnvelopes() == null) {
                continue;
            }
            for (Envelope envelope : budget.getEnvelopes()) {
                LocalDateTime nextDisbursementAt = envelope.getNextDisbursementAt();
                if (nextDisbursementAt == null || !nextDisbursementAt.isAfter(now) || !hasFutureValue(envelope)) {
                    continue;
                }
                // Hard gate: only surface disbursements within the next 3 hours — skip everything further away
                if (Duration.between(now, nextDisbursementAt).compareTo(IMMINENT_DISBURSEMENT_WINDOW) > 0) {
                    continue;
                }

                BigDecimal upcomingAmount = resolveUpcomingAmount(envelope);
                candidates.add(buildUpcomingEnvelopeCandidate(budget, envelope, upcomingAmount, now, false));
            }
        }

        candidates.sort(Comparator.comparingDouble(ActionCandidate::score).reversed());
        return candidates.size() > 2 ? candidates.subList(0, 2) : candidates;
    }

    private List<ActionCandidate> buildDueDisbursementCandidates(List<Budget> activeBudgets) {
        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        List<ActionCandidate> candidates = new ArrayList<>();

        for (Budget budget : activeBudgets) {
            if (budget.getEnvelopes() == null) {
                continue;
            }
            for (Envelope envelope : budget.getEnvelopes()) {
                if (!hasDisbursementReached(envelope, now) || hasHealthyRemaining(envelope) || !hasFutureValue(envelope)) {
                    continue;
                }

                ActionCandidate candidate = baseCandidate(
                    envelope.getName() + " is due now",
                    envelope.getName() + " has reached disbursement time. Give it a moment; your naira is at the door.",
                    "Open " + envelope.getName(),
                    "review_active_budget",
                    "high",
                    "This envelope's scheduled disbursement time has reached, but no spendable balance is visible yet.",
                    790
                );
                candidate.budgetId = budget.getId();
                candidate.budgetName = budget.getName();
                candidate.envelopeId = envelope.getId();
                candidate.envelopeName = envelope.getName();
                candidate.amountValue = Optional.ofNullable(resolveUpcomingAmount(envelope))
                    .map(BigDecimal::doubleValue)
                    .orElse(null);
                candidate.nextAvailableAt = "now";
                candidate.countdownText = "right now";
                candidates.add(candidate);
            }
        }

        candidates.sort(Comparator.comparingDouble(ActionCandidate::score).reversed());
        return candidates.size() > 2 ? candidates.subList(0, 2) : candidates;
    }

    private List<ActionCandidate> buildNoSpendableEnvelopeCandidates(DashboardActionContext context) {
        // buildUpcomingEnvelopeCandidates already gates on IMMINENT_DISBURSEMENT_WINDOW (3 h),
        // so this list only contains truly imminent events.
        List<ActionCandidate> upcoming = buildUpcomingEnvelopeCandidates(context.activeBudgets, true);
        if (!upcoming.isEmpty()) {
            return List.of(upcoming.get(0));
        }

        // Nothing imminent in the next 3 hours — return a personalised, empathetic motivational card
        return List.of(buildPersonalizedMotivationalCandidate(context));
    }

    /**
     * Builds a warm, profile-aware motivational card for when there are no active envelopes
     * to spend from and no disbursement is scheduled within the next 3 hours.
     *
     * <p>Uses the user's name, occupation, age, savings goal, and main expense to make the
     * message feel like it comes from a real personal finance assistant who knows them.
     * The {@code reason} string is detailed enough that Gemini can produce richer, even more
     * empathetic copy when it rewrites the card.
     */
    private ActionCandidate buildPersonalizedMotivationalCandidate(DashboardActionContext context) {
        int hour = LocalDateTime.now(LAGOS_ZONE).getHour();
        String timeOfDay = hour < 12 ? "morning" : hour < 17 ? "afternoon" : hour < 21 ? "evening" : "night";
        boolean isWeekend = "Saturday".equals(context.dayOfWeek) || "Sunday".equals(context.dayOfWeek);

        String activeBudgetName = context.activeBudget != null && context.activeBudget.getName() != null
                ? context.activeBudget.getName() : null;
        String actionType = activeBudgetName != null ? "review_active_budget" : "create_budget";
        String ctaLabel   = activeBudgetName != null ? "View budget" : "Start a budget";

        String title   = buildMotivationalTitle(context.firstName, timeOfDay, isWeekend, context.occupation);
        String message = buildMotivationalMessage(
                context.firstName, activeBudgetName, timeOfDay, isWeekend,
                context.occupation, context.savingsGoal, context.mainExpense, context.age
        );

        // Rich reason string — Gemini reads this to know what kind of card to produce
        StringBuilder reason = new StringBuilder();
        reason.append("No envelope disbursement is scheduled in the next 3 hours — this is a quiet, low-activity moment. ")
              .append("Write a warm, empathetic, psychologically supportive message that feels like a real personal finance ")
              .append("assistant who genuinely knows this user. Profile context: name=").append(context.firstName);
        if (!context.occupation.isBlank())  reason.append(", occupation=").append(context.occupation);
        if (context.age > 0)                reason.append(", age=").append(context.age);
        if (!context.savingsGoal.isBlank()) reason.append(", savingsGoal=").append(context.savingsGoal);
        if (!context.mainExpense.isBlank()) reason.append(", mainExpense=").append(context.mainExpense);
        reason.append(", time=").append(context.currentTimeFormatted)
              .append(", day=").append(context.dayOfWeek).append(". ");
        if (activeBudgetName != null) reason.append("Active budget: ").append(activeBudgetName).append(". ");
        reason.append("DO NOT invent disbursement events that do not exist. Keep the message warm, grounding, and human — ")
              .append("reference the budget or occupation naturally where it makes the message feel personal.");

        ActionCandidate candidate = baseCandidate(title, message, ctaLabel, actionType, "normal", reason.toString(), 420);
        if (context.activeBudget != null) {
            candidate.budgetId   = context.activeBudget.getId();
            candidate.budgetName = context.activeBudget.getName();
        }
        return candidate;
    }

    /** Builds a time-of-day / occupation-aware title for the motivational card. */
    private String buildMotivationalTitle(String name, String timeOfDay, boolean isWeekend, String occupation) {
        if (!occupation.isBlank()) {
            if ("morning".equals(timeOfDay)) {
                return "Good morning, " + name + " — your plan is ready 🌅";
            } else if ("evening".equals(timeOfDay)) {
                return "Evening check-in, " + name + " — budget held up today 🌙";
            } else if ("night".equals(timeOfDay)) {
                return "Winding down, " + name + "? Budget stayed strong tonight 🌙";
            } else if (isWeekend) {
                return "Weekend mode, " + name + " — money's still working 💪";
            } else {
                return "Steady as you go, " + name + " 🎯";
            }
        }
        if (isWeekend) {
            return "Enjoy your " + timeOfDay + ", " + name + " 🙌";
        }
        if ("morning".equals(timeOfDay)) {
            return "Morning, " + name + " — all quiet and on track ☀️";
        } else if ("afternoon".equals(timeOfDay)) {
            return "Midday check, " + name + " — your money is steady 💚";
        } else if ("evening".equals(timeOfDay)) {
            return "Good evening, " + name + " — budget's holding strong 🌙";
        } else {
            return "All quiet, " + name + " — your plan is doing its job ✅";
        }
    }

    /**
     * Builds the motivational card's supporting message.
     * Uses occupation, savings goal, main expense, and age-tier wisdom
     * to give Gemini something genuinely personal to improve upon.
     */
    private String buildMotivationalMessage(
            String name, String activeBudgetName, String timeOfDay,
            boolean isWeekend, String occupation, String savingsGoal,
            String mainExpense, int age
    ) {
        StringBuilder msg = new StringBuilder();

        // Ground the message in the active budget
        if (activeBudgetName != null) {
            msg.append(activeBudgetName).append(" is on track");
            if ("night".equals(timeOfDay)) {
                msg.append(" through the night");
            } else if (isWeekend) {
                msg.append(" this ").append("morning".equals(timeOfDay) ? "weekend morning" : "weekend");
            }
            msg.append(".");
        } else {
            msg.append("No active budget yet — a great moment to start one.");
        }

        // Occupation context
        if (!occupation.isBlank()) {
            msg.append(" As a ").append(occupation.toLowerCase()).append(", staying consistent is how you win.");
        }

        // Savings goal (keep brief)
        if (!savingsGoal.isBlank() && msg.length() < 90) {
            msg.append(" Your ").append(savingsGoal.toLowerCase()).append(" goal is still in motion.");
        }

        // Age-tier wisdom — invisible (no numeric mention)
        if (msg.length() < 100) {
            if (age > 0 && age < 25) {
                msg.append(" Building this habit early is the real edge.");
            } else if (age >= 25 && age < 35) {
                msg.append(" This discipline adds up faster than you think.");
            } else if (age >= 35 && age < 45) {
                msg.append(" Staying consistent now protects what matters later.");
            }
        }

        String result = msg.toString().trim();
        // Gemini rewrites this; just ensure it's within the 130-char limit for the fallback path
        return result.length() > 130 ? result.substring(0, 127) + "…" : result;
    }

    private List<ActionCandidate> buildUpcomingEnvelopeCandidates(
        List<Budget> activeBudgets,
        boolean noSpendablePrimary
    ) {
        LocalDateTime now = LocalDateTime.now(LAGOS_ZONE);
        List<ActionCandidate> candidates = new ArrayList<>();

        for (Budget budget : activeBudgets) {
            if (budget.getEnvelopes() == null) {
                continue;
            }
            for (Envelope envelope : budget.getEnvelopes()) {
                LocalDateTime nextDisbursementAt = envelope.getNextDisbursementAt();
                if (nextDisbursementAt == null || !nextDisbursementAt.isAfter(now) || !hasFutureValue(envelope)) {
                    continue;
                }
                // Hard gate: only surface disbursements within the next 3 hours — skip everything further away
                if (Duration.between(now, nextDisbursementAt).compareTo(IMMINENT_DISBURSEMENT_WINDOW) > 0) {
                    continue;
                }

                BigDecimal upcomingAmount = resolveUpcomingAmount(envelope);
                candidates.add(buildUpcomingEnvelopeCandidate(
                    budget,
                    envelope,
                    upcomingAmount,
                    now,
                    noSpendablePrimary
                ));
            }
        }

        candidates.sort(Comparator.comparingDouble(ActionCandidate::score).reversed());
        int limit = noSpendablePrimary ? 1 : 2;
        return candidates.size() > limit ? candidates.subList(0, limit) : candidates;
    }

    private ActionCandidate buildUpcomingEnvelopeCandidate(
        Budget budget,
        Envelope envelope,
        BigDecimal upcomingAmount,
        LocalDateTime now,
        boolean noSpendablePrimary
    ) {
        LocalDateTime nextDisbursementAt = envelope.getNextDisbursementAt();
        String countdown = formatCountdown(nextDisbursementAt);
        boolean soon = isCloseToDisbursement(nextDisbursementAt, now);
        double score = scoreUpcomingEnvelope(budget, envelope, upcomingAmount, now);

        String title;
        String message;
        String priority;
        String reason;

        if (noSpendablePrimary) {
            if (soon) {
                title = envelope.getName() + " releases " + countdown;
                message = "Nothing is spendable yet, but " + envelope.getName()
                    + " releases " + countdown + ". Hold steady; your naira is warming up.";
                priority = "high";
                reason = "No envelope has reached disbursement time yet; this is the closest upcoming release.";
                score = Math.max(score, 760);
            } else {
                title = "No envelope is ready yet";
                message = "No disbursement has reached yet. Next up: " + envelope.getName()
                    + " " + countdown + ". Your budget is doing disciplined things.";
                priority = "normal";
                reason = "No envelope has reached disbursement time yet; this is the next scheduled release.";
                score = Math.max(score, 560);
            }
        } else if (soon) {
            title = envelope.getName() + " releases soon";
            message = envelope.getName() + " unlocks " + countdown
                + ". Hold steady; your naira is almost ready for duty.";
            priority = "normal";
            reason = "This envelope is close to its next scheduled disbursement time.";
            score += 80;
        } else {
            title = envelope.getName() + " unlocks next";
            message = "Next release: " + envelope.getName() + " " + countdown
                + ". Your plan is quietly doing the heavy lifting.";
            priority = "normal";
            reason = "This is an upcoming envelope that will unlock usable money next.";
        }

        ActionCandidate candidate = baseCandidate(
            title,
            message,
            "Go to " + envelope.getName(),
            "review_active_budget",
            priority,
            reason,
            score
        );
        candidate.budgetId = budget.getId();
        candidate.budgetName = budget.getName();
        candidate.envelopeId = envelope.getId();
        candidate.envelopeName = envelope.getName();
        candidate.amountValue = upcomingAmount != null ? upcomingAmount.doubleValue() : null;
        candidate.nextAvailableAt = formatNextAvailableAt(nextDisbursementAt);
        candidate.countdownText = countdown;
        return candidate;
    }

    private AiDashboardNextActionResponse sanitizeResponse(
        AiDashboardNextActionResponse response,
        DashboardActionContext context,
        AiDashboardNextActionResponse deterministic
    ) {
        if (response == null || !isAllowedActionType(response.getActionType())) {
            logger.warn(
                "AiInsightService: Gemini dashboard action rejected due to unsupported actionType={}",
                response != null ? response.getActionType() : null
            );
            return deterministic != null ? deterministic : buildFallback(context);
        }

        ActionCandidate selectedCandidate = findMatchingCandidate(response, context.candidates);
        if (selectedCandidate == null) {
            logger.warn(
                "AiInsightService: Gemini dashboard action rejected because no candidate matched actionType={}, budgetId={}, envelopeId={}",
                response.getActionType(),
                response.getBudgetId(),
                response.getEnvelopeId()
            );
            return deterministic != null ? deterministic : buildFallback(context);
        }

        AiDashboardNextActionResponse sanitized = mergeWithCandidate(response, selectedCandidate, "ai");
        sanitized.setAlternatives(resolveAlternatives(response.getAlternatives(), context.candidates, selectedCandidate));
        return sanitized;
    }

    private ActionCandidate findMatchingCandidate(
        AiDashboardNextActionResponse response,
        List<ActionCandidate> candidates
    ) {
        for (ActionCandidate candidate : candidates) {
            if (!candidate.actionType.equals(response.getActionType())) {
                continue;
            }
            if (candidate.envelopeId != null && response.getEnvelopeId() != null
                && candidate.envelopeId.equals(response.getEnvelopeId())) {
                return candidate;
            }
            if (candidate.budgetId != null && response.getBudgetId() != null
                && candidate.budgetId.equals(response.getBudgetId())) {
                return candidate;
            }
        }

        for (ActionCandidate candidate : candidates) {
            if (candidate.actionType.equals(response.getActionType())) {
                return candidate;
            }
        }
        return null;
    }

    private AiDashboardNextActionResponse mergeWithCandidate(
        AiDashboardNextActionResponse response,
        ActionCandidate candidate,
        String source
    ) {
        AiDashboardNextActionResponse merged = new AiDashboardNextActionResponse();
        merged.setTitle(valueOrDefault(response.getTitle(), candidate.title));
        merged.setMessage(valueOrDefault(response.getMessage(), candidate.message));
        merged.setCtaLabel(valueOrDefault(response.getCtaLabel(), candidate.ctaLabel));
        merged.setActionType(candidate.actionType);
        merged.setPriority(isAllowedPriority(response.getPriority()) ? response.getPriority() : candidate.priority);
        merged.setReason(valueOrDefault(response.getReason(), candidate.reason));
        merged.setSource(source);
        merged.setConfidence(normalizeConfidence(response.getConfidence(), source.equals("ai") ? 0.82 : 0.93));
        merged.setBudgetId(candidate.budgetId);
        merged.setBudgetName(candidate.budgetName);
        merged.setEnvelopeId(candidate.envelopeId);
        merged.setEnvelopeName(candidate.envelopeName);
        merged.setAmountValue(candidate.amountValue);
        merged.setNextAvailableAt(candidate.nextAvailableAt);
        merged.setCountdownText(candidate.countdownText);
        // Preserve AI-generated variants; clear them for server-ranked/fallback paths
        // (the "ai" source check ensures only Gemini responses carry variants through)
        if ("ai".equals(source) && response.getVariants() != null && !response.getVariants().isEmpty()) {
            merged.setVariants(response.getVariants());
        }
        return merged;
    }

    private List<AiDashboardNextActionResponse.AlternativeAction> resolveAlternatives(
        List<AiDashboardNextActionResponse.AlternativeAction> requested,
        List<ActionCandidate> candidates,
        ActionCandidate selectedCandidate
    ) {
        List<AiDashboardNextActionResponse.AlternativeAction> alternatives = new ArrayList<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        usedKeys.add(selectedCandidate.uniqueKey());

        if (requested != null) {
            for (AiDashboardNextActionResponse.AlternativeAction item : requested) {
                ActionCandidate matched = findMatchingCandidate(item, candidates);
                if (matched == null || usedKeys.contains(matched.uniqueKey())) {
                    continue;
                }
                alternatives.add(toAlternative(matched, valueOrDefault(item.getReason(), matched.reason)));
                usedKeys.add(matched.uniqueKey());
                if (alternatives.size() >= 2) {
                    return alternatives;
                }
            }
        }

        for (ActionCandidate candidate : candidates) {
            if (usedKeys.contains(candidate.uniqueKey())) {
                continue;
            }
            alternatives.add(toAlternative(candidate, candidate.reason));
            usedKeys.add(candidate.uniqueKey());
            if (alternatives.size() >= 2) {
                break;
            }
        }

        return alternatives;
    }

    private ActionCandidate findMatchingCandidate(
        AiDashboardNextActionResponse.AlternativeAction response,
        List<ActionCandidate> candidates
    ) {
        if (response == null || response.getActionType() == null) {
            return null;
        }

        for (ActionCandidate candidate : candidates) {
            if (!candidate.actionType.equals(response.getActionType())) {
                continue;
            }
            if (candidate.envelopeId != null && response.getEnvelopeId() != null
                && candidate.envelopeId.equals(response.getEnvelopeId())) {
                return candidate;
            }
            if (candidate.budgetId != null && response.getBudgetId() != null
                && candidate.budgetId.equals(response.getBudgetId())) {
                return candidate;
            }
        }

        for (ActionCandidate candidate : candidates) {
            if (candidate.actionType.equals(response.getActionType())) {
                return candidate;
            }
        }
        return null;
    }

    private AiDashboardNextActionResponse.AlternativeAction toAlternative(ActionCandidate candidate, String reason) {
        AiDashboardNextActionResponse.AlternativeAction alternative = new AiDashboardNextActionResponse.AlternativeAction();
        alternative.setTitle(candidate.title);
        alternative.setActionType(candidate.actionType);
        alternative.setCtaLabel(candidate.ctaLabel);
        alternative.setReason(reason);
        alternative.setBudgetId(candidate.budgetId);
        alternative.setBudgetName(candidate.budgetName);
        alternative.setEnvelopeId(candidate.envelopeId);
        alternative.setEnvelopeName(candidate.envelopeName);
        return alternative;
    }

    private AiDashboardNextActionResponse buildDeterministicRecommendation(DashboardActionContext context) {
        if (context.candidates.isEmpty()) {
            return null;
        }

        ActionCandidate selected = context.candidates.get(0);
        AiDashboardNextActionResponse response = mergeWithCandidate(new AiDashboardNextActionResponse(), selected, "server_ranked");

        List<AiDashboardNextActionResponse.AlternativeAction> alternatives = new ArrayList<>();
        for (int i = 1; i < context.candidates.size() && alternatives.size() < 2; i++) {
            ActionCandidate candidate = context.candidates.get(i);
            alternatives.add(toAlternative(candidate, candidate.reason));
        }
        response.setAlternatives(alternatives);
        return response;
    }

    private AiDashboardNextActionResponse buildFallback(DashboardActionContext context) {
        AiDashboardNextActionResponse response = new AiDashboardNextActionResponse();
        boolean hasName = context.firstName != null && !context.firstName.isBlank()
                && !"there".equals(context.firstName);
        response.setTitle(hasName
                ? context.firstName + ", let's set your money in motion 📅"
                : "New cycle, new plan — let's go 📅");
        response.setMessage("Set up a fresh budget so your money has direction this month — not just vibes.");
        response.setCtaLabel("Create budget");
        response.setActionType("create_budget");
        response.setPriority("high");
        response.setReason("No stronger live dashboard action was available.");
        response.setSource("fallback");
        response.setConfidence(0.61);
        return response;
    }

    private ActionCandidate baseCandidate(
        String title,
        String message,
        String ctaLabel,
        String actionType,
        String priority,
        String reason,
        double score
    ) {
        ActionCandidate candidate = new ActionCandidate();
        candidate.title = title;
        candidate.message = message;
        candidate.ctaLabel = ctaLabel;
        candidate.actionType = actionType;
        candidate.priority = priority;
        candidate.reason = reason;
        candidate.score = score;
        return candidate;
    }

    private List<Map<String, Object>> buildCandidatePayloads(List<ActionCandidate> candidates) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (ActionCandidate candidate : candidates) {
            Map<String, Object> item = new HashMap<>();
            item.put("title", candidate.title);
            item.put("message", candidate.message);
            item.put("ctaLabel", candidate.ctaLabel);
            item.put("actionType", candidate.actionType);
            item.put("priority", candidate.priority);
            item.put("reason", candidate.reason);
            item.put("score", candidate.score);
            item.put("budgetId", candidate.budgetId);
            item.put("budgetName", candidate.budgetName);
            item.put("envelopeId", candidate.envelopeId);
            item.put("envelopeName", candidate.envelopeName);
            item.put("amountValue", candidate.amountValue);
            item.put("nextAvailableAt", candidate.nextAvailableAt);
            item.put("countdownText", candidate.countdownText);
            payloads.add(item);
        }
        return payloads;
    }

    private boolean isEnvelopeSpendable(Envelope envelope) {
        return envelope != null
            && envelope.getRemainingAmount() != null
            && envelope.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0
            && !Boolean.TRUE.equals(envelope.getHasMatured());
    }

    private boolean hasHealthyRemaining(Envelope envelope) {
        BigDecimal available = resolveSpendableAmount(envelope);
        BigDecimal reference = resolveReferenceAmount(envelope);
        if (available == null || available.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (reference == null || reference.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        return available.divide(reference, 6, RoundingMode.HALF_UP).compareTo(new BigDecimal("0.01")) > 0;
    }

    private boolean hasFutureValue(Envelope envelope) {
        if (envelope == null) {
            return false;
        }

        BigDecimal totalRemaining = envelope.getTotalRemainingAmount();
        BigDecimal amount = envelope.getAmount();
        BigDecimal remaining = envelope.getRemainingAmount();

        return (totalRemaining != null && totalRemaining.compareTo(BigDecimal.ZERO) > 0)
            || (amount != null && amount.compareTo(BigDecimal.ZERO) > 0)
            || (remaining != null && remaining.compareTo(BigDecimal.ZERO) > 0);
    }

    private BigDecimal resolveSpendableAmount(Envelope envelope) {
        if (envelope == null) {
            return BigDecimal.ZERO;
        }
        if (envelope.getRemainingAmount() != null && envelope.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
            return envelope.getRemainingAmount();
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal resolveReferenceAmount(Envelope envelope) {
        if (envelope == null) {
            return null;
        }

        if (envelope.getConditions() != null) {
            Object limit = envelope.getConditions().get("limit");
            BigDecimal parsedLimit = parseBigDecimal(limit);
            if (parsedLimit != null && parsedLimit.compareTo(BigDecimal.ZERO) > 0) {
                return parsedLimit;
            }
        }

        if (envelope.getAmount() != null && envelope.getAmount().compareTo(BigDecimal.ZERO) > 0) {
            return envelope.getAmount();
        }
        return envelope.getTotalRemainingAmount();
    }

    private BigDecimal resolveUpcomingAmount(Envelope envelope) {
        if (envelope == null) {
            return null;
        }

        if (envelope.getConditions() != null) {
            Object limit = envelope.getConditions().get("limit");
            BigDecimal parsedLimit = parseBigDecimal(limit);
            if (parsedLimit != null && parsedLimit.compareTo(BigDecimal.ZERO) > 0) {
                return parsedLimit;
            }
        }

        if (envelope.getRemainingAmount() != null && envelope.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
            return envelope.getRemainingAmount();
        }

        return envelope.getAmount();
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value != null) {
            try {
                return new BigDecimal(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double scoreSpendableEnvelope(
        Budget budget,
        Envelope envelope,
        LocalDateTime now,
        BigDecimal availableAmount
    ) {
        double score = 0;
        BigDecimal reference = resolveReferenceAmount(envelope);
        if (reference != null && reference.compareTo(BigDecimal.ZERO) > 0) {
            double ratio = availableAmount.divide(reference, 6, RoundingMode.HALF_UP).doubleValue();
            score += Math.min(ratio, 1.0) * 40;
        }
        score += availableAmount.doubleValue() * 0.00002;

        if (envelope.getLastDisbursedAt() != null) {
            long hoursAgo = Math.max(0, Duration.between(envelope.getLastDisbursedAt(), now).toHours());
            double recency = hoursAgo >= 168 ? 0 : 1 - (hoursAgo / 168.0);
            score += recency * 16;
        }

        if (budget.getCreatedAt() != null && Duration.between(budget.getCreatedAt(), now).toDays() <= 30) {
            score += 8;
        }

        return score;
    }

    private double scoreUpcomingEnvelope(
        Budget budget,
        Envelope envelope,
        BigDecimal upcomingAmount,
        LocalDateTime now
    ) {
        LocalDateTime next = envelope.getNextDisbursementAt();
        if (next == null) {
            return -999999;
        }

        long minutesAway = Math.max(0, Duration.between(now, next).toMinutes());
        double score = 500 - (minutesAway / 10.0);
        if (upcomingAmount != null) {
            score += upcomingAmount.doubleValue() * 0.00001;
        }
        if (budget.getCreatedAt() != null && Duration.between(budget.getCreatedAt(), now).toDays() <= 30) {
            score += 6;
        }
        return score;
    }

    /** Returns a human-readable availability text for an envelope, e.g. "locked until in 2d 4h (Mon, 8 Jun - 8:00 am)" or "available NOW". */
    private String buildDisbursementText(Envelope envelope, LocalDateTime now) {
        LocalDateTime next = envelope.getNextDisbursementAt();
        if (next == null) return null;
        if (!next.isAfter(now)) return "available NOW";
        Duration d = Duration.between(now, next);
        long days    = d.toDays();
        long hours   = d.toHours() % 24;
        long minutes = d.toMinutes() % 60;
        StringBuilder sb = new StringBuilder("in ");
        if (days > 0)    sb.append(days).append("d ");
        if (hours > 0)   sb.append(hours).append("h ");
        if (minutes > 0 || (days == 0 && hours == 0)) sb.append(minutes).append("m");
        String countdown = sb.toString().trim() + " (" + formatNextAvailableAt(next) + ")";
        return isEnvelopeSpendable(envelope)
                ? "next release " + countdown
                : "locked until " + countdown;
    }

    private String formatNextAvailableAt(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "soon";
        }
        return dateTime.atZone(LAGOS_ZONE).format(NEXT_AVAILABLE_FORMATTER);
    }

    private String formatCountdown(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "soon";
        }

        Duration duration = Duration.between(LocalDateTime.now(LAGOS_ZONE), dateTime);
        if (duration.isNegative() || duration.isZero()) {
            return "right now";
        }

        long totalMinutes = duration.toMinutes();
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes % (60 * 24)) / 60;
        long minutes = totalMinutes % 60;

        StringBuilder builder = new StringBuilder("in ");
        if (days > 0) {
            builder.append(days).append("d ");
        }
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0 || (days == 0 && hours == 0)) {
            builder.append(minutes).append("m");
        }

        return builder.toString().trim();
    }

    private String formatMoney(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String cleanJson(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "{}";
        }

        // 1. Strip ALL markdown code fences (```json, ```JSON, ``` alone)
        String cleaned = rawText.trim()
                .replaceAll("(?i)```json", "")
                .replaceAll("```", "")
                .trim();

        // 2. Extract just the JSON object — find the first { and matching last }
        //    This handles cases where Gemini adds introductory text before the JSON
        //    (e.g. "Here is the MONNIE card:\n\n{...}") which previously caused
        //    objectMapper.readValue to fail and forced the deterministic fallback.
        int start = cleaned.indexOf('{');
        int end   = cleaned.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        // Couldn't find a JSON object — return empty so the caller falls back cleanly
        logger.warn("[Monnie] cleanJson: no JSON object found in Gemini response (length={})", rawText.length());
        return "{}";
    }

    private boolean isAllowedActionType(String value) {
        if (value == null) {
            return false;
        }
        return switch (value) {
            case "create_budget", "review_active_budget", "fund_wallet", "set_account", "open_notifications" -> true;
            default -> false;
        };
    }

    private boolean isAllowedPriority(String value) {
        if (value == null) {
            return false;
        }
        return switch (value) {
            case "normal", "high", "urgent" -> true;
            default -> false;
        };
    }

    private boolean hasDisbursementReached(Envelope envelope, LocalDateTime now) {
        return envelope != null
            && envelope.getNextDisbursementAt() != null
            && !envelope.getNextDisbursementAt().isAfter(now);
    }

    private boolean wasRecentlyReleased(Envelope envelope, LocalDateTime now) {
        if (envelope == null || envelope.getLastDisbursedAt() == null) {
            return false;
        }

        if (envelope.getCreatedAt() != null) {
            long minutesAfterCreation = Math.abs(Duration.between(
                envelope.getCreatedAt(),
                envelope.getLastDisbursedAt()
            ).toMinutes());
            if (minutesAfterCreation < 2) {
                return false;
            }
        }

        Duration sinceRelease = Duration.between(envelope.getLastDisbursedAt(), now);
        return !sinceRelease.isNegative() && sinceRelease.compareTo(RECENT_RELEASE_WINDOW) <= 0;
    }

    private boolean isCloseToDisbursement(LocalDateTime nextDisbursementAt, LocalDateTime now) {
        if (nextDisbursementAt == null || !nextDisbursementAt.isAfter(now)) {
            return false;
        }

        Duration untilRelease = Duration.between(now, nextDisbursementAt);
        return untilRelease.compareTo(SOON_DISBURSEMENT_WINDOW) <= 0;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Double normalizeConfidence(Double value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value < 0) {
            return 0.0;
        }
        if (value > 1) {
            return 1.0;
        }
        return value;
    }

    private static class DashboardActionContext {
        // ── Identity ──────────────────────────────────────────────────────────
        private String userName   = "there";
        private String firstName  = "there";
        private String gender     = "unknown";   // "male" | "female" | "other" | "unknown"
        private String occupation  = "";
        private String mainExpense = "";   // user's stated biggest spending category
        private String savingsGoal = "";   // user's stated savings goal
        // ── Demographics ──────────────────────────────────────────────────────
        /** Computed age in years. -1 = not available (DOB not on profile). */
        private int    age              = -1;
        private String ageGroup         = "unknown";
        /** Nigerian-context life-stage description fed directly to Gemini. */
        private String demographicContext = "Age unknown — use a universally warm, professional tone.";

        // ── Time / day ────────────────────────────────────────────────────────
        private String currentTimeFormatted = "";  // "5pm", "9:30am"
        private String dayOfWeek            = "";  // "Monday", "Friday" …

        // ── PSP ───────────────────────────────────────────────────────────────
        private boolean isPspRubies = false;

        // ── Wallet & budget ───────────────────────────────────────────────────
        private BigDecimal walletBalance          = BigDecimal.ZERO;
        private boolean    hasLinkedSettlementAccount = false;
        private boolean    hasBudgetHistory        = false;
        private Budget     activeBudget            = null;
        private Budget     completedBudget         = null;
        private List<Budget> activeBudgets         = List.of();
        private List<ActionCandidate> candidates   = List.of();
        private int activeBudgetCount              = 0;
        /** -1 = no active budget; 0 = ends today; N = ends in N days */
        private int daysUntilActiveBudgetEnds      = -1;
        /** How far through the budget period we are, by days elapsed (0-100). -1 = no budget */
        private int budgetPctElapsed               = -1;
        /** Names of all currently active budgets */
        private List<String> activeBudgetNames     = List.of();

        /** One line per savings pot (name, balance, target, status, maturity + days),
         *  or "none". Lets Monnie speak truthfully about savings instead of guessing. */
        private String savingsSnapshot = "none";

        // ── Full envelope snapshot ────────────────────────────────────────────
        /** Every envelope across all active budgets — gives Gemini specific ₦ figures */
        private List<EnvelopeSummaryDto> allEnvelopes = List.of();

        // ── Envelope health ───────────────────────────────────────────────────
        private List<String> envelopesNearLimit = List.of();
        private boolean hasBudgetDrift          = false;
        private String  driftingEnvelopeName    = "";

        // ── Transfer pattern ──────────────────────────────────────────────────
        private boolean isUsualTransferTime  = false;
        private String  transferPatternDesc  = "";  // e.g. "Friday evenings"
    }

    /** Lightweight envelope snapshot passed to Gemini so it can reference exact ₦ figures and disbursement timing. */
    private static class EnvelopeSummaryDto {
        final String name;
        final String budgetName;
        // Period view — today's spendable pocket vs period limit
        final double periodRemaining;
        final double periodLimit;
        final int    periodPctLeft;
        // Vault view — total_remaining_amount vs initial_amount (overall budget health)
        final double vaultRemaining;
        final double vaultInitial;
        final int    vaultPctRemaining;
        /** Human-readable availability text, e.g. "locked until in 2d 4h (Mon, 8 Jun - 8:00 am)" or "available NOW". Null if none. */
        final String nextDisbursementText;

        EnvelopeSummaryDto(String name, String budgetName,
                           double periodRemaining, double periodLimit,
                           double vaultRemaining, double vaultInitial,
                           String nextDisbursementText) {
            this.name               = name;
            this.budgetName         = budgetName;
            this.periodRemaining    = periodRemaining;
            this.periodLimit        = periodLimit;
            this.periodPctLeft      = periodLimit > 0
                    ? (int) Math.round((periodRemaining / periodLimit) * 100.0)
                    : 100;
            this.vaultRemaining     = vaultRemaining;
            this.vaultInitial       = vaultInitial;
            this.vaultPctRemaining  = vaultInitial > 0
                    ? (int) Math.round((vaultRemaining / vaultInitial) * 100.0)
                    : 100;
            this.nextDisbursementText = nextDisbursementText;
        }

        @Override
        public String toString() {
            String periodStr = periodLimit > 0
                    ? String.format("period ₦%.0f/₦%.0f (%d%% left today)", periodRemaining, periodLimit, periodPctLeft)
                    : String.format("period ₦%.0f available", periodRemaining);

            String vaultFlag = vaultPctRemaining <= 10  ? " ⚠️ CRITICALLY LOW"
                             : vaultPctRemaining <= 25  ? " ⚠️ LOW"
                             : vaultPctRemaining <= 50  ? " (getting low)"
                             : "";
            String vaultStr = String.format("vault ₦%.0f/₦%.0f (%d%% intact%s)",
                    vaultRemaining, vaultInitial, vaultPctRemaining, vaultFlag);

            String disbText = nextDisbursementText != null && !nextDisbursementText.isBlank()
                    ? " | next disbursement: " + nextDisbursementText
                    : "";

            return String.format("  • %s [%s] — %s | %s%s",
                    name, budgetName, periodStr, vaultStr, disbText);
        }
    }

    private static class ActionCandidate {
        private String title;
        private String message;
        private String ctaLabel;
        private String actionType;
        private String priority;
        private String reason;
        private Long budgetId;
        private String budgetName;
        private Long envelopeId;
        private String envelopeName;
        private Double amountValue;
        private String nextAvailableAt;
        private String countdownText;
        private double score;

        private String uniqueKey() {
            return actionType + "::" + (budgetId == null ? 0 : budgetId)
                + "::" + (envelopeId == null ? 0 : envelopeId)
                + "::" + (budgetName == null ? "" : budgetName)
                + "::" + (envelopeName == null ? "" : envelopeName);
        }

        private double score() {
            return score;
        }
    }
}
