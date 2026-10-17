package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.PayeelordVasTransaction;
import com.moniewise.moniewise_backend.entity.ReconciliationRun;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.VasPayable;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.enums.VasTransactionStatus;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.WalletTransactionStatement;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.PayeelordVasTransactionRepository;
import com.moniewise.moniewise_backend.repository.ReconciliationRunRepository;
import com.moniewise.moniewise_backend.repository.RevenueLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.VasPayableRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Classified;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.ExpectedMovement;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Line;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Lookup;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Outcome;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Purchase;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Report;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the Wisemonie revenue-account statement and matches each line to the
 * books. The scheduled run stores a report. It does not replace the revenue
 * wallet balance with the Rubies balance.
 *
 * <p>Healing is off unless {@code moniewise.reconciliation.revenue-statement.heal-enabled}
 * is true. A healed line must fall on or after the cutoff, and the reference
 * and amount must match one known pattern. An airtime credit books only the
 * margin. An opening-fee debit books only when the statement names
 * {@code WALLET-OPEN-} or {@code KYC-BVN-} and the amount is the configured fee.
 */
@Service
public class RevenueStatementService {

    private static final Logger logger = LoggerFactory.getLogger(RevenueStatementService.class);
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final int PAGE_SIZE = 100;
    private static final int REF_CAP = 25;
    private static final Set<TransactionType> REVENUE_ACCOUNT_TYPES = Set.of(
            TransactionType.VAS_PAYMENT_COLLECTION,
            TransactionType.BUDGET_FEE_COLLECTION,
            TransactionType.MARKUP_FEE_COLLECTION,
            TransactionType.WALLET_OPEN_FEE,
            TransactionType.KYC_BVN_FEE);

    private final WalletRepository walletRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final RevenueLogRepository revenueLogRepository;
    private final VasPayableRepository vasPayableRepository;
    private final PayeelordVasTransactionRepository payeelordVasTransactionRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final PaymentGatewayResolver paymentGatewayResolver;
    private final SystemConfigService systemConfigService;
    private final RevenueClassificationService revenueClassificationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final boolean healEnabled;
    private final LocalDate healAfter;
    private final int lookbackDays;
    private final int overlapDays;
    private final BigDecimal openingFee;

    public RevenueStatementService(
            WalletRepository walletRepository,
            TransactionLogRepository transactionLogRepository,
            RevenueLogRepository revenueLogRepository,
            VasPayableRepository vasPayableRepository,
            PayeelordVasTransactionRepository payeelordVasTransactionRepository,
            ReconciliationRunRepository reconciliationRunRepository,
            PaymentGatewayResolver paymentGatewayResolver,
            SystemConfigService systemConfigService,
            RevenueClassificationService revenueClassificationService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${moniewise.reconciliation.revenue-statement.heal-enabled:false}") boolean healEnabled,
            @Value("${moniewise.reconciliation.revenue-statement.heal-after:2026-10-18}") String healAfter,
            @Value("${moniewise.reconciliation.revenue-statement.lookback-days:14}") int lookbackDays,
            @Value("${moniewise.reconciliation.revenue-statement.overlap-days:1}") int overlapDays,
            @Value("${moniewise.wallet.opening-fee:${moniewise.kyc.bvn-fee:70}}") BigDecimal openingFee) {
        this.walletRepository = walletRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.vasPayableRepository = vasPayableRepository;
        this.payeelordVasTransactionRepository = payeelordVasTransactionRepository;
        this.reconciliationRunRepository = reconciliationRunRepository;
        this.paymentGatewayResolver = paymentGatewayResolver;
        this.systemConfigService = systemConfigService;
        this.revenueClassificationService = revenueClassificationService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.healEnabled = healEnabled;
        this.healAfter = parseHealAfter(healAfter);
        this.lookbackDays = Math.max(1, lookbackDays);
        this.overlapDays = Math.max(0, Math.min(overlapDays, 14));
        this.openingFee = openingFee == null ? new BigDecimal("70") : openingFee;
    }

    /**
     * Admin preview. Does not heal, does not store a run, and does not move the cursor.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> runReport() {
        return run(LocalDate.now(LAGOS), false, false);
    }

    /**
     * Scheduled and admin run. Healing follows the configuration flag.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> runScheduled() {
        return run(LocalDate.now(LAGOS), true, healEnabled);
    }

    Map<String, Object> run(LocalDate today, boolean persist, boolean heal) {
        LocalDateTime started = LocalDateTime.now();
        Wallet revenue = walletRepository.findByRevenueWalletTrue().orElse(null);
        if (revenue == null) {
            return finish(persist, started, baseReport("NO_WALLET", today, today, null), null);
        }
        String searchItem = firstNonBlank(
                revenue.getProviderWalletRef(),
                revenue.getAccountNumber(),
                systemConfigService.getString(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER));
        if (searchItem == null) {
            return finish(persist, started, baseReport("NO_PROVIDER_REF", today, today, null), null);
        }

        LocalDate cursor = readCursor();
        LocalDate windowEnd = today;
        LocalDate windowStart = cursor == null
                ? windowEnd.minusDays(lookbackDays)
                : cursor.minusDays(overlapDays);
        if (windowStart.isAfter(windowEnd)) {
            windowStart = windowEnd;
        }

        PaymentGateway gateway = paymentGatewayResolver.resolveForWallet(revenue);
        Optional<WalletTransactionStatement> statement = gateway.fetchWalletTransactionStatement(
                windowStart.toString(),
                windowEnd.toString(),
                searchItem,
                1,
                PAGE_SIZE);
        if (statement.isEmpty()) {
            Map<String, Object> report = baseReport("PROVIDER_UNREACHABLE", windowStart, windowEnd, cursor);
            report.put("message", "Could not read the revenue-account statement.");
            return finish(persist, started, report, null);
        }

        List<Line> lines = new ArrayList<>();
        for (Map<String, Object> raw : statement.get().lines()) {
            lines.add(RevenueStatementMatcher.parse(raw));
        }
        BooksLookup lookup = new BooksLookup(windowStart, windowEnd);
        Report classified = RevenueStatementMatcher.classify(lines, lookup, openingFee, healAfter);

        Set<String> healedKeys = new LinkedHashSet<>();
        int healFailed = 0;
        if (heal && !statement.get().truncated()) {
            for (Classified row : classified.lines()) {
                if (!row.healable()) {
                    continue;
                }
                String key = healKey(row);
                if (!healedKeys.add(key)) {
                    continue;
                }
                try {
                    if (!applyHeal(row)) {
                        healedKeys.remove(key);
                        healFailed++;
                    }
                } catch (RuntimeException ex) {
                    healedKeys.remove(key);
                    healFailed++;
                    logger.warn("[RevenueStatement] Heal failed for {} — {}", row.outcome(), ex.getClass().getSimpleName());
                }
            }
        }

        List<Classified> forCursor = clearHealed(classified.lines(), healedKeys);
        LocalDate nextCursor = statement.get().truncated()
                ? null
                : RevenueStatementMatcher.nextCursor(windowEnd, forCursor);
        Map<String, Object> report = toReport(
                statement.get().truncated() ? "TRUNCATED" : null,
                windowStart,
                windowEnd,
                cursor,
                nextCursor,
                classified,
                forCursor,
                healedKeys,
                healFailed,
                heal);
        return finish(persist, started, report, nextCursor);
    }

    private Map<String, Object> finish(boolean persist, LocalDateTime started, Map<String, Object> report, LocalDate nextCursor) {
        report.put("persisted", persist);
        report.put("cursorAdvanced", false);
        if (!persist) {
            return report;
        }
        String json = toJson(report);
        Long runId = transactionTemplate.execute(status -> {
            ReconciliationRun run = new ReconciliationRun();
            run.setProviderName(RubiesGateway.PROVIDER_NAME);
            run.setRunType(ReconciliationRun.RUN_TYPE_REVENUE_STATEMENT);
            run.setStatus(ReconciliationRun.STATUS_COMPLETED);
            run.setStartedAt(started);
            run.setCompletedAt(LocalDateTime.now());
            run.setCreatedBy(ReconciliationRun.CREATED_BY_SYSTEM);
            run.setSummaryJson(json);
            return reconciliationRunRepository.save(run).getId();
        });
        report.put("reconciliationRunId", runId);
        if (nextCursor != null) {
            systemConfigService.set(
                    SystemConfigService.REVENUE_STATEMENT_CURSOR,
                    nextCursor.toString(),
                    "Lagos date through which the revenue-account statement was fully read.");
            report.put("cursorAdvanced", true);
            report.put("cursorAfter", nextCursor.toString());
        }
        logger.info("[RevenueStatement] status={} lines={} missingInDb={} missingOnRubies={} unmatched={} healed={}",
                report.get("status"),
                report.get("statementLines"),
                report.get("missingInDb"),
                report.get("missingOnRubies"),
                report.get("unmatched"),
                report.get("healed"));
        return report;
    }

    private boolean applyHeal(Classified row) {
        if (row.outcome() == Outcome.MISSING_VAS_BOOKING && row.purchase() != null) {
            Purchase purchase = row.purchase();
            revenueClassificationService.bookCompletedVasCollection(
                    purchase.userId(),
                    purchase.reference(),
                    purchase.selling(),
                    purchase.cost(),
                    purchase.margin());
            VasPayable after = vasPayableRepository.findByPurchaseReference(purchase.reference()).orElse(null);
            return after != null && after.isMarginCredited();
        }
        if (row.outcome() == Outcome.MISSING_OPENING_FEE && row.openingFeeUserId() != null) {
            revenueClassificationService.recordWalletOpeningFee(row.openingFeeUserId(), "");
            return transactionLogRepository.existsByReference("WALLET-OPEN-" + row.openingFeeUserId());
        }
        return false;
    }

    private Map<String, Object> toReport(
            String forcedStatus,
            LocalDate windowStart,
            LocalDate windowEnd,
            LocalDate cursorBefore,
            LocalDate cursorAfter,
            Report classified,
            List<Classified> forCursor,
            Set<String> healedKeys,
            int healFailed,
            boolean heal) {
        EnumMap<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        List<String> missingInDb = new ArrayList<>();
        int healableLeft = 0;
        for (Classified row : forCursor) {
            counts.merge(row.outcome(), 1, Integer::sum);
            if (row.healable()) {
                healableLeft++;
            }
            if (healedKeys.contains(healKey(row))) {
                continue;
            }
            if (row.outcome() == Outcome.MISSING_VAS_BOOKING || row.outcome() == Outcome.MISSING_OPENING_FEE) {
                if (row.keyReference() != null) {
                    missingInDb.add(row.keyReference());
                }
            }
        }
        int unmatched = counts.getOrDefault(Outcome.UNMATCHED, 0)
                + counts.getOrDefault(Outcome.OPENING_FEE_UNATTRIBUTED, 0)
                + counts.getOrDefault(Outcome.VAS_AMOUNT_DIFFERS, 0)
                + counts.getOrDefault(Outcome.OPENING_FEE_AMOUNT_DIFFERS, 0)
                + counts.getOrDefault(Outcome.UNPARSEABLE, 0);
        int missingOnRubies = classified.missingOnRubies().size();
        String status = forcedStatus;
        if (status == null) {
            status = (unmatched > 0 || missingInDb.size() > 0 || missingOnRubies > 0 || healFailed > 0 || healableLeft > 0)
                    ? "ATTENTION"
                    : "OK";
        }
        Map<String, Object> report = baseReport(status, windowStart, windowEnd, cursorBefore);
        report.put("cursorAfter", cursorAfter == null ? cursorBefore == null ? null : cursorBefore.toString() : cursorAfter.toString());
        report.put("statementLines", classified.lines().size());
        report.put("matched", counts.getOrDefault(Outcome.MATCHED_VAS, 0)
                + counts.getOrDefault(Outcome.MATCHED_FEE, 0)
                + counts.getOrDefault(Outcome.MATCHED_OPENING_FEE, 0)
                + counts.getOrDefault(Outcome.MATCHED_OTHER, 0));
        report.put("missingInDb", missingInDb.size());
        report.put("missingOnRubies", missingOnRubies);
        report.put("pendingNotOnStatement", classified.pendingNotOnStatement().size());
        report.put("unmatched", unmatched);
        report.put("skippedNotFinal", counts.getOrDefault(Outcome.SKIPPED_NOT_FINAL, 0));
        report.put("healed", healedKeys.size());
        report.put("healFailed", healFailed);
        report.put("healableLeft", healableLeft);
        report.put("healApplied", heal);
        report.put("missingInDbRefs", cap(missingInDb));
        report.put("missingOnRubiesRefs", cap(classified.missingOnRubies()));
        report.put("balanceRewritten", false);
        report.put("note", "Matched the revenue-account statement to the books. "
                + "A healed airtime line adds only the margin. An opening fee is booked only for a named "
                + "WALLET-OPEN or KYC-BVN line of the configured amount, on or after the cutoff. "
                + "The revenue wallet balance is never replaced with the Rubies balance.");
        return report;
    }

    private Map<String, Object> baseReport(String status, LocalDate windowStart, LocalDate windowEnd, LocalDate cursor) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "revenueStatement");
        report.put("status", status);
        report.put("healEnabled", healEnabled);
        report.put("healAfter", healAfter.toString());
        report.put("windowStart", windowStart.toString());
        report.put("windowEnd", windowEnd.toString());
        report.put("cursorBefore", cursor == null ? null : cursor.toString());
        report.put("balanceRewritten", false);
        return report;
    }

    private LocalDate readCursor() {
        String raw = systemConfigService.getString(SystemConfigService.REVENUE_STATEMENT_CURSOR, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            logger.warn("[RevenueStatement] Stored cursor is not a date. Reading the lookback window.");
            return null;
        }
    }

    private String toJson(Map<String, Object> report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException ex) {
            return "{\"status\":\"COMPLETED\",\"scope\":\"revenueStatement\"}";
        }
    }

    private static List<Classified> clearHealed(List<Classified> lines, Set<String> healedKeys) {
        List<Classified> copy = new ArrayList<>();
        for (Classified row : lines) {
            if (row.healable() && healedKeys.contains(healKey(row))) {
                copy.add(new Classified(row.line(), row.outcome(), row.keyReference(), false,
                        row.purchase(), row.openingFeeUserId()));
            } else {
                copy.add(row);
            }
        }
        return copy;
    }

    private static String healKey(Classified row) {
        if (row.outcome() == Outcome.MISSING_VAS_BOOKING && row.purchase() != null) {
            return "VAS:" + row.purchase().reference();
        }
        if (row.outcome() == Outcome.MISSING_OPENING_FEE && row.openingFeeUserId() != null) {
            return "OPEN:" + row.openingFeeUserId();
        }
        return "LINE:" + row.keyReference();
    }

    private static List<String> cap(List<String> refs) {
        if (refs.size() <= REF_CAP) {
            return List.copyOf(refs);
        }
        return List.copyOf(refs.subList(0, REF_CAP));
    }

    private static LocalDate parseHealAfter(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.of(9999, 12, 31);
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            logger.warn("[RevenueStatement] heal-after is not a date. No line will be healed.");
            return LocalDate.of(9999, 12, 31);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private final class BooksLookup implements Lookup {
        private final List<ExpectedMovement> completed;
        private final List<ExpectedMovement> pending;

        private BooksLookup(LocalDate windowStart, LocalDate windowEnd) {
            LocalDateTime from = windowStart.atStartOfDay();
            LocalDateTime to = windowEnd.plusDays(1).atStartOfDay();
            this.completed = movements(from, to, TransactionStatus.COMPLETED);
            this.pending = movements(from, to, TransactionStatus.PENDING);
        }

        private List<ExpectedMovement> movements(LocalDateTime from, LocalDateTime to, TransactionStatus status) {
            List<TransactionLog> logs = transactionLogRepository.findByTypesAndStatusBetween(
                    from, to, REVENUE_ACCOUNT_TYPES, status);
            List<ExpectedMovement> movements = new ArrayList<>();
            for (TransactionLog log : logs) {
                if (log.getReference() != null && !log.getReference().isBlank()) {
                    movements.add(new ExpectedMovement(log.getReference()));
                }
            }
            return List.copyOf(movements);
        }

        @Override
        public boolean transactionReferenceExists(String reference) {
            if (reference == null || reference.isBlank()) {
                return false;
            }
            if (transactionLogRepository.existsByReference(reference)
                    || transactionLogRepository.existsByProviderNameAndProviderReference(
                    RubiesGateway.PROVIDER_NAME, reference)) {
                return true;
            }
            String upper = reference.toUpperCase(Locale.ROOT);
            return !upper.equals(reference) && transactionLogRepository.existsByReference(upper);
        }

        @Override
        public boolean revenueMentionExists(String reference) {
            if (reference == null || reference.length() < 6) {
                return false;
            }
            return revenueLogRepository.existsByDescriptionContaining(reference);
        }

        @Override
        public Optional<Purchase> successfulPurchase(String purchaseReference) {
            String reference = RevenueStatementMatcher.stripRev(purchaseReference);
            if (reference == null || reference.isBlank()) {
                return Optional.empty();
            }
            PayeelordVasTransaction txn = payeelordVasTransactionRepository.findByReference(reference).orElse(null);
            if (txn == null || txn.getStatus() != VasTransactionStatus.SUCCESSFUL) {
                return Optional.empty();
            }
            VasPayable payable = vasPayableRepository.findByPurchaseReference(reference).orElse(null);
            boolean credited = payable != null && payable.isMarginCredited();
            return Optional.of(new Purchase(
                    reference,
                    txn.getUserId(),
                    txn.getSellingAmount(),
                    txn.getCostAmount(),
                    txn.getMarginAmount(),
                    credited));
        }

        @Override
        public boolean openingFeeRecorded(long userId) {
            return transactionLogRepository.existsByReference("WALLET-OPEN-" + userId)
                    || transactionLogRepository.existsByReferenceStartingWith("KYC-BVN-" + userId + "-");
        }

        @Override
        public List<ExpectedMovement> completedExpected() {
            return completed;
        }

        @Override
        public List<ExpectedMovement> pendingExpected() {
            return pending;
        }
    }
}
