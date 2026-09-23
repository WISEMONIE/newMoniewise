package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.FeeReserve;
import com.moniewise.moniewise_backend.entity.FeeReserveEstimate;
import com.moniewise.moniewise_backend.entity.FeeReserveTransaction;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.FeeReserveTransactionType;
import com.moniewise.moniewise_backend.repository.FeeReserveEstimateRepository;
import com.moniewise.moniewise_backend.repository.FeeReserveRepository;
import com.moniewise.moniewise_backend.repository.FeeReserveTransactionRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FeeReserveService {

    private static final Logger logger = LoggerFactory.getLogger(FeeReserveService.class);

    private final FeeReserveRepository feeReserveRepository;
    private final FeeReserveTransactionRepository feeReserveTransactionRepository;
    private final FeeReserveEstimateRepository feeReserveEstimateRepository;
    private final WalletRepository walletRepository;
    private final MarkupCalculatorService markupCalculatorService;

    public FeeReserveService(FeeReserveRepository feeReserveRepository,
                             FeeReserveTransactionRepository feeReserveTransactionRepository,
                             FeeReserveEstimateRepository feeReserveEstimateRepository,
                             WalletRepository walletRepository,
                             MarkupCalculatorService markupCalculatorService) {
        this.feeReserveRepository = feeReserveRepository;
        this.feeReserveTransactionRepository = feeReserveTransactionRepository;
        this.feeReserveEstimateRepository = feeReserveEstimateRepository;
        this.walletRepository = walletRepository;
        this.markupCalculatorService = markupCalculatorService;
    }

    // ── Estimation ─────────────────────────────────────────────────

    public record EnvelopeFeeEstimate(
            Long envelopeId,
            BigDecimal envelopeAmount,
            BigDecimal nipFee,
            BigDecimal stampDuty,
            BigDecimal markup,
            BigDecimal total
    ) {}

    public record ReserveEstimationResult(
            List<EnvelopeFeeEstimate> estimates,
            BigDecimal totalEstimatedFees,
            BigDecimal walletSurplus,
            BigDecimal reserveAmount,
            BigDecimal shortfall
    ) {}

    public ReserveEstimationResult estimateForBudget(User user, Budget budget, List<Envelope> envelopes) {
        List<EnvelopeFeeEstimate> estimates = new ArrayList<>();
        BigDecimal totalEstimated = BigDecimal.ZERO;

        for (Envelope envelope : envelopes) {
            // Skip savings sweep envelopes — they move money internally, not via NIP
            Map<String, Object> conditions = envelope.getConditions();
            if (conditions != null && "savings_sweep".equals(conditions.get("type"))) continue;

            BigDecimal amount = envelope.getInitialAmount();
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) continue;

            MarkupCalculatorService.FeeBreakdown breakdown =
                    markupCalculatorService.buildBreakdown(amount, user.getId());

            BigDecimal envelopeTotal = breakdown.bankCharge()
                    .add(breakdown.stampDuty())
                    .add(breakdown.markupFee());

            estimates.add(new EnvelopeFeeEstimate(
                    envelope.getId(), amount,
                    breakdown.bankCharge(), breakdown.stampDuty(), breakdown.markupFee(),
                    envelopeTotal
            ));
            totalEstimated = totalEstimated.add(envelopeTotal);
        }

        FeeReserve existing = feeReserveRepository.findByUserId(user.getId()).orElse(null);
        BigDecimal existingBalance = existing != null ? existing.getBalance() : BigDecimal.ZERO;

        BigDecimal additionalNeeded = totalEstimated.subtract(existingBalance).max(BigDecimal.ZERO);

        // Wallet balance is already post-deduction (BudgetService deducts allocation
        // and creation fee before calling this), so it IS the available surplus.
        BigDecimal walletAfterBudget = walletRepository.findByUserId(user.getId())
                .map(Wallet::getBalance)
                .orElse(BigDecimal.ZERO)
                .max(BigDecimal.ZERO);

        BigDecimal canFund = walletAfterBudget.min(additionalNeeded);
        BigDecimal reserveAmount = existingBalance.add(canFund);
        BigDecimal shortfall = totalEstimated.subtract(reserveAmount).max(BigDecimal.ZERO);

        return new ReserveEstimationResult(estimates, totalEstimated, walletAfterBudget, reserveAmount, shortfall);
    }

    // ── Fund reserve at budget creation ────────────────────────────

    @Transactional
    public FeeReserve fundReserveForBudget(User user, Budget budget, List<Envelope> envelopes) {
        ReserveEstimationResult estimation = estimateForBudget(user, budget, envelopes);

        if (estimation.totalEstimatedFees.compareTo(BigDecimal.ZERO) <= 0) {
            logger.info("[FeeReserve] No fees estimated for budget {} — skipping reserve.", budget.getId());
            return feeReserveRepository.findByUserId(user.getId()).orElse(null);
        }

        FeeReserve reserve = feeReserveRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    FeeReserve r = new FeeReserve();
                    r.setUser(user);
                    return r;
                });

        BigDecimal additionalFunding = estimation.reserveAmount.subtract(reserve.getBalance()).max(BigDecimal.ZERO);

        if (additionalFunding.compareTo(BigDecimal.ZERO) > 0) {
            Wallet wallet = walletRepository.findByUserIdForUpdate(user.getId())
                    .orElseThrow(() -> new IllegalStateException("Wallet not found"));

            if (wallet.getBalance().compareTo(additionalFunding) < 0) {
                additionalFunding = wallet.getBalance().max(BigDecimal.ZERO);
            }

            if (additionalFunding.compareTo(BigDecimal.ZERO) > 0) {
                wallet.setBalance(wallet.getBalance().subtract(additionalFunding));
                walletRepository.save(wallet);
            }
        }

        reserve.setBalance(reserve.getBalance().add(additionalFunding));
        reserve.setInitialAmount(reserve.getBalance());
        feeReserveRepository.save(reserve);

        // Recalculate totals from ALL active estimates (not just this budget's)
        recalculateReserveTotals(reserve);

        for (EnvelopeFeeEstimate est : estimation.estimates) {
            FeeReserveEstimate estimate = new FeeReserveEstimate();
            estimate.setFeeReserve(reserve);
            estimate.setBudgetId(budget.getId());
            estimate.setEnvelopeId(est.envelopeId());
            estimate.setEnvelopeAmount(est.envelopeAmount());
            estimate.setEstimatedNipFee(est.nipFee());
            estimate.setEstimatedStampDuty(est.stampDuty());
            estimate.setEstimatedMarkup(est.markup());
            estimate.setEstimatedTotal(est.total());
            feeReserveEstimateRepository.save(estimate);
        }

        if (additionalFunding.compareTo(BigDecimal.ZERO) > 0) {
            boolean isTopUp = reserve.getId() != null && reserve.getBalance().compareTo(additionalFunding) > 0;
            recordTransaction(reserve,
                    isTopUp ? FeeReserveTransactionType.TOP_UP : FeeReserveTransactionType.FUND,
                    additionalFunding,
                    "budget:" + budget.getId(),
                    String.format("Budget #%d creation — %d envelopes", budget.getId(), envelopes.size()));
        }

        logger.info("[FeeReserve] User {} budget {} — estimated={}, funded={}, shortfall={}",
                user.getId(), budget.getId(),
                estimation.totalEstimatedFees, additionalFunding, reserve.getShortfall());

        return reserve;
    }

    // ── Cover fees at transfer time (reserve → wallet) ──────────────

    @Transactional
    public void coverFeesFromReserve(Long userId, BigDecimal feeAmount) {
        if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) <= 0) return;

        FeeReserve reserve = feeReserveRepository.findByUserIdForUpdate(userId).orElse(null);
        if (reserve == null || reserve.getBalance().compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal covered = feeAmount.min(reserve.getBalance());
        reserve.setBalance(reserve.getBalance().subtract(covered));
        feeReserveRepository.save(reserve);

        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("Wallet not found"));
        wallet.setBalance(wallet.getBalance().add(covered));
        walletRepository.save(wallet);

        recordTransaction(reserve, FeeReserveTransactionType.DEBIT, covered,
                "fee-cover:" + System.currentTimeMillis(),
                String.format("Transfer fee covered from reserve (requested=%s, covered=%s)", feeAmount, covered));

        logger.info("[FeeReserve] User {} — covered ₦{} of ₦{} fees from reserve → wallet. Reserve remaining={}",
                userId, covered, feeAmount, reserve.getBalance());
    }

    // ── Debit reserve at transfer time ─────────────────────────────

    @Transactional
    public BigDecimal debitFeeFromReserve(Long userId, BigDecimal feeAmount, String transferReference) {
        if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        FeeReserve reserve = feeReserveRepository.findByUserIdForUpdate(userId).orElse(null);
        if (reserve == null || reserve.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal debitAmount = feeAmount.min(reserve.getBalance());
        reserve.setBalance(reserve.getBalance().subtract(debitAmount));
        feeReserveRepository.save(reserve);

        recordTransaction(reserve, FeeReserveTransactionType.DEBIT, debitAmount,
                transferReference,
                String.format("Transfer fee debit (requested=%s, covered=%s)", feeAmount, debitAmount));

        logger.info("[FeeReserve] User {} debit {} from reserve for transfer {}. Remaining={}",
                userId, debitAmount, transferReference, reserve.getBalance());

        return debitAmount;
    }

    // ── Release reserve when budget ends ───────────────────────────

    @Transactional
    public void releaseForBudget(Long userId, Long budgetId) {
        FeeReserve reserve = feeReserveRepository.findByUserId(userId).orElse(null);
        if (reserve == null) return;

        BigDecimal budgetEstimate = feeReserveEstimateRepository.sumEstimatedTotalByBudgetId(budgetId);
        feeReserveEstimateRepository.deactivateByBudgetId(budgetId);

        BigDecimal releaseAmount = budgetEstimate.min(reserve.getBalance());
        if (releaseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            recalculateReserveTotals(reserve);
            return;
        }

        reserve.setBalance(reserve.getBalance().subtract(releaseAmount));
        feeReserveRepository.save(reserve);

        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("Wallet not found"));
        wallet.setBalance(wallet.getBalance().add(releaseAmount));
        walletRepository.save(wallet);

        recordTransaction(reserve, FeeReserveTransactionType.RELEASE, releaseAmount,
                "budget:" + budgetId,
                String.format("Budget #%d ended — released to wallet", budgetId));

        recalculateReserveTotals(reserve);

        logger.info("[FeeReserve] User {} budget {} ended — released {} to wallet. Reserve remaining={}",
                userId, budgetId, releaseAmount, reserve.getBalance());
    }

    // ── Query ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FeeReserve getReserve(Long userId) {
        return feeReserveRepository.findByUserId(userId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<FeeReserveEstimate> getActiveEstimates(Long userId) {
        FeeReserve reserve = feeReserveRepository.findByUserId(userId).orElse(null);
        if (reserve == null) return List.of();
        return feeReserveEstimateRepository.findByFeeReserveIdAndActiveTrue(reserve.getId());
    }

    @Transactional(readOnly = true)
    public List<FeeReserveTransaction> getTransactionHistory(Long userId) {
        FeeReserve reserve = feeReserveRepository.findByUserId(userId).orElse(null);
        if (reserve == null) return List.of();
        return feeReserveTransactionRepository.findByFeeReserveIdOrderByCreatedAtDesc(reserve.getId());
    }

    // ── Internals ──────────────────────────────────────────────────

    private void recordTransaction(FeeReserve reserve, FeeReserveTransactionType type,
                                   BigDecimal amount, String reference, String description) {
        FeeReserveTransaction txn = new FeeReserveTransaction();
        txn.setFeeReserve(reserve);
        txn.setType(type);
        txn.setAmount(amount);
        txn.setBalanceAfter(reserve.getBalance());
        txn.setReference(reference);
        txn.setDescription(description);
        feeReserveTransactionRepository.save(txn);
    }

    private void recalculateReserveTotals(FeeReserve reserve) {
        List<FeeReserveEstimate> activeEstimates =
                feeReserveEstimateRepository.findByFeeReserveIdAndActiveTrue(reserve.getId());

        BigDecimal newTotal = activeEstimates.stream()
                .map(FeeReserveEstimate::getEstimatedTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        reserve.setEstimatedTotalFees(newTotal);
        reserve.setShortfall(newTotal.subtract(reserve.getBalance()).max(BigDecimal.ZERO));
        feeReserveRepository.save(reserve);
    }
}
