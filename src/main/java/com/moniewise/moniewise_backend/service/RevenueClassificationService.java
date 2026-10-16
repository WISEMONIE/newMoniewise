package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.RevenueLog;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.VasPayable;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.repository.RevenueLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.VasPayableRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Splits money that lands in the Rubies revenue account into revenue (ours)
 * and PayeeLord cost (still to send back). Also records the wallet-opening
 * fee Rubies takes from that account when a new wallet is created.
 */
@Service
public class RevenueClassificationService {

    private static final Logger logger = LoggerFactory.getLogger(RevenueClassificationService.class);

    private final WalletRepository walletRepository;
    private final RevenueLogRepository revenueLogRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final VasPayableRepository vasPayableRepository;
    private final BigDecimal walletOpeningFee;

    public RevenueClassificationService(
            WalletRepository walletRepository,
            RevenueLogRepository revenueLogRepository,
            TransactionLogRepository transactionLogRepository,
            VasPayableRepository vasPayableRepository,
            @Value("${moniewise.wallet.opening-fee:${moniewise.kyc.bvn-fee:70}}") BigDecimal walletOpeningFee) {
        this.walletRepository = walletRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.vasPayableRepository = vasPayableRepository;
        this.walletOpeningFee = walletOpeningFee == null ? BigDecimal.ZERO : walletOpeningFee;
    }

    /**
     * Called only after the Rubies transfer of the selling amount into the
     * revenue account has completed. The cost becomes a PayeeLord payable.
     * The margin is added to the revenue wallet once.
     */
    @Transactional
    public void bookCompletedVasCollection(Long userId,
                                           String purchaseRef,
                                           BigDecimal sellingAmount,
                                           BigDecimal costAmount,
                                           BigDecimal marginAmount) {
        if (purchaseRef == null || purchaseRef.isBlank()) {
            return;
        }
        BigDecimal selling = moneyOrZero(sellingAmount);
        BigDecimal cost = moneyOrZero(costAmount);
        BigDecimal margin = moneyOrZero(marginAmount);
        if (selling.signum() <= 0 && cost.signum() <= 0 && margin.signum() <= 0) {
            return;
        }

        VasPayable payable = vasPayableRepository.findByPurchaseReference(purchaseRef).orElse(null);
        if (payable != null && payable.isMarginCredited()) {
            return;
        }
        if (payable == null) {
            payable = new VasPayable();
            payable.setPurchaseReference(purchaseRef);
            payable.setCollectionReference("REV-" + purchaseRef);
            payable.setUserId(userId);
            payable.setSellingAmount(selling);
            payable.setCostAmount(cost);
            payable.setMarginAmount(margin);
            payable.setMarginCredited(false);
            payable.setSwept(false);
            payable.setCreatedAt(LocalDateTime.now());
            payable = vasPayableRepository.saveAndFlush(payable);
        }

        if (margin.signum() > 0) {
            Wallet revenue = lockRevenueWallet();
            if (revenue == null) {
                logger.error("[Revenue] VAS collection {} completed but the revenue wallet is missing, "
                        + "so ₦{} markup was not added", purchaseRef, margin.toPlainString());
                return;
            }
            BigDecimal balance = revenue.getBalance() == null ? BigDecimal.ZERO : revenue.getBalance();
            revenue.setBalance(balance.add(margin));
            revenue.setUpdatedAt(LocalDateTime.now());
            walletRepository.save(revenue);

            String fragment = markupLogFragment(purchaseRef);
            if (!revenueLogRepository.existsByDescriptionContaining(fragment)) {
                RevenueLog revenueLog = new RevenueLog();
                revenueLog.setUserId(userId);
                revenueLog.setType("vas_markup");
                revenueLog.setAmount(margin);
                revenueLog.setDescription(String.format(
                        "VAS markup ref=%s sold=%s cost=%s",
                        purchaseRef, selling.toPlainString(), cost.toPlainString()));
                revenueLog.setCreatedAt(LocalDateTime.now());
                revenueLogRepository.save(revenueLog);
            }
        }

        payable.setMarginCredited(true);
        vasPayableRepository.save(payable);
        logger.info("[Revenue] Booked VAS ref={} cost={} margin={}",
                purchaseRef, cost.toPlainString(), margin.toPlainString());
    }

    /**
     * Rubies debits the revenue account when create-wallet succeeds. One row
     * per user. A BVN fee already recorded for that user is the same ₦70, so
     * it is not deducted again.
     *
     * <p>This must not join the wallet-creation transaction. Rubies has already
     * opened the account before this runs. A failure here would mark that
     * transaction rollback-only, and the wallet row would disappear while the
     * bank account stayed open.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWalletOpeningFee(Long userId, String accountNumber) {
        if (userId == null || walletOpeningFee.signum() <= 0) {
            return;
        }
        String reference = walletOpenReference(userId);
        if (transactionLogRepository.existsByReference(reference)) {
            return;
        }
        if (transactionLogRepository.existsByReferenceStartingWith(bvnFeeReferencePrefix(userId))) {
            logger.info("[Revenue] Wallet opening fee for user {} already recorded as a BVN fee", userId);
            return;
        }

        Wallet revenue = lockRevenueWallet();
        if (revenue == null || revenue.getUser() == null || revenue.getUser().getId() == null) {
            logger.error("[Revenue] Cannot record wallet opening fee for user {} — revenue wallet missing", userId);
            return;
        }
        if (userId.equals(revenue.getUser().getId())) {
            return;
        }

        BigDecimal balance = revenue.getBalance() == null ? BigDecimal.ZERO : revenue.getBalance();
        revenue.setBalance(balance.subtract(walletOpeningFee));
        revenue.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(revenue);

        TransactionLog feeLog = new TransactionLog();
        feeLog.setUserId(revenue.getUser().getId());
        feeLog.setAmount(walletOpeningFee);
        feeLog.setTransactionType(TransactionType.WALLET_OPEN_FEE);
        feeLog.setStatus(TransactionStatus.COMPLETED);
        feeLog.setCreatedAt(LocalDateTime.now());
        feeLog.setReference(reference);
        String account = accountNumber == null ? "" : accountNumber;
        feeLog.setDescription("Rubies wallet opening fee for user " + userId + " account " + account);
        transactionLogRepository.save(feeLog);
        logger.info("[Revenue] Recorded wallet opening fee ₦{} for user {} account {}",
                walletOpeningFee.toPlainString(), userId, account);
    }

    /**
     * Read-only position. {@code actualRubies} is null when the bank balance
     * was not fetched. Nothing here is written.
     */
    @Transactional(readOnly = true)
    public RevenuePosition.Result explain(BigDecimal actualRubies) {
        BigDecimal dbRevenue = walletRepository.findByRevenueWalletTrue()
                .map(wallet -> wallet.getBalance() == null ? BigDecimal.ZERO : wallet.getBalance())
                .orElse(BigDecimal.ZERO);
        BigDecimal cost = vasPayableRepository.sumOutstandingCost();
        BigDecimal margin = vasPayableRepository.sumMarginNotCredited();
        long missingCount = walletRepository.countRubiesWalletsMissingOpeningFee();
        BigDecimal missingAmount = walletOpeningFee.multiply(BigDecimal.valueOf(missingCount));
        return RevenuePosition.explain(
                dbRevenue, cost, margin, missingAmount, missingCount, actualRubies,
                RevenuePosition.DEFAULT_TOLERANCE);
    }

    static String markupLogFragment(String purchaseRef) {
        return "ref=" + purchaseRef + " ";
    }

    static String walletOpenReference(Long userId) {
        return "WALLET-OPEN-" + userId;
    }

    static String bvnFeeReferencePrefix(Long userId) {
        return "KYC-BVN-" + userId + "-";
    }

    private Wallet lockRevenueWallet() {
        Wallet revenue = walletRepository.findByRevenueWalletTrue().orElse(null);
        if (revenue == null || revenue.getUser() == null || revenue.getUser().getId() == null) {
            return revenue;
        }
        Optional<Wallet> locked = walletRepository.findByUserIdForUpdate(revenue.getUser().getId());
        if (locked == null || locked.isEmpty()) {
            return revenue;
        }
        return locked.get();
    }

    private static BigDecimal moneyOrZero(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
