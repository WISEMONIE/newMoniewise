package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BudgetFeeBackfillService {

    private static final Logger logger = LoggerFactory.getLogger(BudgetFeeBackfillService.class);
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final BudgetRepository budgetRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final SystemConfigService systemConfigService;

    public BudgetFeeBackfillService(
            BudgetRepository budgetRepository,
            TransactionLogRepository transactionLogRepository,
            WalletRepository walletRepository,
            WalletService walletService,
            SystemConfigService systemConfigService) {
        this.budgetRepository = budgetRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.walletRepository = walletRepository;
        this.walletService = walletService;
        this.systemConfigService = systemConfigService;
    }

    public BudgetFeeBackfillResult backfillMissingRubiesBudgetFees(
            boolean dryRun,
            Integer requestedLimit,
            Long userId,
            Long budgetId,
            boolean retryFailed) {

        int limit = normalizeLimit(requestedLimit);
        boolean revenueConfigured = isRevenueAccountConfigured();
        if (!dryRun && !revenueConfigured) {
            throw new IllegalStateException(
                    "Rubies revenue account is not configured. Call /admin/rubies/register-revenue-wallet first.");
        }

        List<BudgetRepository.BudgetFeeBackfillCandidate> candidates =
                budgetRepository.findRubiesBudgetFeeBackfillCandidates(
                        RubiesGateway.PROVIDER_NAME,
                        userId,
                        budgetId,
                        PageRequest.of(0, limit));

        List<BudgetFeeBackfillItem> items = new ArrayList<>();
        int inspected = 0;
        int wouldQueue = 0;
        int queued = 0;
        int skipped = 0;
        int failed = 0;
        BigDecimal candidateFeeTotal = BigDecimal.ZERO;
        BigDecimal correctionFeeTotal = BigDecimal.ZERO;

        for (BudgetRepository.BudgetFeeBackfillCandidate candidate : candidates) {
            inspected++;

            BigDecimal feeAmount = safeAmount(candidate.getFeeAmount());
            candidateFeeTotal = candidateFeeTotal.add(feeAmount);

            if (feeAmount.compareTo(BigDecimal.ZERO) <= 0) {
                skipped++;
                items.add(item(candidate, feeAmount, null, null, null,
                        "SKIPPED", "Budget fee is zero"));
                continue;
            }

            if (isBlank(candidate.getProviderWalletRef())) {
                skipped++;
                items.add(item(candidate, feeAmount, null, null, null,
                        "SKIPPED", "User Rubies wallet reference is missing"));
                continue;
            }

            String feeReference = resolveBudgetFeeReference(candidate.getBudgetId());
            String collectionReference = "REV-" + feeReference;
            Optional<TransactionLog> existingCollection =
                    transactionLogRepository.findByReference(collectionReference);

            if (existingCollection.isPresent()) {
                TransactionLog existing = existingCollection.get();
                TransactionStatus status = existing.getStatus();
                if (status == TransactionStatus.COMPLETED) {
                    skipped++;
                    String reason = sameAmount(existing.getAmount(), feeAmount)
                            ? "Budget fee already physically collected"
                            : "Budget fee already has a completed collection log with a different amount";
                    items.add(item(candidate, feeAmount, feeReference, collectionReference, status.name(),
                            "SKIPPED", reason));
                    continue;
                }

                if (status == TransactionStatus.PENDING || status == TransactionStatus.PROCESSING) {
                    skipped++;
                    items.add(item(candidate, feeAmount, feeReference, collectionReference, status.name(),
                            "SKIPPED", "Budget fee collection is already queued"));
                    continue;
                }

                if (status == TransactionStatus.FAILED && !retryFailed) {
                    skipped++;
                    items.add(item(candidate, feeAmount, feeReference, collectionReference, status.name(),
                            "SKIPPED", "Budget fee collection failed earlier; retryFailed=false"));
                    continue;
                }
            }

            correctionFeeTotal = correctionFeeTotal.add(feeAmount);

            if (dryRun) {
                wouldQueue++;
                String action = existingCollection.isPresent() ? "WOULD_RETRY" : "WOULD_QUEUE";
                String reason = existingCollection.isPresent()
                        ? "Would retry failed budget fee collection"
                        : "Would queue missing budget fee collection";
                items.add(item(candidate, feeAmount, feeReference, collectionReference,
                        existingCollection.map(t -> t.getStatus().name()).orElse(null),
                        action, reason));
                continue;
            }

            try {
                walletService.collectRubiesBudgetCreationFeeAsync(
                        feeAmount,
                        candidate.getProviderWalletRef(),
                        walletService.resolveDisplayNameByUserId(candidate.getUserId()),
                        feeReference,
                        candidate.getUserId());
                queued++;
                items.add(item(candidate, feeAmount, feeReference, collectionReference,
                        existingCollection.map(t -> t.getStatus().name()).orElse(null),
                        existingCollection.isPresent() ? "RETRY_QUEUED" : "QUEUED",
                        "Budget fee collection queued"));
            } catch (Exception e) {
                failed++;
                logger.error("[BudgetFeeBackfill] Failed to queue budget={} user={} fee={}: {}",
                        candidate.getBudgetId(), candidate.getUserId(), feeAmount, e.getMessage());
                items.add(item(candidate, feeAmount, feeReference, collectionReference,
                        existingCollection.map(t -> t.getStatus().name()).orElse(null),
                        "FAILED", e.getMessage()));
            }
        }

        return new BudgetFeeBackfillResult(
                dryRun,
                limit,
                userId,
                budgetId,
                retryFailed,
                revenueConfigured,
                inspected,
                wouldQueue,
                queued,
                skipped,
                failed,
                candidateFeeTotal,
                correctionFeeTotal,
                items);
    }

    private String resolveBudgetFeeReference(Long budgetId) {
        return transactionLogRepository
                .findByBudgetIdAndTransactionTypeOrderByCreatedAtAsc(
                        budgetId,
                        TransactionType.BUDGET_CREATION_FEE)
                .stream()
                .map(TransactionLog::getReference)
                .filter(ref -> !isBlank(ref))
                .findFirst()
                .orElse("BUD-FEE-BACKFILL-" + budgetId);
    }

    private boolean isRevenueAccountConfigured() {
        try {
            boolean walletConfigured = walletRepository.findByRevenueWalletTrue()
                    .map(Wallet::getProviderWalletRef)
                    .filter(ref -> !isBlank(ref))
                    .isPresent();
            if (walletConfigured) {
                return true;
            }
            String configuredAccount = systemConfigService.getString(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER);
            return !isBlank(configuredAccount);
        } catch (Exception e) {
            logger.warn("[BudgetFeeBackfill] Could not verify Rubies revenue account config: {}",
                    e.getMessage());
            return false;
        }
    }

    private BudgetFeeBackfillItem item(
            BudgetRepository.BudgetFeeBackfillCandidate candidate,
            BigDecimal feeAmount,
            String feeReference,
            String collectionReference,
            String existingCollectionStatus,
            String action,
            String reason) {
        return new BudgetFeeBackfillItem(
                candidate.getBudgetId(),
                candidate.getUserId(),
                feeAmount,
                feeReference,
                collectionReference,
                existingCollectionStatus,
                action,
                reason);
    }

    private int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private BigDecimal safeAmount(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    private boolean sameAmount(BigDecimal actual, BigDecimal expected) {
        return actual != null && expected != null && actual.abs().compareTo(expected.abs()) == 0;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record BudgetFeeBackfillResult(
            boolean dryRun,
            int limit,
            Long userId,
            Long budgetId,
            boolean retryFailed,
            boolean revenueAccountConfigured,
            int inspected,
            int wouldQueue,
            int queued,
            int skipped,
            int failed,
            BigDecimal candidateFeeTotal,
            BigDecimal correctionFeeTotal,
            List<BudgetFeeBackfillItem> items) {
    }

    public record BudgetFeeBackfillItem(
            Long budgetId,
            Long userId,
            BigDecimal feeAmount,
            String feeReference,
            String collectionReference,
            String existingCollectionStatus,
            String action,
            String reason) {
    }
}
