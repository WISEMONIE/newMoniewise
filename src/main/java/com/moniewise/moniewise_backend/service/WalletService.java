package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.TransferFeeQuote;
import com.moniewise.moniewise_backend.dto.request.UpdateBankDetailsRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.WithdrawalQuoteResponse;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.enums.*;
import com.moniewise.moniewise_backend.exception.InsufficientFundsException;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.ProvidusExpressGateway;
import com.moniewise.moniewise_backend.entity.RevenueLog;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.RevenueLogRepository;
import com.moniewise.moniewise_backend.repository.SavingsGoalRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.repository.WithdrawalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEDUCTION;
import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEPOSIT;

@Service
public class WalletService {

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final NotificationService notificationService;
    private final ActivationJourneyNudgeService activationJourneyNudgeService;
    private final UserRepository userRepository;
    private final PaymentGatewayResolver paymentGatewayResolver;
    private final ProvidusExpressGateway providusExpressGateway;
    private final WithdrawalRepository withdrawalRepository;
    private final UserService userService;
    private final WithdrawalFeeService withdrawalFeeService;

    private final TransferFeeService transferFeeService;

    private final MarkupCalculatorService markupCalculatorService;

    private final RevenueLogRepository revenueLogRepository;

    private final SavingsGoalRepository savingsGoalRepository;
    private final EnvelopeRepository envelopeRepository;
    private final SystemConfigService systemConfigService;
    private final RedisTemplate<String, String> redisTemplate;
    private final MonnieCacheInvalidationService monnieCacheInvalidationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BANK_LIST_CACHE_PREFIX  = "bank_list:";
    private static final long   BANK_LIST_TTL_HOURS     = 24;

    /** Short-lived cache for read-heavy GET /wallets calls (dashboard load etc.).
     *  Only scalar response fields are cached — no JPA proxies, no lazy loading. */
    private static final String WALLET_CACHE_PREFIX     = "wallet:snapshot:";
    private static final long   WALLET_CACHE_TTL_SECS   = 30;
    private static final String TOTAL_HOLDINGS_PREFIX   = "total_holdings:";

    /** Caches Rubies name-enquiry results so the same (bankCode, accountNumber) pair
     *  isn't resolved twice within a few minutes — e.g. once by the client's
     *  preview call and again by processWithdrawal()'s server-side re-verification. */
    private static final String RESOLVE_ACCOUNT_PREFIX  = "resolve_account:";
    private static final long   RESOLVE_ACCOUNT_TTL_SECS = 300; // 5 minutes
    private static final String RUBIES_P2P_CREDIT_REF_PREFIX = "P2P-RB-CR-";
    private static final String RUBIES_P2P_SETTLEMENT_MARKER_PREFIX = "P2P-RB-WH-";

    @Autowired
    @Lazy
    private WalletService self;

    public WalletService(
            WalletRepository walletRepository,
            TransactionLogRepository transactionLogRepository,
            NotificationService notificationService,
            ActivationJourneyNudgeService activationJourneyNudgeService,
            UserRepository userRepository,
            PaymentGatewayResolver paymentGatewayResolver,
            ProvidusExpressGateway providusExpressGateway,
            WithdrawalRepository withdrawalRepository,
            @Lazy UserService userService,
            WithdrawalFeeService withdrawalFeeService,
            TransferFeeService transferFeeService,
            MarkupCalculatorService markupCalculatorService,
            RevenueLogRepository revenueLogRepository,
            SavingsGoalRepository savingsGoalRepository,
            EnvelopeRepository envelopeRepository,
            SystemConfigService systemConfigService,
            RedisTemplate<String, String> redisTemplate,
            MonnieCacheInvalidationService monnieCacheInvalidationService) {
        this.walletRepository = walletRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.notificationService = notificationService;
        this.activationJourneyNudgeService = activationJourneyNudgeService;
        this.userRepository = userRepository;
        this.paymentGatewayResolver = paymentGatewayResolver;
        this.providusExpressGateway = providusExpressGateway;
        this.withdrawalRepository = withdrawalRepository;
        this.userService = userService;
        this.withdrawalFeeService = withdrawalFeeService;
        this.transferFeeService = transferFeeService;
        this.markupCalculatorService = markupCalculatorService;
        this.revenueLogRepository = revenueLogRepository;
        this.savingsGoalRepository = savingsGoalRepository;
        this.envelopeRepository = envelopeRepository;
        this.systemConfigService = systemConfigService;
        this.redisTemplate = redisTemplate;
        this.monnieCacheInvalidationService = monnieCacheInvalidationService;
    }

    /**
     * Fetches the wallet for a user, with a 30-second Redis cache.
     *
     * <p>Only scalar fields (balance, currency, account details, status, provider)
     * are cached as a plain JSON map — no JPA proxies, no lazy-loading risk.
     * A full entity is reconstructed from the cached values on hit, so callers
     * that need {@code wallet.getId()} or JPA relationships should call
     * {@link #getWalletByUserIdUncached(Long)} directly (e.g. before mutations).
     */
    public Wallet getWalletByUserId(Long userId) {
        String cacheKey = WALLET_CACHE_PREFIX + userId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                Map<String, Object> snap = objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                Wallet w = new Wallet();
                Object bal = snap.get("balance");
                w.setBalance(bal != null ? new java.math.BigDecimal(bal.toString()) : java.math.BigDecimal.ZERO);
                Object cur = snap.get("currency");
                w.setCurrency(cur != null ? cur.toString() : "NGN");
                Object acct = snap.get("accountNumber");
                w.setAccountNumber(acct != null ? acct.toString() : null);
                Object acctName = snap.get("accountName");
                w.setAccountName(acctName != null ? acctName.toString() : null);
                Object bank = snap.get("bankName");
                w.setBankName(bank != null ? bank.toString() : null);
                Object st = snap.get("status");
                if (st != null) {
                    try { w.setStatus(com.moniewise.moniewise_backend.enums.WalletStatus.valueOf(st.toString())); }
                    catch (IllegalArgumentException ignored) {}
                }
                Object prov = snap.get("providerName");
                w.setProviderName(prov != null ? prov.toString() : null);
                Object provWalletRef = snap.get("providerWalletRef");
                w.setProviderWalletRef(provWalletRef != null ? provWalletRef.toString() : null);
                return w;
            }
        } catch (Exception e) {
            logger.debug("[WalletCache] Cache miss or read error for userId={}: {}", userId, e.getMessage());
        }

        Wallet wallet = getWalletByUserIdUncached(userId);

        try {
            Map<String, Object> snap = new java.util.LinkedHashMap<>();
            snap.put("balance",        wallet.getBalance() != null ? wallet.getBalance().toPlainString() : "0");
            snap.put("currency",       wallet.getCurrency() != null ? wallet.getCurrency() : "NGN");
            snap.put("accountNumber",  wallet.getAccountNumber());
            snap.put("accountName",    wallet.getAccountName());
            snap.put("bankName",       wallet.getBankName());
            snap.put("status",            wallet.getStatus() != null ? wallet.getStatus().name() : "ACTIVE");
            snap.put("providerName",      wallet.getProviderName());
            snap.put("providerWalletRef", wallet.getProviderWalletRef());
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(snap),
                    WALLET_CACHE_TTL_SECS, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("[WalletCache] Failed to write cache for userId={}: {}", userId, e.getMessage());
        }

        return wallet;
    }

    /** Direct DB fetch — use before any mutation or when you need wallet.getId(). */
    public Wallet getWalletByUserIdUncached(Long userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));
    }

    /** Evict the wallet snapshot and total holdings from Redis after balance-affecting operations. */
    public void evictWalletCache(Long userId) {
        try {
            redisTemplate.delete(WALLET_CACHE_PREFIX + userId);
            redisTemplate.delete(TOTAL_HOLDINGS_PREFIX + userId);
        } catch (Exception e) {
            logger.debug("[WalletCache] Failed to evict cache for userId={}: {}", userId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public BigDecimal getTotalHoldings(Long userId) {
        String cacheKey = TOTAL_HOLDINGS_PREFIX + userId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return new BigDecimal(cached);
            }
        } catch (Exception e) {
            logger.debug("[TotalHoldings] Cache miss for userId={}: {}", userId, e.getMessage());
        }

        BigDecimal walletBalance = walletRepository.findByUserId(userId)
                .map(w -> w.getBalance() != null ? w.getBalance() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);

        BigDecimal envelopeTotal = envelopeRepository.sumTotalRemainingByUserIdAndBudgetStatuses(
                userId, List.of(BudgetStatus.ACTIVE, BudgetStatus.SCHEDULED));
        BigDecimal savingsTotal = savingsGoalRepository.sumBalanceByUserIdAndStatus(
                userId, SavingsStatus.ACTIVE);

        BigDecimal total = walletBalance.add(envelopeTotal).add(savingsTotal);

        try {
            redisTemplate.opsForValue().set(cacheKey, total.toPlainString(),
                    WALLET_CACHE_TTL_SECS, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("[TotalHoldings] Failed to cache for userId={}: {}", userId, e.getMessage());
        }

        return total;
    }
    public Map<String, Object> getLinkedBankInfo(Long userId, String email) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));
        PaymentGateway gateway = paymentGatewayResolver.resolveForWallet(wallet);

        Map<String, Object> providerInfo = null;
        try {
            providerInfo = gateway.getWithdrawalBankInfo(email);
        } catch (RuntimeException e) {
            logger.warn("Falling back to stored settlement account for {}: {}", email, e.getMessage());
        }

        if (providerInfo != null && !providerInfo.isEmpty()) {
            String bankName = safeString(providerInfo.get("bank_name"));
            String bankCode = safeString(providerInfo.get("bank_code"));
            String accountNumber = safeString(providerInfo.get("account_number"));
            String accountName = safeString(providerInfo.get("account_name"));

            wallet.setSettlementBankName(bankName);
            wallet.setSettlementBankCode(bankCode);
            wallet.setSettlementAccountNumber(accountNumber);
            wallet.setSettlementAccountName(accountName);
            walletRepository.save(wallet);

            return providerInfo;
        }

        if (hasStoredSettlementAccount(wallet)) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("bank_name", wallet.getSettlementBankName());
            fallback.put("bank_code", wallet.getSettlementBankCode());
            fallback.put("account_number", wallet.getSettlementAccountNumber());
            fallback.put("account_name", wallet.getSettlementAccountName());
            return fallback;
        }

        return Map.of();
    }


    @PostConstruct
    @Transactional
    public void ensureRevenueWalletExists() {
        Optional<User> revenueUserOpt =
                userRepository.findByEmail("revenue@moniewise.com");

        if (revenueUserOpt.isPresent()) {
            Optional<Wallet> existingWallet =
                    walletRepository.findByUser(revenueUserOpt.get());

            if (existingWallet.isPresent()) {
                return;
            }
        }

        User revenueUser = userRepository.findByEmail("revenue@moniewise.com")
                .orElseGet(() -> {
                    logger.info("Creating System Revenue User...");
                    User sysUser = new User();
//                    sysUser.setId(revenueWalletUserId);

                    sysUser.setEmail("revenue@moniewise.com");

                    Map<String, Object> profile = new HashMap<>();
                    profile.put("name", "Wisemonie Revenue");
                    sysUser.setProfileData(profile);

                    sysUser.setPassword("SYSTEM_ACCOUNT_LOCKED");
                    return userRepository.save(sysUser);
                });

        Wallet revenueWallet = new Wallet();
        revenueWallet.setUser(revenueUser);
        revenueWallet.setBalance(BigDecimal.ZERO);
        revenueWallet.setCurrency("NGN");
        revenueWallet.setStatus(WalletStatus.ACTIVE);
        revenueWallet.setRevenueWallet(true);
        revenueWallet.setUpdatedAt(LocalDateTime.now());

        walletRepository.save(revenueWallet);
        logger.info("Created platform revenue wallet.");
    }

    public BigDecimal checkBalance(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));
        return wallet.getBalance();
    }

    public User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

    @Transactional
    public void deductBalance(Long userId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));

        if (wallet.getBalance().compareTo(amount) < 0) {
            String message = insufficientWalletOnlyBalanceMessage("complete this action", amount, wallet.getBalance());
            notificationService.sendNotification(userId.toString(), message,
                    NotificationType.INSUFFICIENT_BALANCE, null, null, "VIEW_WALLET", "/wallet");
            throw new InsufficientFundsException(message);
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);

        TransactionLog transactionLog = new TransactionLog();
        transactionLog.setUserId(userId);
        transactionLog.setBudgetId(null);
        transactionLog.setAmount(amount);
        transactionLog.setFee(BigDecimal.ZERO);
        transactionLog.setTransactionType(WALLET_DEDUCTION);
        transactionLog.setReference("W-DEC-" + System.currentTimeMillis() + "-" + userId);
        transactionLog.setStatus(TransactionStatus.COMPLETED);
        transactionLog.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(transactionLog);

        String message = String.format("₦%.2f deducted from wallet for budget creation.", amount);
        notificationService.sendNotification(
                userId.toString(),
                message,
                NotificationType.BUDGET_CREATION_FEE,
                null,
                null,
                "VIEW_ACTIVITY",
                "/activity"
        );

        logger.info("Deducted ₦{} from wallet for user {}", amount, userId);
        monnieCacheInvalidationService.evictUserAfterCommit(userId);
        evictWalletCache(userId);
    }

    /**
     * Deducts transfer fees (NIP bank charge + Wisemonie markup) from the user's
     * main wallet balance. Fees for envelope-to-external transfers are sourced from
     * the wallet so that the envelope's period limit only reflects the actual send
     * amount — not the platform charges.
     *
     * <p>Throws {@link IllegalStateException} with a user-facing breakdown message
     * when the wallet balance is insufficient; callers should surface this directly.
     */
    @Transactional
    public void deductTransferFee(Long userId, BigDecimal totalFee,
                                   BigDecimal bankCharge, BigDecimal markupFee) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user " + userId));
        BigDecimal balance = wallet.getBalance() != null ? wallet.getBalance() : BigDecimal.ZERO;
        if (balance.compareTo(totalFee) < 0) {
            throw new IllegalStateException(String.format(
                "Your wallet balance is not enough to cover the \u20A6%,.2f transfer charges " +
                "(NIP fee: \u20A6%,.2f + Service fee: \u20A6%,.2f). " +
                "Your dashboard total includes money in budgets and savings, but transfer charges " +
                "can only be paid from your wallet balance. Please top up your wallet to continue. " +
                "Wallet available: \u20A6%,.2f.",
                totalFee, bankCharge, markupFee, balance));
        }
        wallet.setBalance(balance.subtract(totalFee));
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);
        monnieCacheInvalidationService.evictUserAfterCommit(userId);
        evictWalletCache(userId);
        logger.info("[FeeDeduct] ₦{} transfer fee deducted from wallet for user {} (NIP: ₦{}, markup: ₦{})",
                totalFee, userId, bankCharge, markupFee);
    }

    /**
     * Creates a wallet-side transaction-log entry for the NIP + service fee charged
     * at envelope-to-bank transfer initiation.  This is the record that appears in
     * the user's transaction history explaining the wallet balance drop that
     * accompanies an envelope transfer.
     *
     * <p>Reference pattern: {@code WFT-{transferRef}}.  The settlement service
     * looks up this reference to flip the status to COMPLETED (success) or FAILED
     * (transfer reversed, fee already refunded by {@link #refundTransferFee}).
     *
     * <p>Call this INSIDE the provider try-block, AFTER the provider accepts the
     * transfer, so it is rolled back atomically if the provider call fails.
     */
    @Transactional
    public void logTransferFeeDebit(Long userId,
                                     BigDecimal totalFee,
                                     BigDecimal bankCharge,
                                     BigDecimal markupFee,
                                     String transferRef,
                                     BigDecimal transferAmount,
                                     String recipientName,
                                     String bankName) {
        if (totalFee == null || totalFee.compareTo(BigDecimal.ZERO) <= 0) return;
        String feeRef = "WFT-" + transferRef;
        if (transactionLogRepository.findByReference(feeRef).isPresent()) return; // idempotent

        String recipientDisplay = (recipientName != null && !recipientName.isBlank()) ? recipientName : "recipient";
        String bankDisplay      = (bankName != null && !bankName.isBlank()) ? " — " + bankName : "";

        BigDecimal safeNip    = bankCharge  != null ? bankCharge  : BigDecimal.ZERO;
        BigDecimal safeMarkup = markupFee   != null ? markupFee   : BigDecimal.ZERO;
        String breakdown;
        if (safeNip.compareTo(BigDecimal.ZERO) > 0 && safeMarkup.compareTo(BigDecimal.ZERO) > 0) {
            breakdown = String.format(" (NIP: ₦%,.2f + service: ₦%,.2f)", safeNip, safeMarkup);
        } else if (safeMarkup.compareTo(BigDecimal.ZERO) > 0) {
            breakdown = String.format(" (service: ₦%,.2f)", safeMarkup);
        } else {
            breakdown = "";
        }

        String desc = String.format(
                "Transfer fee for ₦%,.2f to %s%s%s",
                transferAmount, recipientDisplay, bankDisplay, breakdown);

        TransactionLog feeLog = TransactionLog.builder()
                .userId(userId)
                .amount(totalFee)      // positive — isOutgoing() returns true via type-switch
                .fee(BigDecimal.ZERO)
                .reference(feeRef)
                .status(TransactionStatus.PROCESSING)
                .transactionType(TransactionType.WALLET_ENVELOPE_TRANSFER_FEE)
                .externalAccountName(recipientName)
                .externalBankName(bankName)
                .description(desc)
                .createdAt(LocalDateTime.now())
                .build();
        transactionLogRepository.save(feeLog);
        logger.info("[FeeLog] Wallet fee debit log created: ref={} totalFee=₦{}", feeRef, totalFee);
    }

    /**
     * Refunds transfer fees back to the user's wallet when an external transfer fails.
     * Called by the settlement service upon confirmed Rubies failure so the user is
     * not charged fees for a transfer that never completed.
     */
    @Transactional
    public void refundTransferFee(Long userId, BigDecimal totalFee) {
        if (totalFee == null || totalFee.compareTo(BigDecimal.ZERO) <= 0) return;
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user " + userId));
        BigDecimal balance = wallet.getBalance() != null ? wallet.getBalance() : BigDecimal.ZERO;
        wallet.setBalance(balance.add(totalFee));
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);
        monnieCacheInvalidationService.evictUserAfterCommit(userId);
        evictWalletCache(userId);
        logger.info("[FeeRefund] ₦{} transfer fee refunded to wallet for user {} (transfer failed)",
                totalFee, userId);
    }

//    @Transactional
//    public Wallet createWalletForUser(User user) {
//        if (user.getId() == null) {
//            throw new IllegalStateException("User must be saved before creating wallet");
//        }
//
//        if (walletRepository.existsByUser(user)) {
//            throw new IllegalStateException("Wallet already exists");
//        }
//
//        Wallet wallet = new Wallet();
//        wallet.setUser(user);
//        wallet.setBalance(BigDecimal.ZERO);
//        wallet.setCurrency("NGN");
//        wallet.setStatus(WalletStatus.ACTIVE);
//        wallet.setUpdatedAt(LocalDateTime.now());
//
//        Map<String, String> virtualAccount = paymentProvider.createVirtualAccount(user);
//        wallet.setAccountNumber(virtualAccount.get("accountNumber"));
//        wallet.setBankName(virtualAccount.get("bank"));
//
//        return walletRepository.save(wallet);
//    }

    @Transactional
    public Wallet createWalletForUser(User user) {
        if (user.getId() == null) {
            throw new IllegalStateException("User must be saved before creating wallet");
        }

        if (walletRepository.existsByUser(user)) {
            throw new IllegalStateException("Wallet already exists");
        }

        Wallet wallet = new Wallet();
        wallet.setUser(user);
        wallet.setBalance(BigDecimal.ZERO);
        wallet.setCurrency("NGN");
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setUpdatedAt(LocalDateTime.now());
        wallet.setProviderStatus("PENDING");

        wallet = walletRepository.save(wallet);

        PaymentGateway gateway = paymentGatewayResolver.resolveDefault();
        Map<String, String> virtualAccount = gateway.createVirtualAccount(user);

        wallet.setAccountNumber(virtualAccount.get("accountNumber"));
        wallet.setAccountName(virtualAccount.get("accountName"));
        wallet.setBankName(virtualAccount.get("bank"));

        // optional provider fields: set only if returned
        wallet.setProviderName(virtualAccount.getOrDefault("providerName", gateway.getProviderName()));
        wallet.setProviderCustomerRef(virtualAccount.get("providerCustomerRef"));
        wallet.setProviderWalletRef(virtualAccount.get("providerWalletRef"));
        wallet.setMasterWalletRef(virtualAccount.get("masterWalletRef"));
        wallet.setSubWalletRef(virtualAccount.get("subWalletRef"));
        wallet.setProviderStatus("ACTIVE");
        wallet.setLastBalanceSyncAt(LocalDateTime.now());

        Map<String, Object> providerMetadata = new HashMap<>();
        if (virtualAccount.get("accountName") != null) {
            providerMetadata.put("accountName", virtualAccount.get("accountName"));
        }
        if (virtualAccount.get("bankCode") != null) {
            providerMetadata.put("bankCode", virtualAccount.get("bankCode"));
        }
        wallet.setProviderMetadata(providerMetadata);

        return walletRepository.save(wallet);
    }

    @Transactional
    public void fundWallet(Long userId, BigDecimal amount, String notificationMessage, boolean suppressLogAndNotification) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Funding amount must be positive");
        }

        if (suppressLogAndNotification) {
            Wallet wallet = walletRepository.findByUser(user)
                    .orElseGet(() -> createWalletForUser(user));

            wallet.setBalance(wallet.getBalance().add(amount));
            wallet.setUpdatedAt(LocalDateTime.now());
            wallet.setLastBalanceSyncAt(LocalDateTime.now());
            walletRepository.save(wallet);

            logger.info("Internal wallet balance-only funding applied for user {}", userId);
            monnieCacheInvalidationService.evictUserAfterCommit(userId);
        evictWalletCache(userId);
            return;
        }

        String internalRef = "INT-" + System.currentTimeMillis() + "-" + userId;

        this.processSuccessfulFunding(
                user.getEmail(),
                amount,
                amount,
                BigDecimal.ZERO,
                internalRef,
                notificationMessage != null ? notificationMessage : "Wallet Deposit",
                LocalDateTime.now()
        );

        logger.info("Internal Wallet Funding triggered for user {}", userId);
        monnieCacheInvalidationService.evictUserAfterCommit(userId);
        evictWalletCache(userId);
    }

    public void fundWallet(Long userId, BigDecimal amount, String notificationMessage) {
        fundWallet(userId, amount, notificationMessage, false);
    }

    public void fundWalletFromWebhook(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            String eventType = root.path("eventType").asText(null);
            String notificationStatus = root.path("notification_status").asText(null);

            if ("SUCCESSFUL_TRANSACTION".equalsIgnoreCase(eventType)) {
                JsonNode data = root.path("eventData");
                String email = data.path("customer").path("email").asText();
                BigDecimal grossAmount = data.path("amountPaid").decimalValue();

                TransferFeeQuote feeQuote = transferFeeService.quoteIncomingFee(
                        "SECUREWAVE",
                        TransferFeeTransferType.WALLET_DEPOSIT,
                        grossAmount
                );

                BigDecimal fee = feeQuote.getFee();
                BigDecimal netAmount = feeQuote.getRecipientReceives();

                String transactionReference = data.path("transactionReference").asText();
                String paymentDescription = data.path("paymentDescription").asText();
                LocalDateTime transactionTime = parseTransactionDate(data.path("paidOn").asText());

                this.processSuccessfulFunding(
                        email,
                        netAmount,
                        grossAmount,
                        fee,
                        transactionReference,
                        paymentDescription,
                        transactionTime
                );
                return;
            }

            if ("payment_successful".equalsIgnoreCase(notificationStatus)) {
                String email = root.path("customer").path("email").asText();
                BigDecimal grossAmount = decimalFromNode(root.path("amount"));

                TransferFeeQuote feeQuote = transferFeeService.quoteIncomingFee(
                        "SECUREWAVE",
                        TransferFeeTransferType.WALLET_DEPOSIT,
                        grossAmount
                );

                BigDecimal fee = feeQuote.getFee();
                BigDecimal netAmount = feeQuote.getRecipientReceives();

                String transactionReference = root.path("transaction_id").asText();
                String paymentDescription = String.format("Deposit of NGN %s", grossAmount);

                this.processSuccessfulFunding(
                        email,
                        netAmount,
                        grossAmount,
                        fee,
                        transactionReference,
                        paymentDescription,
                        LocalDateTime.now()
                );
            }
        } catch (Exception e) {
            logger.error("Webhook crashed", e);
            throw new RuntimeException("Webhook failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bank-list resilience — three-layer fallback so /wallets/banks NEVER
    // returns 503 in production:
    //   1. Fresh upstream call  → update stale cache, return results.
    //   2. Upstream fails/empty → serve last known-good in-memory cache.
    //   3. No stale cache (cold start with upstream down) → return the
    //      embedded static list of Nigerian banks.  Bank codes are stable;
    //      this list covers all major institutions and keeps the withdrawal
    //      flow open even during full upstream outages.
    // ─────────────────────────────────────────────────────────────────────────

    /** In-memory stale-on-error cache.  Volatile for cross-thread visibility. */
    private volatile List<Map<String, Object>> _lastKnownBanks = null;

    /** Embedded static Nigerian bank list — the last-resort fallback. */
    private static final List<Map<String, Object>> NIGERIAN_BANK_FALLBACK;
    static {
        List<Map<String, Object>> b = new ArrayList<>();
        b.add(bankEntry(1,  "Access Bank",                 "access",     "044"));
        b.add(bankEntry(2,  "Citibank Nigeria",             "citibank",   "023"));
        b.add(bankEntry(3,  "Ecobank Nigeria",              "ecobank",    "050"));
        b.add(bankEntry(4,  "First Bank of Nigeria",        "firstbank",  "011"));
        b.add(bankEntry(5,  "First City Monument Bank",     "fcmb",       "214"));
        b.add(bankEntry(6,  "Fidelity Bank",                "fidelity",   "070"));
        b.add(bankEntry(7,  "Guaranty Trust Bank",          "gtbank",     "058"));
        b.add(bankEntry(8,  "Heritage Bank",                null,         "030"));
        b.add(bankEntry(9,  "Jaiz Bank",                    null,         "301"));
        b.add(bankEntry(10, "Keystone Bank",                "keystone",   "082"));
        b.add(bankEntry(11, "Polaris Bank",                 "polaris",    "076"));
        b.add(bankEntry(12, "Stanbic IBTC Bank",            "stanbic",    "039"));
        b.add(bankEntry(13, "Sterling Bank",                "sterling",   "232"));
        b.add(bankEntry(14, "Union Bank of Nigeria",        "unionbank",  "032"));
        b.add(bankEntry(15, "United Bank for Africa",       "uba",        "033"));
        b.add(bankEntry(16, "Unity Bank",                   null,         "215"));
        b.add(bankEntry(17, "Wema Bank",                    "wema",       "035"));
        b.add(bankEntry(18, "Zenith Bank",                  "zenith",     "057"));
        b.add(bankEntry(19, "Kuda Microfinance Bank",       "kuda",       "999991"));
        b.add(bankEntry(20, "OPay",                         null,         "100004"));
        b.add(bankEntry(21, "PalmPay",                      null,         "999992"));
        b.add(bankEntry(22, "Moniepoint Microfinance Bank", "moniepoint", "50515"));
        b.add(bankEntry(23, "VFD Microfinance Bank",        "vfd",        "566"));
        b.add(bankEntry(24, "Providus Bank",                "providus",   "101"));
        b.add(bankEntry(25, "Standard Chartered Bank",      "scb",        "068"));
        b.add(bankEntry(26, "Coronation Merchant Bank",     null,         "559"));
        b.add(bankEntry(27, "Parallex Bank",                null,         "526"));
        b.add(bankEntry(28, "Titan Trust Bank",             "titan",      "102"));
        b.add(bankEntry(29, "Lotus Bank",                   null,         "303"));
        b.add(bankEntry(30, "Carbon (One Finance)",         "carbon",     "565"));
        NIGERIAN_BANK_FALLBACK = Collections.unmodifiableList(b);
    }

    /** Builds one bank map in the format expected by Flutter's Bank.fromJson. */
    private static Map<String, Object> bankEntry(int id, String name, String alias, String bankCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        if (alias != null) m.put("alias", alias);
        m.put("bank_code", bankCode);
        return m;
    }

    public List<Map<String, Object>> getSupportedBanks(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId).orElse(null);
        PaymentGateway gateway = wallet != null
                ? paymentGatewayResolver.resolveForWallet(wallet)
                : paymentGatewayResolver.resolveDefault();

        // Redis cache key is PSP-specific so a provider switch auto-invalidates.
        String cacheKey = BANK_LIST_CACHE_PREFIX + gateway.getClass().getSimpleName().toLowerCase();

        // Layer 0 (new): Redis — survives restarts, shared across all instances.
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                List<Map<String, Object>> banks = objectMapper.readValue(
                        cached,
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, Map.class));
                if (banks != null && !banks.isEmpty()) {
                    logger.debug("[BankList] Redis cache hit for {}", cacheKey);
                    return banks;
                }
            }
        } catch (Exception e) {
            logger.warn("[BankList] Redis read failed — will fetch live: {}", e.getMessage());
        }

        // Layer 1: try fresh upstream call (gateway already swallows HTTP errors
        // and returns [], but wrap in try-catch for any unexpected runtime exceptions).
        List<Map<String, Object>> fresh;
        try {
            fresh = gateway.getSupportedBanks();
        } catch (Exception e) {
            logger.warn("Bank list upstream threw exception — will try stale/static fallback: {}", e.getMessage());
            fresh = Collections.emptyList();
        }

        if (fresh != null && !fresh.isEmpty()) {
            _lastKnownBanks = fresh;   // refresh JVM stale-on-error cache
            // Write to Redis — next request returns in < 5ms
            try {
                redisTemplate.opsForValue().set(
                        cacheKey,
                        objectMapper.writeValueAsString(fresh),
                        BANK_LIST_TTL_HOURS, TimeUnit.HOURS);
                logger.info("[BankList] Cached {} banks in Redis (key={}, ttl={}h)",
                        fresh.size(), cacheKey, BANK_LIST_TTL_HOURS);
            } catch (Exception e) {
                logger.warn("[BankList] Redis write failed — result still returned: {}", e.getMessage());
            }
            return fresh;
        }

        // Layer 2: stale in-memory cache (populated from a previous successful call
        // within this JVM lifetime — survives transient upstream blips but not restarts).
        if (_lastKnownBanks != null && !_lastKnownBanks.isEmpty()) {
            logger.warn("Bank list upstream empty — serving stale in-memory cache ({} banks)", _lastKnownBanks.size());
            return _lastKnownBanks;
        }

        // Layer 3: static embedded list — cold start with upstream already down.
        // Nigerian bank codes are stable; this list covers all major institutions.
        // The 503 path in WalletController is now effectively unreachable.
        logger.warn("No upstream data and no stale cache — serving static Nigerian bank fallback ({} banks)",
                NIGERIAN_BANK_FALLBACK.size());
        return NIGERIAN_BANK_FALLBACK;
    }

    /**
     * Pre-loads the bank list for the default PSP into Redis at server startup.
     * After this runs, every call to {@link #getSupportedBanks} returns from
     * Redis (< 5ms) — no user ever waits on a live Rubies/upstream call.
     *
     * <p>If the cache is already populated (e.g. a recent restart), the upstream
     * call is skipped entirely to avoid unnecessary latency at boot time.
     */
    public void warmBankListCache() {
        try {
            PaymentGateway gateway = paymentGatewayResolver.resolveDefault();
            String cacheKey = BANK_LIST_CACHE_PREFIX + gateway.getClass().getSimpleName().toLowerCase();

            // Already warm? Nothing to do.
            String existing = redisTemplate.opsForValue().get(cacheKey);
            if (existing != null && !existing.isBlank()) {
                logger.info("[BankList] Cache already warm at startup — skipping upstream call");
                return;
            }

            List<Map<String, Object>> banks = gateway.getSupportedBanks();
            if (banks != null && !banks.isEmpty()) {
                redisTemplate.opsForValue().set(
                        cacheKey,
                        objectMapper.writeValueAsString(banks),
                        BANK_LIST_TTL_HOURS, TimeUnit.HOURS);
                logger.info("[BankList] Warmed {} banks into Redis at startup (key={})", banks.size(), cacheKey);
            } else {
                // Upstream returned nothing — static fallback will serve requests until
                // the next successful upstream call populates the cache.
                logger.warn("[BankList] Startup warm skipped — upstream returned empty list. " +
                        "Static fallback will be used until upstream recovers.");
            }
        } catch (Exception e) {
            logger.warn("[BankList] Startup warm failed — requests will lazy-load: {}", e.getMessage());
        }
    }

    public String resolveBankAccount(Long userId, String bankCode, String accountNumber) {
        String cacheKey = RESOLVE_ACCOUNT_PREFIX + bankCode + ":" + accountNumber;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached;
            }
        } catch (Exception e) {
            logger.debug("[ResolveAccountCache] Cache miss or read error for {}:{}: {}",
                    bankCode, accountNumber, e.getMessage());
        }

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElse(null);
        PaymentGateway gateway = wallet != null
                ? paymentGatewayResolver.resolveForWallet(wallet)
                : paymentGatewayResolver.resolveDefault();
        String accountName = gateway.resolveAccount(bankCode, accountNumber);

        try {
            redisTemplate.opsForValue().set(cacheKey, accountName,
                    RESOLVE_ACCOUNT_TTL_SECS, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("[ResolveAccountCache] Failed to cache {}:{}: {}",
                    bankCode, accountNumber, e.getMessage());
        }

        return accountName;
    }

    // ── OPay-style bank auto-detect ────────────────────────────────────────────

    private static final String DETECT_BANKS_CACHE_PREFIX = "detect_banks:";

    /**
     * Lowercased name fragments for the curated set of banks probed during account auto-detect.
     * Codes are NOT hardcoded here — we intersect these names with the active PSP's supported bank
     * list so each probe uses the exact {@code bank_code} that PSP expects. Trim this list if the
     * PSP starts billing per name-enquiry.
     */
    private static final List<String> POPULAR_BANK_NAMES = List.of(
            "opay", "palmpay", "moniepoint", "kuda", "guaranty", "gtbank",
            "access bank", "zenith", "united bank for africa", "first bank",
            "fidelity", "first city monument", "fcmb", "sterling", "wema",
            "stanbic", "ecobank", "union bank", "polaris", "providus",
            "keystone", "jaiz", "vfd"
    );

    /**
     * Given just an account number, probe name-enquiry across the curated popular-bank set in
     * parallel and return every bank that resolves to a real account name (normally exactly one).
     *
     * <p>A NUBAN doesn't encode its bank and Rubies name-enquiry requires a bank code, so this is
     * the only way to "detect" the bank. Each unique account number is cached (~10 min on hit,
     * ~1 min on miss) so repeats/retries don't re-probe. Resilient: a single failed probe (the
     * expected outcome for a non-matching bank) is skipped, never failing the whole call.
     *
     * @return a list of {@code {bankCode, bankName, accountName}} maps; empty when nothing matched.
     */
    public List<Map<String, Object>> detectBanksForAccount(Long userId, String accountNumber) {
        final String acct = accountNumber == null ? "" : accountNumber.trim();
        if (acct.length() != 10 || !acct.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("A valid 10-digit account number is required.");
        }

        final String cacheKey = DETECT_BANKS_CACHE_PREFIX + acct;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            }
        } catch (Exception e) {
            logger.warn("[DetectBanks] Redis read failed — probing live: {}", e.getMessage());
        }

        Wallet wallet = walletRepository.findByUserId(userId).orElse(null);
        final PaymentGateway gateway = wallet != null
                ? paymentGatewayResolver.resolveForWallet(wallet)
                : paymentGatewayResolver.resolveDefault();

        // Candidate banks = curated popular set ∩ the active PSP's supported bank list, so each
        // candidate carries the exact bank_code that PSP's resolveAccount expects.
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> bank : getSupportedBanks(userId)) {
            Object codeObj = bank.get("bank_code");
            String name = String.valueOf(bank.get("name")).toLowerCase();
            if (codeObj == null || String.valueOf(codeObj).trim().isEmpty()) continue;
            if (POPULAR_BANK_NAMES.stream().anyMatch(name::contains)) candidates.add(bank);
        }
        if (candidates.isEmpty()) return List.of();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, candidates.size()));
        try {
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (Map<String, Object> bank : candidates) {
                final String code = String.valueOf(bank.get("bank_code")).trim();
                final String bankName = String.valueOf(bank.get("name"));
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        String name = gateway.resolveAccount(code, acct);
                        if (name == null || name.isBlank()) return null;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("bankCode", code);
                        m.put("bankName", bankName);
                        m.put("accountName", name.trim());
                        return m;
                    } catch (Exception ignored) {
                        return null; // wrong bank / no such account — the expected non-match outcome
                    }
                }, pool).completeOnTimeout(null, 5, TimeUnit.SECONDS));
            }

            List<Map<String, Object>> matches = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (CompletableFuture<Map<String, Object>> f : futures) {
                Map<String, Object> m;
                try {
                    m = f.join();
                } catch (Exception e) {
                    m = null;
                }
                if (m != null && seen.add((String) m.get("bankCode"))) {
                    matches.add(m);
                }
            }

            try {
                long ttlSeconds = matches.isEmpty() ? 60 : 600;
                redisTemplate.opsForValue().set(cacheKey,
                        objectMapper.writeValueAsString(matches), ttlSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("[DetectBanks] Redis write failed: {}", e.getMessage());
            }
            return matches;
        } finally {
            pool.shutdownNow();
        }
    }

    private LocalDateTime parseTransactionDate(String paidOn) {
        if (paidOn == null || paidOn.isEmpty()) {
            return LocalDateTime.now();
        }
        try {
            DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");
            return LocalDateTime.parse(paidOn, formatter1);
        } catch (Exception e1) {
            try {
                DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm:ss a", Locale.ENGLISH);
                return LocalDateTime.parse(paidOn, formatter2);
            } catch (Exception e2) {
                logger.warn("Date parsing failed for '{}', using current time.", paidOn);
                return LocalDateTime.now();
            }
        }
    }

    @Transactional
    /** Convenience overload — notification always sent. */
    public void processSuccessfulFunding(
            String email, BigDecimal netAmount, BigDecimal grossAmount,
            BigDecimal fee, String ref, String desc, LocalDateTime time) {
        processSuccessfulFunding(email, netAmount, grossAmount, fee, ref, desc, time, false);
    }

    /**
     * Credits the wallet and (optionally) fires a push notification.
     *
     * @param suppressNotification pass {@code true} for internal P2P credits where
     *                             EnvelopeService already sent the recipient a notification,
     *                             preventing the duplicate "Wallet funded" alert.
     */
    public void processSuccessfulFunding(
            String email,
            BigDecimal netAmount,
            BigDecimal grossAmount,
            BigDecimal fee,
            String ref,
            String desc,
            LocalDateTime time,
            boolean suppressNotification
    ) {
        if (transactionLogRepository.existsByReference(ref)) {
            logger.info("Transaction {} already processed.", ref);
            return;
        }

        User user = userRepository.findFirstByEmailOrderByCreatedAtAsc(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        Wallet wallet = walletRepository.findByUser(user)
                .orElseGet(() -> createWalletForUser(user));

        wallet.setBalance(wallet.getBalance().add(netAmount));
        wallet.setUpdatedAt(LocalDateTime.now());
        wallet.setLastBalanceSyncAt(LocalDateTime.now());
        walletRepository.save(wallet);
        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
        evictWalletCache(user.getId());

        TransactionLog log = new TransactionLog();
        log.setUserId(user.getId());
        log.setAmount(netAmount);
        log.setFee(fee != null ? fee : BigDecimal.ZERO);
        log.setTransactionType(WALLET_DEPOSIT);
        log.setReference(ref);
        log.setDescription(desc + " | Gross: ₦" + grossAmount);
        log.setStatus(TransactionStatus.COMPLETED);
        log.setCreatedAt(time);
        transactionLogRepository.save(log);

        if (fee != null && fee.compareTo(BigDecimal.ZERO) > 0) {
            TransactionLog feeLog = new TransactionLog();
            feeLog.setUserId(user.getId());
            feeLog.setAmount(fee.negate());
            feeLog.setFee(BigDecimal.ZERO);
            feeLog.setTransactionType(TransactionType.WALLET_DEPOSIT_FEE);
            feeLog.setReference(ref + "-FEE");
            feeLog.setDescription("Deposit fee for " + ref);
            feeLog.setStatus(TransactionStatus.COMPLETED);
            feeLog.setCreatedAt(time);
            transactionLogRepository.save(feeLog);
        }

        if (!suppressNotification) {
            final BigDecimal finalFee = fee;
            CompletableFuture.runAsync(() -> {
                try {
                    String alertMessage = String.format(
                            "Wallet funded with ₦%.2f. (₦%.2f deposit fee applied)",
                            netAmount,
                            finalFee
                    );
                    notificationService.sendNotification(
                            user.getId().toString(),
                            alertMessage,
                            NotificationType.WALLET_FUNDED,
                            null,
                            null,
                            "VIEW_WALLET",
                            "/wallet"
                    );
                } catch (Exception e) {
                    logger.error("Failed to send credit alert", e);
                }
            });

            // Activation journey off-switch: comment out this one invocation to
            // stop the post-funding create-budget push/email.
            activationJourneyNudgeService.nudgeAfterWalletFunded(user.getId());
        }
    }

    private boolean hasStoredSettlementAccount(Wallet wallet) {
        return wallet.getSettlementAccountNumber() != null && !wallet.getSettlementAccountNumber().isBlank()
                && wallet.getSettlementBankName() != null && !wallet.getSettlementBankName().isBlank();
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString().trim();
    }
    @Transactional
    public Wallet updateSettlementAccount(Long userId, UpdateBankDetailsRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));
        PaymentGateway gateway = paymentGatewayResolver.resolveForWallet(wallet);

        String resolvedAccountName = gateway.resolveAccount(request.getBankCode(), request.getAccountNumber());

        boolean isUpdated = gateway.updateWithdrawalBankInfo(
                user.getEmail(),
                request.getBankName(),
                resolvedAccountName,
                request.getBankCode(),
                request.getAccountNumber()
        );

        if (!isUpdated) {
            throw new RuntimeException("Payment provider rejected the bank details.");
        }

        wallet.setSettlementAccountNumber(request.getAccountNumber());
        wallet.setSettlementBankCode(request.getBankCode());
        wallet.setSettlementBankName(request.getBankName());
        wallet.setSettlementAccountName(resolvedAccountName);

        logger.info("Successfully updated settlement account for user {}", user.getEmail());

        return walletRepository.save(wallet);
    }

    public Withdrawal processWithdrawal(Long userId, WithdrawalRequest request) {
        return processWithdrawal(userId, request, null, null);
    }

    public Withdrawal processWithdrawal(Long userId, WithdrawalRequest request,
                                         BigDecimal overrideMarkupFee, BigDecimal overrideNipFee) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        if (!userService.verifyTransactionPin(user, request.getTransactionPin())) {
            throw new IllegalArgumentException("Invalid transaction PIN");
        }

        PaymentGateway gateway = paymentGatewayResolver.resolveForWallet(wallet);

        // ── Resolve destination bank details ──────────────────────────────────────
        // Rubies: user enters destination per-transfer (like OPay).
        //         Frontend must call POST /wallets/resolve-account first to verify the name.
        // Legacy: pre-linked settlement account stored on the wallet.
        final String destBankCode;
        final String destBankName;
        final String destAccountNumber;
        final String destAccountName;

        if (RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())) {
            if (request.getBankCode() == null || request.getBankCode().isBlank()) {
                throw new IllegalArgumentException("Destination bank code is required.");
            }
            if (request.getAccountNumber() == null || request.getAccountNumber().isBlank()) {
                throw new IllegalArgumentException("Destination account number is required.");
            }
            if (request.getAccountName() == null || request.getAccountName().isBlank()) {
                throw new IllegalArgumentException(
                        "Please verify the destination account name before proceeding. " +
                        "Use POST /wallets/resolve-account to look it up.");
            }
            destBankCode      = request.getBankCode().trim();
            destBankName      = request.getBankName() != null ? request.getBankName().trim() : destBankCode;
            destAccountNumber = request.getAccountNumber().trim();

            // ── Server-side re-verification — never trust the client-submitted name ──
            // The frontend calls POST /wallets/resolve-account to SHOW the user the
            // real account-holder name for confirmation before they submit, but
            // nothing stops a modified client (or a direct API call) from then
            // submitting an arbitrary (accountNumber, accountName) pair to THIS
            // endpoint — request.getAccountName() up to this point was only checked
            // for being non-blank. We re-resolve here and use Rubies' own answer as
            // the name that actually gets sent onward, exactly mirroring how
            // updateSettlementAccount() already treats the gateway as the sole
            // source of truth for the pre-linked-account flow. If the resolve
            // fails (bad account number, Rubies outage, etc.) we fail the
            // withdrawal up front rather than risk moving funds toward an
            // unverified destination.
            String verifiedAccountName;
            try {
                verifiedAccountName = resolveBankAccount(userId, destBankCode, destAccountNumber);
            } catch (RuntimeException e) {
                logger.warn("[Withdrawal] Could not re-verify destination account for user={} bank={} acct={}: {}",
                        userId, destBankCode, destAccountNumber, e.getMessage());
                throw new IllegalArgumentException(
                        "We couldn't verify the destination account right now. Please re-check the " +
                        "account number and try again.");
            }
            if (verifiedAccountName == null || verifiedAccountName.isBlank()) {
                throw new IllegalArgumentException(
                        "We couldn't verify the destination account right now. Please re-check the " +
                        "account number and try again.");
            }
            verifiedAccountName = verifiedAccountName.trim();

            String submittedAccountName = request.getAccountName().trim();
            if (!submittedAccountName.equalsIgnoreCase(verifiedAccountName)) {
                logger.warn("[Withdrawal][SECURITY] Submitted account name didn't match Rubies' verified " +
                                "name — user={} bank={} acct={} submitted='{}' verified='{}'. Proceeding " +
                                "with the VERIFIED name; client input is never trusted for transfer destinations.",
                        userId, destBankCode, destAccountNumber, submittedAccountName, verifiedAccountName);
            }

            destAccountName = verifiedAccountName;
        } else {
            // Legacy Providus / SecureWave — must have a pre-linked settlement account
            if (wallet.getSettlementAccountNumber() == null || wallet.getSettlementBankCode() == null) {
                throw new IllegalStateException("Please link a withdrawal bank account before withdrawing funds.");
            }
            destBankCode      = wallet.getSettlementBankCode();
            destBankName      = wallet.getSettlementBankName();
            destAccountNumber = wallet.getSettlementAccountNumber();
            destAccountName   = wallet.getSettlementAccountName();
        }

        // ── Fee calculation: Rubies uses markup tiers, legacy providers use WithdrawalFeeService ──
        // transferFee  = Moniewise markup only (this is what enters the revenue wallet)
        // nipFee       = NIBSS NIP bank charge (auto-deducted by Rubies; does NOT go to revenue)
        // totalDebit   = amount + nipFee + transferFee  (what actually leaves the user's Rubies wallet)
        BigDecimal transferFee;
        BigDecimal nipFee;
        if (overrideMarkupFee != null) {
            transferFee = overrideMarkupFee;
            nipFee      = overrideNipFee != null ? overrideNipFee
                        : markupCalculatorService.calculateNipFee(request.getAmount());
            logger.info("[Transfer] Custom fees for user={} amount={}: markup=₦{} NIP=₦{}",
                    userId, request.getAmount(), transferFee, nipFee);
        } else if (request.isClosure()
                && RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())) {
            // Account closure: one flat charge, with the NIP paid out of it and
            // the remainder to Moniewise, so the total debit lands exactly on the
            // user's balance and the wallet can reach zero. Ordinary transfers
            // fall through to the tiered pricing below, unchanged.
            MarkupCalculatorService.FeeBreakdown closureFees =
                    markupCalculatorService.buildClosureBreakdown(request.getAmount());
            transferFee = closureFees.markupFee();
            nipFee      = closureFees.bankCharge();
            logger.info("[Closure] Flat closure fee for user={} amount=₦{}: markup=₦{} NIP=₦{}",
                    userId, request.getAmount(), transferFee, nipFee);
        } else if (RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())) {
            MarkupCalculatorService.FeeBreakdown breakdown =
                    markupCalculatorService.buildBreakdown(request.getAmount(), userId);
            transferFee = breakdown.markupFee();
            nipFee      = breakdown.bankCharge();
            logger.info("[Transfer] Rubies fees for user={} amount={}: markup=₦{} NIP=₦{}",
                    userId, request.getAmount(), transferFee, nipFee);
        } else {
            transferFee = withdrawalFeeService.calculateWithdrawalFee(request.getAmount());
            nipFee      = BigDecimal.ZERO;
        }

        BigDecimal stampDuty = RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())
                ? markupCalculatorService.calculateStampDuty(request.getAmount())
                : BigDecimal.ZERO;
        BigDecimal totalDebit = request.getAmount().add(nipFee).add(transferFee).add(stampDuty);

        if (wallet.getBalance().compareTo(totalDebit) < 0) {
            if (nipFee.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal totalFee = nipFee.add(transferFee);
                throw new IllegalArgumentException(insufficientWithdrawalBalanceMessage(
                        totalDebit,
                        totalFee,
                        wallet.getBalance()));
            }
            throw new IllegalArgumentException(insufficientWithdrawalBalanceMessage(
                    totalDebit,
                    transferFee,
                    wallet.getBalance()));
        }

        String narration = buildWithdrawalNarration(destBankName, request);
        Withdrawal withdrawal = createWithdrawalRecord(
                user, wallet, request, narration, transferFee, totalDebit,
                destBankCode, destBankName, destAccountNumber, destAccountName);
        self.reserveWithdrawalForProvider(withdrawal.getId());

        String providerReference;
        try {
            if (RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())) {
                // Rubies: pass explicit debit + credit account details
                providerReference = gateway.initiateTransferWithContext(
                        wallet.getProviderWalletRef(),
                        resolveDisplayName(user),
                        destBankCode,
                        destBankName,
                        destAccountNumber,
                        destAccountName,
                        request.getAmount(),
                        withdrawal.getClientReference(),
                        narration
                );
            } else {
                // Providus / SecureWave: legacy path
                providerReference = gateway.initiateWithdrawal(
                        user.getEmail(),
                        request.getAmount(),
                        narration
                );
            }
        } catch (RuntimeException e) {
            self.markWithdrawalFailed(withdrawal.getId(), e.getMessage());
            // Sanitise provider-level errors before they bubble to the user.
            // "Insufficient float" means OUR merchant float is low — not the user's fault.
            // "Insufficient balance" (without our balance-check prefix) is a Rubies internal
            // error that also maps to a float issue on our side.
            String rawMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (rawMsg.contains("insufficient float")
                    || rawMsg.contains("insufficient balance")
                    || rawMsg.contains("not enough float")) {
                throw new RuntimeException(
                        "Transfer temporarily unavailable. Please try again in a few minutes or contact support.");
            }
            throw e;
        }

        // NOTE: the markup fee is NOT transferred to the revenue wallet here.
        // It moves only after the SUCCESS webhook confirms the transfer landed —
        // see processRubiesWithdrawalConfirmation(). Doing it here would mean the
        // revenue wallet gets the fee even when Rubies later rejects the transfer.

        return self.finalizeAcceptedWithdrawal(withdrawal.getId(), providerReference);
    }

    /**
     * Returns the fee breakdown for a transfer, respecting the user's wallet provider
     * and premium status.
     *
     * <p>Rubies wallets use the markup-fee tiers. Legacy wallets use the flat withdrawal fee.
     * Frontend should call this before showing the confirmation screen.
     */
    public WithdrawalQuoteResponse quoteWithdrawal(BigDecimal amount, Long userId) {
        return quoteWithdrawal(amount, userId, false);
    }

    /**
     * @param closure when true the quote uses the flat account-closure charge
     *                instead of ordinary tiered pricing, so the figure shown
     *                before confirming matches what is actually debited.
     */
    public WithdrawalQuoteResponse quoteWithdrawal(BigDecimal amount, Long userId, boolean closure) {
        Wallet wallet = walletRepository.findByUserId(userId).orElse(null);

        if (wallet != null && RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())) {
            MarkupCalculatorService.FeeBreakdown breakdown = closure
                    ? markupCalculatorService.buildClosureBreakdown(amount)
                    : markupCalculatorService.buildBreakdown(amount, userId);

            // fee        = Moniewise markup only (revenue)
            // bankCharge = NIBSS NIP fee (goes to Rubies/banking system — NOT revenue)
            // stampDuty  = ₦50 on transfers > ₦10K (goes to government via Rubies — NOT revenue)
            // totalDebit = amount + bankCharge + fee + stampDuty
            return new WithdrawalQuoteResponse(
                    breakdown.transferAmount(),
                    breakdown.markupFee(),
                    breakdown.bankCharge(),
                    breakdown.stampDuty(),
                    breakdown.totalFromEnvelope(),
                    amount,
                    closure ? "CLOSURE_FLAT_FEE"
                            : (breakdown.markupFee().compareTo(BigDecimal.ZERO) == 0
                                    ? "WAIVED" : "MARKUP_TIER"),
                    "USER_BALANCE",
                    breakdown.displayText()
            );
        }

        // Legacy: flat fee
        BigDecimal fee = withdrawalFeeService.calculateWithdrawalFee(amount);
        BigDecimal totalDebit = withdrawalFeeService.calculateTotalDebit(amount);
        return new WithdrawalQuoteResponse(
                amount,
                fee,
                totalDebit,
                amount,
                "FLAT_WITHDRAWAL_FEE",
                "USER_BALANCE",
                String.format("A ₦%s withdrawal fee applies. ₦%s will be deducted from your available balance.",
                        formatMoney(fee),
                        formatMoney(totalDebit))
        );
    }

    /**
     * Backward-compatible overload — called by existing code that doesn't pass userId.
     * Falls back to the flat withdrawal fee (no markup/premium logic).
     */
    public WithdrawalQuoteResponse quoteWithdrawal(BigDecimal amount) {
        BigDecimal fee = withdrawalFeeService.calculateWithdrawalFee(amount);
        BigDecimal totalDebit = withdrawalFeeService.calculateTotalDebit(amount);
        return new WithdrawalQuoteResponse(
                amount,
                fee,
                totalDebit,
                amount,
                "FLAT_WITHDRAWAL_FEE",
                "USER_BALANCE",
                String.format("A ₦%s withdrawal fee applies. ₦%s will be deducted from your available balance.",
                        formatMoney(fee),
                        formatMoney(totalDebit))
        );
    }

    /**
     * Convenience overload — resolves by userId without requiring the caller to hold a UserRepository.
     * Falls back to "ACCOUNT HOLDER" if the user is not found.
     */
    public String resolveDisplayNameByUserId(Long userId) {
        if (userId == null) return "ACCOUNT HOLDER";
        User user = userRepository.findById(userId).orElse(null);
        return user != null ? resolveDisplayName(user) : "ACCOUNT HOLDER";
    }

    public String resolveFundingAccountName(Wallet wallet, User user) {
        if (wallet != null) {
            String storedAccountName = safeString(wallet.getAccountName());
            if (!storedAccountName.isBlank()) {
                return storedAccountName;
            }

            Map<String, Object> metadata = wallet.getProviderMetadata();
            if (metadata != null) {
                String metadataAccountName = firstPresentMetadataValue(
                        metadata,
                        "accountName",
                        "account_name",
                        "walletAccountName",
                        "wallet_account_name"
                );
                if (!metadataAccountName.isBlank()) {
                    return metadataAccountName;
                }
            }
        }

        return user != null ? resolveDisplayName(user) : "ACCOUNT HOLDER";
    }

    /** Resolves the user's display name from BVN profile fields, falling back to email prefix. */
    public String resolveDisplayName(User user) {
        Map<String, Object> profile = user.getProfileData() != null ? user.getProfileData() : Map.of();
        String first  = strFromProfile(profile, "bvnFirstName",  "bvnFirst",  "firstName");
        String last   = strFromProfile(profile, "bvnLastName",   "bvnLast",   "lastName");
        if (first != null && last != null) return (first + " " + last).toUpperCase();
        if (last  != null) return last.toUpperCase();
        // Last resort: use the part of the email before @
        String email = user.getEmail();
        return email != null ? email.split("@")[0].toUpperCase() : "ACCOUNT HOLDER";
    }

    private String firstPresentMetadataValue(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            String value = safeString(metadata.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String strFromProfile(Map<String, Object> profile, String... keys) {
        for (String key : keys) {
            Object v = profile.get(key);
            if (v != null && !v.toString().isBlank()) return v.toString().trim();
        }
        return null;
    }

    @Transactional
    public void debitWalletForWithdrawal(Long userId, BigDecimal amount) {
        debitWalletForWithdrawal(userId, amount, "complete this transaction");
    }

    @Transactional
    public void debitWalletForWithdrawal(Long userId, BigDecimal amount, String actionDescription) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException(
                    insufficientWalletOnlyBalanceMessage(actionDescription, amount, wallet.getBalance()));
        }
        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);
        monnieCacheInvalidationService.evictUserAfterCommit(userId);
        evictWalletCache(userId);
    }

    @Transactional(readOnly = true)
    public List<Withdrawal> getRecentWithdrawals(Long userId) {
        return withdrawalRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Powers the "transferred before" auto-suggest dropdown on the Transfer to
     * Bank screen — as the user starts typing an account number, the frontend
     * shows a list of accounts they've successfully sent money to before;
     * picking one auto-fills bank + account number + name, leaving just the
     * amount to enter.
     *
     * <p>Deliberately derived live from the user's own {@code COMPLETED}
     * withdrawal history rather than a separate "saved beneficiary" table:
     * <ul>
     *   <li>zero new schema/migration — works the moment this ships</li>
     *   <li>always accurate — no separate "save this recipient" step that can
     *       drift out of sync or go stale</li>
     *   <li>only ever surfaces destinations that actually settled successfully
     *       — never a mistyped account from a FAILED/REVERSED attempt</li>
     * </ul>
     *
     * <p>Dedupes by {@code bankCode + accountNumber} (a user may have sent to
     * the same account many times — we only want it to appear once, using the
     * most recent — and verified — {@code accountName} on file), capped at
     * {@code limit} — the same method backs both the quick top-3 dropdown and
     * the "see more" full-list screen, just called with a different limit.
     * Note the destination name shown here is whatever Rubies verified at the
     * time of that past transfer (see the server-side re-verification in
     * {@code processWithdrawal}); the gateway is still re-resolved on
     * submission regardless, so even a "trusted" suggestion gets the same
     * fresh-name guarantee as a brand-new recipient.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRecentRecipients(Long userId, int limit) {
        // Primary dedup map keyed by "bankCode|accountNumber" — first entry for each key wins
        // (both sources are newest-first, so the most-recent transfer to a given account
        // is naturally preferred).
        LinkedHashMap<String, Map<String, Object>> deduped = new LinkedHashMap<>();

        // ── Source 1: wallet withdrawals (WD- / legacy path) ─────────────────
        List<Withdrawal> recentWithdrawals = withdrawalRepository
                .findTop50ByUserIdAndStatusOrderByCreatedAtDesc(userId, WithdrawalStatus.COMPLETED);
        for (Withdrawal w : recentWithdrawals) {
            if (deduped.size() >= limit) break;
            if (w.getBankCode() == null || w.getAccountNumber() == null) continue;

            String acc = w.getAccountNumber().trim();
            String key = w.getBankCode().trim() + "|" + acc;
            if (deduped.containsKey(key)) continue;
            // Secondary dedup: same account number at a different-coded bank
            if (accountNumberAlreadyPresent(deduped, acc)) continue;

            Map<String, Object> recipient = new LinkedHashMap<>();
            recipient.put("bankCode", w.getBankCode());
            recipient.put("bankName", w.getBankName());
            recipient.put("accountNumber", acc);
            recipient.put("accountName", w.getAccountName());
            deduped.put(key, recipient);
        }

        // ── Source 2: envelope external transfers (EXT- / Rubies path) ───────
        // These are stored on TransactionLog (not the Withdrawal table), so they
        // were previously invisible to this method — the dropdown would show empty
        // even if the user had many successful envelope-to-bank transfers.
        if (deduped.size() < limit) {
            List<TransactionLog> recentExt = transactionLogRepository
                    .findRecentCompletedEnvelopeExternalTransfers(
                            userId,
                            org.springframework.data.domain.PageRequest.of(0, 50)
                    );
            for (TransactionLog t : recentExt) {
                if (deduped.size() >= limit) break;
                if (t.getExternalAccountNumber() == null) continue;

                // bankCode may be null for historical records (before V19 migration).
                // Use bankName as the primary-key suffix when bankCode is absent.
                String bc  = t.getExternalBankCode()  != null ? t.getExternalBankCode().trim()  : "";
                String acc = t.getExternalAccountNumber().trim();
                String key = (bc.isEmpty() ? t.getExternalBankName() : bc) + "|" + acc;
                if (deduped.containsKey(key)) continue;
                // Secondary dedup: catches the case where Source 1 stored this account
                // under a bankCode key while this EXT- record has an empty bankCode —
                // e.g. "100004|8060214037" vs "OPAY|8060214037" for the same account.
                if (accountNumberAlreadyPresent(deduped, acc)) continue;

                Map<String, Object> recipient = new LinkedHashMap<>();
                recipient.put("bankCode",      bc);
                recipient.put("bankName",      t.getExternalBankName() != null ? t.getExternalBankName() : "");
                recipient.put("accountNumber", acc);
                recipient.put("accountName",   t.getExternalAccountName() != null ? t.getExternalAccountName() : "");
                deduped.put(key, recipient);
            }
        }

        return new ArrayList<>(deduped.values());
    }

    /** Returns true if any entry in the dedup map already carries this account number. */
    private boolean accountNumberAlreadyPresent(
            LinkedHashMap<String, Map<String, Object>> deduped, String accountNumber) {
        for (Map<String, Object> r : deduped.values()) {
            if (accountNumber.equals(r.get("accountNumber"))) return true;
        }
        return false;
    }

    @Transactional
    public Withdrawal finalizeAcceptedWithdrawal(Long withdrawalId, String providerReference) {
        Withdrawal withdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("Withdrawal not found"));

        withdrawal.setProviderReference(providerReference);
        withdrawal.setStatus(WithdrawalStatus.PROCESSING);
        withdrawal.setProcessedAt(LocalDateTime.now());
        withdrawal.setFailureReason(null);
        transactionLogRepository.findByReference(withdrawal.getClientReference()).ifPresent(logEntry -> {
            logEntry.setProviderReference(providerReference);
            logEntry.setStatus(TransactionStatus.PROCESSING);
            transactionLogRepository.save(logEntry);
        });
        transactionLogRepository.findByReference(buildWithdrawalFeeReference(withdrawal)).ifPresent(logEntry -> {
            logEntry.setProviderReference(providerReference);
            logEntry.setStatus(TransactionStatus.PROCESSING);
            transactionLogRepository.save(logEntry);
        });
        return withdrawalRepository.save(withdrawal);
    }

    @Transactional
    public void markWithdrawalFailed(Long withdrawalId, String reason) {
        withdrawalRepository.findById(withdrawalId).ifPresent(withdrawal -> {
            if (withdrawal.getStatus() == WithdrawalStatus.FAILED || withdrawal.getStatus() == WithdrawalStatus.REVERSED) {
                return;
            }

            Wallet wallet = walletRepository.findByUserIdForUpdate(withdrawal.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

            wallet.setBalance(wallet.getBalance().add(withdrawal.getTotalDebit()));
            wallet.setUpdatedAt(LocalDateTime.now());
            walletRepository.save(wallet);
            monnieCacheInvalidationService.evictUserAfterCommit(withdrawal.getUserId());
        evictWalletCache(withdrawal.getUserId());

            transactionLogRepository.findByReference(withdrawal.getClientReference()).ifPresent(logEntry -> {
                logEntry.setStatus(TransactionStatus.REVERSED);
                logEntry.setDescription(withdrawal.getNarration() + " | Failed: " + reason + " | Amount and withdrawal fee reversed.");
                transactionLogRepository.save(logEntry);
            });

            transactionLogRepository.findByReference(buildWithdrawalFeeReference(withdrawal)).ifPresent(logEntry -> {
                logEntry.setStatus(TransactionStatus.REVERSED);
                logEntry.setDescription("Withdrawal fee reversed for " + withdrawal.getClientReference());
                transactionLogRepository.save(logEntry);
            });

            withdrawal.setStatus(WithdrawalStatus.FAILED);
            withdrawal.setFailureReason(reason);
            withdrawal.setProcessedAt(LocalDateTime.now());
            withdrawalRepository.save(withdrawal);

            // Notify user — fire-and-forget so a notification failure never rolls back the reversal
            String failMsg = String.format(
                    "Your transfer of ₦%.2f to %s (%s) could not be completed. Your balance has been reversed.",
                    withdrawal.getAmount(),
                    withdrawal.getAccountName() != null ? withdrawal.getAccountName() : withdrawal.getAccountNumber(),
                    withdrawal.getBankName() != null ? withdrawal.getBankName() : "Unknown Bank"
            );
            final Long userId = withdrawal.getUserId();
            CompletableFuture.runAsync(() -> {
                try {
                    notificationService.sendNotification(
                            userId.toString(),
                            failMsg,
                            NotificationType.WITHDRAWAL,
                            null, null, "VIEW_WALLET", "/wallet"
                    );
                } catch (Exception e) {
                    logger.error("[Wallet] Failed to send withdrawal failure notification for userId={}", userId, e);
                }
            });
        });
    }

    @Transactional
    public void reserveWithdrawalForProvider(Long withdrawalId) {
        Withdrawal withdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("Withdrawal not found"));

        Wallet wallet = walletRepository.findByUserIdForUpdate(withdrawal.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        BigDecimal fee = withdrawal.getFeeAmount() != null ? withdrawal.getFeeAmount() : BigDecimal.ZERO;
        BigDecimal totalDebit = withdrawal.getTotalDebit();
        if (wallet.getBalance().compareTo(totalDebit) < 0) {
            throw new IllegalArgumentException(insufficientWithdrawalBalanceMessage(
                    totalDebit,
                    fee,
                    wallet.getBalance()));
        }

        wallet.setBalance(wallet.getBalance().subtract(totalDebit));
        wallet.setUpdatedAt(LocalDateTime.now());
        wallet.setLastBalanceSyncAt(LocalDateTime.now());
        walletRepository.save(wallet);
        monnieCacheInvalidationService.evictUserAfterCommit(withdrawal.getUserId());
        evictWalletCache(withdrawal.getUserId());

        if (transactionLogRepository.findByReference(withdrawal.getClientReference()).isEmpty()) {
            BigDecimal stampDuty = RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName())
                    ? markupCalculatorService.calculateStampDuty(withdrawal.getAmount())
                    : BigDecimal.ZERO;
            TransactionLog logEntry = TransactionLog.builder()
                    .userId(withdrawal.getUserId())
                    .externalAccountId(null)
                    .externalBankName(withdrawal.getBankName())
                    .externalAccountNumber(withdrawal.getAccountNumber())
                    .externalAccountName(withdrawal.getAccountName())
                    .amount(withdrawal.getAmount())
                    .fee(fee)
                    .stampDuty(stampDuty)
                    .reference(withdrawal.getClientReference())
                    .status(TransactionStatus.PROCESSING)
                    .transactionType(TransactionType.WALLET_WITHDRAWAL)
                    .providerName(wallet.getProviderName())
                    .description(withdrawal.getNarration() + " | Recipient receives ₦" + formatMoney(withdrawal.getRecipientReceives()))
                    .createdAt(LocalDateTime.now())
                    .build();
            transactionLogRepository.save(logEntry);
        }

        String feeReference = buildWithdrawalFeeReference(withdrawal);
        if (fee.compareTo(BigDecimal.ZERO) > 0 && transactionLogRepository.findByReference(feeReference).isEmpty()) {
            TransactionLog feeLogEntry = TransactionLog.builder()
                    .userId(withdrawal.getUserId())
                    .externalAccountId(null)
                    .externalBankName(withdrawal.getBankName())
                    .externalAccountNumber(withdrawal.getAccountNumber())
                    .externalAccountName(withdrawal.getAccountName())
                    .amount(fee)
                    .fee(BigDecimal.ZERO)
                    .reference(feeReference)
                    .status(TransactionStatus.PROCESSING)
                    .transactionType(TransactionType.WALLET_WITHDRAWAL_FEE)
                    .description("Withdrawal fee recovery for " + withdrawal.getClientReference())
                    .createdAt(LocalDateTime.now())
                    .build();
            transactionLogRepository.save(feeLogEntry);
        }
    }

    private Withdrawal createWithdrawalRecord(User user,
                                              Wallet wallet,
                                              WithdrawalRequest request,
                                              String narration,
                                              BigDecimal fee,
                                              BigDecimal totalDebit,
                                              String destBankCode,
                                              String destBankName,
                                              String destAccountNumber,
                                              String destAccountName) {
        Withdrawal withdrawal = new Withdrawal();
        withdrawal.setUserId(user.getId());
        withdrawal.setWalletId(wallet.getId());
        withdrawal.setAmount(request.getAmount());
        withdrawal.setFeeAmount(fee);
        withdrawal.setTotalDebit(totalDebit);
        withdrawal.setRecipientReceives(request.getAmount());
        withdrawal.setCurrency(wallet.getCurrency());
        withdrawal.setNarration(narration);
        withdrawal.setBankName(destBankName);
        withdrawal.setBankCode(destBankCode);
        withdrawal.setAccountNumber(destAccountNumber);
        withdrawal.setAccountName(destAccountName);
        withdrawal.setClientReference(buildClientReference(user.getId()));
        withdrawal.setStatus(WithdrawalStatus.INITIATED);
        withdrawal.setCreatedAt(LocalDateTime.now());
        return withdrawalRepository.save(withdrawal);
    }

    private String buildWithdrawalNarration(String destBankName, WithdrawalRequest request) {
        if (request.getNarration() != null && !request.getNarration().isBlank()) {
            return request.getNarration().trim();
        }
        return "Wisemonie Withdrawal to " + (destBankName != null ? destBankName : "Bank");
    }

    private String buildWithdrawalFeeReference(Withdrawal withdrawal) {
        return withdrawal.getClientReference() + "-FEE";
    }

    private String insufficientWithdrawalBalanceMessage(BigDecimal totalDebit,
                                                        BigDecimal fee,
                                                        BigDecimal walletBalance) {
        String feePart = fee != null && fee.compareTo(BigDecimal.ZERO) > 0
                ? " and its \u20A6" + formatMoney(fee) + " charges"
                : "";
        return String.format(
                "Your wallet balance is not enough to cover this withdrawal%s. "
                        + "Your dashboard total includes budgets and savings, but withdrawals and fees "
                        + "can only come from your wallet balance. Please top up your wallet to continue. "
                        + "Wallet available: \u20A6%s. Total needed: \u20A6%s.",
                feePart,
                formatMoney(walletBalance),
                formatMoney(totalDebit));
    }

    private String insufficientWalletOnlyBalanceMessage(String actionDescription,
                                                        BigDecimal amountNeeded,
                                                        BigDecimal walletBalance) {
        String action = actionDescription != null && !actionDescription.isBlank()
                ? actionDescription
                : "complete this transaction";
        return String.format(
                "Your wallet balance is not enough to %s. "
                        + "Your dashboard total includes money in budgets and savings, but this action can only use "
                        + "money in your wallet balance. Please top up your wallet or reduce the amount to fit your wallet balance. "
                        + "Wallet available: \u20A6%s. Amount needed: \u20A6%s.",
                action,
                formatMoney(walletBalance),
                formatMoney(amountNeeded));
    }

    private String formatMoney(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private String buildClientReference(Long userId) {
        return "WD-" + userId + "-" + System.currentTimeMillis();
    }

    /**
     * Fires a best-effort Rubies transfer of the markup fee from the user's Rubies
     * wallet into Moniewise's own Rubies revenue wallet.
     *
     * <p>This call is asynchronous and non-blocking — if it fails, the internal
     * revenue wallet DB record (written by the webhook handler) remains the source
     * of truth for accounting. The Rubies-wallet balance reconciliation can catch any
     * missed fee transfers during periodic audits.
     *
     * <p>Set {@code rubies.revenue.account.number} in system_config via:
     * {@code PUT /admin/config/rubies.revenue.account.number} with
     * {@code {"value":"7012345678","description":"Moniewise Rubies revenue wallet"}}.
     *
     * @param feeAmount      the markup fee to transfer (must be > 0)
     * @param fromWalletRef  the user's Rubies wallet account number (debit side)
     * @param fromWalletName the user's display name (debit narration)
     * @param originalRef    the original WD- or EXT- reference (used to build REV- reference)
     */
    public void collectRubiesMarkupFeeAsync(
            BigDecimal feeAmount,
            String fromWalletRef,
            String fromWalletName,
            String originalRef,
            Long userId) {
        collectRubiesToRevenueAsync(feeAmount, fromWalletRef, fromWalletName, originalRef, userId,
                TransactionType.MARKUP_FEE_COLLECTION, "Markup fee");
    }

    /**
     * VAS analogue of {@link #collectRubiesMarkupFeeAsync}: physically moves the full
     * airtime/data payment from the user's Rubies wallet into Moniewise's Rubies
     * revenue/internal account. Same idempotent, best-effort Rubies-to-Rubies P2P —
     * only the ledger label ({@link TransactionType#VAS_PAYMENT_COLLECTION}) differs,
     * so VAS collections are distinguishable from markup-fee collections in reports.
     */
    public void collectRubiesVasPaymentAsync(
            BigDecimal amount,
            String fromWalletRef,
            String fromWalletName,
            String originalRef,
            Long userId) {
        collectRubiesToRevenueAsync(amount, fromWalletRef, fromWalletName, originalRef, userId,
                TransactionType.VAS_PAYMENT_COLLECTION, "Airtime/Data payment");
    }

    /**
     * Physically moves a budget creation fee from the user's Rubies wallet into
     * Moniewise's Rubies revenue/internal account. The user-facing debit remains
     * the {@link TransactionType#BUDGET_CREATION_FEE} log written by BudgetService;
     * this internal REV-* log exists only for provider-level collection tracking.
     */
    public void collectRubiesBudgetCreationFeeAsync(
            BigDecimal feeAmount,
            String fromWalletRef,
            String fromWalletName,
            String originalRef,
            Long userId) {
        collectRubiesToRevenueAsync(feeAmount, fromWalletRef, fromWalletName, originalRef, userId,
                TransactionType.BUDGET_FEE_COLLECTION, "Budget creation fee");
    }

    /**
     * Shared core for both Rubies-to-revenue collectors. {@code label} drives the
     * human-readable log/narration text and {@code txnType} the ledger category.
     * Reference is always {@code REV-{originalRef}} (idempotent retry key).
     */
    private void collectRubiesToRevenueAsync(
            BigDecimal feeAmount,
            String fromWalletRef,
            String fromWalletName,
            String originalRef,
            Long userId,
            TransactionType txnType,
            String label) {

        if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) <= 0) return;
        if (fromWalletRef == null || fromWalletRef.isBlank()) return;

        // ── Resolve revenue account: DB wallet record is primary, system_config is fallback ──
        // After POST /admin/rubies/register-revenue-wallet (or setup-revenue-wallet), both
        // the DB wallet's providerWalletRef and the system_config key hold the same number.
        // Checking the DB record first is slightly more reliable and avoids a Redis round-trip.
        String revenueAccountNumber = null;
        String revenueAccountName   = "Moniewise Revenue";
        try {
            // Primary: revenue wallet DB record's providerWalletRef
            Wallet revenueWallet = walletRepository.findByRevenueWalletTrue().orElse(null);
            if (revenueWallet != null
                    && revenueWallet.getProviderWalletRef() != null
                    && !revenueWallet.getProviderWalletRef().isBlank()) {
                revenueAccountNumber = revenueWallet.getProviderWalletRef();
                if (revenueWallet.getAccountNumber() != null
                        && !revenueWallet.getAccountNumber().isBlank()) {
                    // use the wallet's account display name if set via providerMetadata / bankName
                    revenueAccountName = "Moniewise Revenue";   // overridden below from system_config if available
                }
                logger.debug("[Rubies-Fee] Revenue account resolved from DB wallet record: acct={}",
                        revenueAccountNumber);
            }

            // Fallback: system_config (also used to pick up the display name regardless)
            String cfgNumber = systemConfigService.getString(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER);
            String cfgName   = systemConfigService.getString(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME);

            if (revenueAccountNumber == null || revenueAccountNumber.isBlank()) {
                // DB wallet not linked yet — use system_config
                revenueAccountNumber = cfgNumber;
                logger.debug("[Rubies-Fee] Revenue account resolved from system_config: acct={}",
                        revenueAccountNumber);
            }
            if (cfgName != null && !cfgName.isBlank()) {
                revenueAccountName = cfgName;
            }
        } catch (Exception e) {
            logger.warn("[Rubies-Fee] Could not resolve revenue account: {}", e.getMessage());
        }

        if (revenueAccountNumber == null || revenueAccountNumber.isBlank()) {
            // Write a FAILED log so there is a DB record of every skipped collection —
            // without this, the only evidence is a server-log line which admins may miss.
            String skipRef = "REV-" + originalRef;
            try {
                TransactionLog skipLog = TransactionLog.builder()
                        .userId(userId)
                        .reference(skipRef)
                        .amount(feeAmount)
                        .transactionType(txnType)
                        .status(TransactionStatus.FAILED)
                        .providerName(RubiesGateway.PROVIDER_NAME)
                        .description(label + " collection SKIPPED — Rubies revenue account not configured. "
                                + "Call POST /admin/rubies/register-revenue-wallet to fix.")
                        .createdAt(LocalDateTime.now())
                        .build();
                transactionLogRepository.save(skipLog);
            } catch (Exception ex) {
                logger.warn("[Rubies-Fee] Could not write SKIPPED fee log for ref={}: {}", skipRef, ex.getMessage());
            }
            logger.error("[Rubies-Fee] *** Revenue account NOT CONFIGURED *** — " +
                    "₦{} {} for ref={} was NOT transferred to Moniewise revenue wallet. " +
                    "Register the account via POST /admin/rubies/register-revenue-wallet",
                    feeAmount, label.toLowerCase(Locale.ROOT), originalRef);
            return;
        }

        final String revRef     = "REV-" + originalRef;
        final String revAccount = revenueAccountNumber;
        final String revName    = revenueAccountName;
        final Long   revUserId  = userId;

        CompletableFuture.runAsync(() -> {

            // ── Idempotency guard ─────────────────────────────────────────────────
            // Check the current DB state at the time this async task actually runs.
            // If a previous attempt already landed (COMPLETED), there is nothing to do.
            // If a previous attempt FAILED or is stuck PENDING, reuse the existing row
            // instead of inserting a new one (which would hit the unique reference constraint).
            TransactionLog feeLog;
            try {
                TransactionLog existing = transactionLogRepository.findByReference(revRef).orElse(null);
                if (existing != null && existing.getStatus() == TransactionStatus.COMPLETED) {
                    logger.info("[Rubies-Fee] Fee already collected for ref={} — skipping duplicate attempt.", revRef);
                    return;
                }
                if (existing != null) {
                    // FAILED or stuck PENDING — reset for retry rather than creating a duplicate
                    existing.setStatus(TransactionStatus.PENDING);
                    existing.setDescription(label + " ₦" + feeAmount + " retry for " + originalRef);
                    existing.setCreatedAt(LocalDateTime.now());
                    feeLog = transactionLogRepository.save(existing);
                    logger.info("[Rubies-Fee] Resetting {} fee log to PENDING for retry. ref={}",
                            existing.getStatus(), revRef);
                } else {
                    // ── 1. Write PENDING transaction log (first attempt) ──────────
                    // Written first so the record exists even if the Rubies call hangs
                    // or the JVM crashes before we get a response.
                    feeLog = transactionLogRepository.save(TransactionLog.builder()
                            .userId(revUserId)
                            .reference(revRef)
                            .amount(feeAmount)
                            .transactionType(txnType)
                            .status(TransactionStatus.PENDING)
                            .externalAccountNumber(revAccount)
                            .externalAccountName(revName)
                            .externalBankName("Rubies MFB")
                            .providerName(RubiesGateway.PROVIDER_NAME)
                            .description(label + " ₦" + feeAmount + " collected via Rubies P2P for " + originalRef)
                            .createdAt(LocalDateTime.now())
                            .build());
                }
            } catch (Exception ex) {
                logger.warn("[Rubies-Fee] Could not write/reset PENDING collection log for ref={}: {}",
                        revRef, ex.getMessage());
                try {
                    TransactionLog existing = transactionLogRepository.findByReference(revRef).orElse(null);
                    if (existing != null) {
                        logger.info("[Rubies-Fee] Collection log already exists for ref={} with status={} — skipping duplicate provider call.",
                                revRef, existing.getStatus());
                        return;
                    }
                } catch (Exception lookupEx) {
                    logger.warn("[Rubies-Fee] Could not re-check collection log for ref={}: {}",
                            revRef, lookupEx.getMessage());
                }
                logger.error("[Rubies-Fee] No collection log could be claimed for ref={} — provider transfer not attempted.",
                        revRef);
                return;
            }

            // ── 2. Fire Rubies-to-Rubies P2P ─────────────────────────────────────
            try {
                PaymentGateway rubies = paymentGatewayResolver
                        .resolveByProviderName(RubiesGateway.PROVIDER_NAME);
                rubies.initiateTransferWithContext(
                        fromWalletRef,
                        fromWalletName,
                        "090175",          // Rubies MFB bank code
                        "Rubies MFB",
                        revAccount,
                        revName,
                        feeAmount,
                        revRef,
                        label + " for " + originalRef
                );

                // ── 3. Update log to COMPLETED ────────────────────────────────────
                transactionLogRepository.findByReference(revRef).ifPresent(log -> {
                    log.setStatus(TransactionStatus.COMPLETED);
                    transactionLogRepository.save(log);
                });
                logger.info("[Rubies-Fee] ₦{} {} transferred to revenue wallet. ref={}",
                        feeAmount, label.toLowerCase(Locale.ROOT), revRef);

            } catch (Exception e) {
                // ── 4. Update log to FAILED ───────────────────────────────────────
                transactionLogRepository.findByReference(revRef).ifPresent(log -> {
                    log.setStatus(TransactionStatus.FAILED);
                    log.setDescription(log.getDescription() + " | FAILED: " + e.getMessage());
                    transactionLogRepository.save(log);
                });
                logger.error("[Rubies-Fee] Failed to transfer ₦{} markup fee for ref={}: {}",
                        feeAmount, revRef, e.getMessage());
            }
        });
    }

    @Transactional
    public Wallet attachProviderMapping(
            Long userId,
            String providerName,
            String providerCustomerRef,
            String providerWalletRef,
            String masterWalletRef,
            String subWalletRef,
            String providerStatus,
//            String providerMetadata
            Map<String, Object> providerMetadata
    ) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));

        wallet.setProviderName(providerName);
        wallet.setProviderCustomerRef(providerCustomerRef);
        wallet.setProviderWalletRef(providerWalletRef);
        wallet.setMasterWalletRef(masterWalletRef);
        wallet.setSubWalletRef(subWalletRef);
        wallet.setProviderStatus(providerStatus);
        wallet.setProviderMetadata(providerMetadata != null ? providerMetadata : new HashMap<>());
        wallet.setLastBalanceSyncAt(LocalDateTime.now());

        return walletRepository.save(wallet);
    }

    @Transactional
    public Wallet updateProviderStatus(Long walletId, String providerStatus) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        wallet.setProviderStatus(providerStatus);
        wallet.setLastBalanceSyncAt(LocalDateTime.now());

        return walletRepository.save(wallet);
    }

    @Transactional
    public Wallet updateBalanceFromProvider(Long walletId, BigDecimal newBalance) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found"));

        wallet.setBalance(newBalance);
        wallet.setLastBalanceSyncAt(LocalDateTime.now());
        wallet.setUpdatedAt(LocalDateTime.now());

        Wallet savedWallet = walletRepository.save(wallet);
        if (savedWallet.getUser() != null && savedWallet.getUser().getId() != null) {
            monnieCacheInvalidationService.evictUserAfterCommit(savedWallet.getUser().getId());
        evictWalletCache(savedWallet.getUser().getId());
        }
        return savedWallet;
    }

    public Wallet getWalletByProviderWalletRef(String providerWalletRef) {
        return walletRepository.findByProviderWalletRef(providerWalletRef)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for provider wallet ref: " + providerWalletRef));
    }

    public Wallet getWalletBySubWalletRef(String subWalletRef) {
        return walletRepository.findBySubWalletRef(subWalletRef)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for sub-wallet ref: " + subWalletRef));
    }

    public Map<String, Object> getProviderTransactions(Long userId, int page, int perPage) {
        Wallet wallet = getProvidusWalletForUser(userId);
        return providusExpressGateway.getCustomerTransactions(wallet.getProviderCustomerRef(), page, perPage);
    }

    public Map<String, Object> getProviderTransactionDetails(Long userId, String transactionReference) {
        getProvidusWalletForUser(userId);
        return providusExpressGateway.getTransactionDetails(transactionReference);
    }

    private Wallet getProvidusWalletForUser(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user ID: " + userId));

        if (!providusExpressGateway.isEnabled()) {
            throw new IllegalStateException("Providus integration is disabled.");
        }

        if (wallet.getProviderName() == null
                || !wallet.getProviderName().equalsIgnoreCase(ProvidusExpressGateway.PROVIDER_NAME)) {
            throw new IllegalStateException("This wallet is not a Providus wallet.");
        }

        if (wallet.getProviderCustomerRef() == null || wallet.getProviderCustomerRef().isBlank()) {
            throw new IllegalStateException("Providus customer reference is missing for this wallet.");
        }

        return wallet;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Providus webhook processors
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Processes an incoming deposit/credit webhook event from Providus.
     *
     * <p>Because Providus has not yet shared their exact payload structure, this
     * method tries multiple common field name patterns to extract the recipient's
     * email/account, the credited amount, and the transaction reference.  The raw
     * payload is already logged at INFO level by {@code WebhookService} before this
     * is called, so once the first live webhook arrives the exact field names can be
     * confirmed and this method can be narrowed down.
     */
    @Transactional
    public void fundWalletFromProvidusWebhook(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);

            // ── 1. Extract recipient identity ──────────────────────────────────
            String email = extractProvidusEmail(root);

            // If no email in payload, fall back to account-number lookup
            if (email == null || email.isBlank()) {
                String accountNumber = extractProvidusAccountNumber(root);
                if (accountNumber != null && !accountNumber.isBlank()) {
                    Wallet found = walletRepository.findByAccountNumber(accountNumber).orElse(null);
                    if (found != null && found.getUser() != null) {
                        email = found.getUser().getEmail();
                        logger.info("[PROVIDUS-WEBHOOK] Resolved email {} from accountNumber {}", email, accountNumber);
                    }
                }
            }

            if (email == null || email.isBlank()) {
                logger.error("[PROVIDUS-WEBHOOK] Cannot determine recipient from payload — manual review required:\n{}", payloadJson);
                throw new RuntimeException("Providus deposit webhook: could not identify recipient email or account number");
            }

            // ── 2. Extract amount ──────────────────────────────────────────────
            BigDecimal amount = extractProvidusAmount(root);
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("Providus deposit webhook: could not extract a positive amount");
            }

            // ── 3. Extract reference ───────────────────────────────────────────
            String reference = extractProvidusReference(root);
            if (reference == null || reference.isBlank()) {
                // Generate a fallback reference so we don't lose the credit
                reference = "PRV-" + System.currentTimeMillis();
                logger.warn("[PROVIDUS-WEBHOOK] No reference found in payload — using generated fallback {}", reference);
            }

            // ── 4. Build description ───────────────────────────────────────────
            String description = extractProvidusDescription(root, amount);

            logger.info("[PROVIDUS-WEBHOOK] Processing deposit: email={} amount={} ref={}", email, amount, reference);

            // ── 5. Credit wallet (idempotent — skips if reference already processed)
            processSuccessfulFunding(email, amount, amount, BigDecimal.ZERO, reference, description, LocalDateTime.now());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[PROVIDUS-WEBHOOK] Deposit processing crashed", e);
            throw new RuntimeException("Providus deposit webhook processing failed", e);
        }
    }

    /**
     * Processes a transfer-success or transfer-failed webhook event from Providus.
     *
     * <p>Matches the event to an existing {@link Withdrawal} record by provider reference
     * or client reference, then updates the withdrawal status and notifies the user.
     *
     * @param payloadJson the raw (already normalised) webhook JSON
     * @param isSuccess   {@code true} for TRANSFER_SUCCESSFUL, {@code false} for TRANSFER_FAILED
     */
    @Transactional
    public void processProvidusWithdrawalConfirmation(String payloadJson, boolean isSuccess) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            String reference = extractProvidusReference(root);

            if (reference == null || reference.isBlank()) {
                logger.warn("[PROVIDUS-WEBHOOK] Withdrawal confirmation has no reference — cannot match record. Payload logged above.");
                return;
            }

            // Try provider reference first, then our own client reference
            Withdrawal withdrawal = withdrawalRepository.findByProviderReference(reference)
                    .or(() -> withdrawalRepository.findByClientReference(reference))
                    .orElse(null);

            if (withdrawal == null) {
                logger.warn("[PROVIDUS-WEBHOOK] No withdrawal found for reference {} — may have been processed already or reference mismatch", reference);
                return;
            }

            // Guard against double-processing
            if (withdrawal.getStatus() == WithdrawalStatus.COMPLETED
                    || withdrawal.getStatus() == WithdrawalStatus.FAILED
                    || withdrawal.getStatus() == WithdrawalStatus.REVERSED) {
                logger.info("[PROVIDUS-WEBHOOK] Withdrawal {} already in terminal state {} — skipping", reference, withdrawal.getStatus());
                return;
            }

            if (isSuccess) {
                withdrawal.setStatus(WithdrawalStatus.COMPLETED);
                withdrawal.setCompletedAt(LocalDateTime.now());
                withdrawalRepository.save(withdrawal);

                // Mark transaction log as completed
                transactionLogRepository.findByReference(withdrawal.getClientReference()).ifPresent(log -> {
                    log.setStatus(TransactionStatus.COMPLETED);
                    log.setDescription(log.getDescription() + " | Confirmed by Providus");
                    transactionLogRepository.save(log);
                });

                transactionLogRepository.findByReference(buildWithdrawalFeeReference(withdrawal)).ifPresent(log -> {
                    log.setStatus(TransactionStatus.COMPLETED);
                    log.setDescription(log.getDescription() + " | Confirmed by Providus");
                    transactionLogRepository.save(log);
                });

                // Push notification to user
                String msg = String.format("Your withdrawal of ₦%.2f to %s (%s) was successful.",
                        withdrawal.getAmount(), withdrawal.getAccountNumber(), withdrawal.getBankName());
                CompletableFuture.runAsync(() -> {
                    try {
                        notificationService.sendNotification(
                                withdrawal.getUserId().toString(),
                                msg,
                                NotificationType.WITHDRAWAL,
                                null, null, "VIEW_WALLET", "/wallet"
                        );
                    } catch (Exception e) {
                        logger.error("[PROVIDUS-WEBHOOK] Failed to send withdrawal success notification", e);
                    }
                });

                logger.info("[PROVIDUS-WEBHOOK] Withdrawal {} marked COMPLETED", reference);

            } else {
                // Failure — mark failed and reverse the balance
                String failureReason = extractProvidusFailureReason(root);
                markWithdrawalFailed(withdrawal.getId(), "Providus: " + (failureReason != null ? failureReason : "Transfer failed"));
                logger.info("[PROVIDUS-WEBHOOK] Withdrawal {} marked FAILED — reason: {}", reference, failureReason);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[PROVIDUS-WEBHOOK] Withdrawal confirmation processing crashed", e);
            throw new RuntimeException("Providus withdrawal webhook processing failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rubies webhook processors
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Handles a Rubies transfer-success or transfer-failed webhook.
     *
     * <p>Looks up the {@link Withdrawal} record by the {@code transactionReference}
     * in the webhook payload (= the {@code clientReference} we sent to Rubies).
     * Updates its status, updates the corresponding transaction logs, and sends a
     * push notification to the user.
     *
     * <p>On failure the user's balance is reversed automatically via
     * {@link #markWithdrawalFailed}.
     *
     * @param payloadJson the raw (already normalised) Rubies webhook JSON
     * @param isSuccess   {@code true} = transfer settled, {@code false} = transfer failed
     */
    @Transactional
    public void processRubiesWithdrawalConfirmation(String payloadJson, boolean isSuccess) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);

            // Rubies webhook is FLAT — paymentReference at root level echoes our transactionReference
            String reference = root.path("paymentReference").asText(null);
            if (reference == null || reference.isBlank()) {
                // secondary fallback for any variant field names
                reference = root.path("transactionReference").asText(null);
            }

            if (reference == null || reference.isBlank()) {
                logger.warn("[RUBIES-WEBHOOK] Withdrawal confirmation has no transactionReference — cannot match record");
                return;
            }

            // Match by our clientReference (which we passed as transactionReference to Rubies)
            // or by the sessionId Rubies returned as providerReference
            final String lookupReference = reference;

            Withdrawal withdrawal = withdrawalRepository.findByClientReference(lookupReference)
                    .or(() -> withdrawalRepository.findByProviderReference(lookupReference))
                    .orElse(null);


            if (withdrawal == null) {
                if (reference.startsWith("P2P-RB-")) {
                    // P2P-RB references are settled synchronously in EnvelopeService — no Withdrawal entity exists.
                    logger.info("[RUBIES-WEBHOOK] P2P reference={} already settled in-app — skipping", reference);
                } else if (reference.startsWith("EXT-")) {
                    // EXT- references belong to envelope external transfers, settled by
                    // ExternalTransferSettlementService.settleExternalTransferIfExists() — not here.
                    logger.info("[RUBIES-WEBHOOK] Envelope external transfer reference={} — settlement handled by ExternalTransferSettlementService", reference);
                } else if (reference.startsWith("REV-")) {
                    // REV- references are the markup fee transfers to Moniewise's own Rubies revenue
                    // wallet. Internal accounting was already done at initiation time — nothing to do here.
                    logger.info("[RUBIES-WEBHOOK] Revenue fee collection reference={} — no further action needed", reference);
                } else {
                    logger.warn("[RUBIES-WEBHOOK] No withdrawal found for reference={} — unexpected", reference);
                }
                return;
            }

            // Guard: don't double-process a terminal state
            if (withdrawal.getStatus() == WithdrawalStatus.COMPLETED
                    || withdrawal.getStatus() == WithdrawalStatus.FAILED
                    || withdrawal.getStatus() == WithdrawalStatus.REVERSED) {
                logger.info("[RUBIES-WEBHOOK] Withdrawal {} already in terminal state {} — skipping",
                        reference, withdrawal.getStatus());
                return;
            }

            if (isSuccess) {
                withdrawal.setStatus(WithdrawalStatus.COMPLETED);
                withdrawal.setCompletedAt(LocalDateTime.now());
                withdrawalRepository.save(withdrawal);

                // Mark transaction logs as completed
                transactionLogRepository.findByReference(withdrawal.getClientReference()).ifPresent(log -> {
                    log.setStatus(TransactionStatus.COMPLETED);
                    log.setDescription(log.getDescription() + " | Confirmed by Rubies");
                    transactionLogRepository.save(log);
                });
                transactionLogRepository.findByReference(buildWithdrawalFeeReference(withdrawal)).ifPresent(log -> {
                    log.setStatus(TransactionStatus.COMPLETED);
                    log.setDescription(log.getDescription() + " | Confirmed by Rubies");
                    transactionLogRepository.save(log);
                });

                // ── Credit markup fee to revenue wallet ───────────────────────
                // The fee was already deducted from the user at reservation time.
                // Now that the transfer is confirmed, move it to the revenue wallet.
                BigDecimal markupFee = withdrawal.getFeeAmount() != null
                        ? withdrawal.getFeeAmount() : BigDecimal.ZERO;

                if (markupFee.compareTo(BigDecimal.ZERO) > 0) {
                    // 1. Credit the internal revenue wallet (accounting ledger).
                    walletRepository.findByRevenueWalletTrue().ifPresent(revenueWallet -> {
                        revenueWallet.setBalance(revenueWallet.getBalance().add(markupFee));
                        revenueWallet.setUpdatedAt(LocalDateTime.now());
                        walletRepository.save(revenueWallet);

                        RevenueLog revenueLog = new RevenueLog();
                        revenueLog.setUserId(withdrawal.getUserId());
                        revenueLog.setType("transfer_markup_fee");
                        revenueLog.setAmount(markupFee);
                        revenueLog.setDescription("Markup fee for transfer " + withdrawal.getClientReference()
                                + " — user " + withdrawal.getUserId());
                        revenueLog.setCreatedAt(LocalDateTime.now());
                        revenueLogRepository.save(revenueLog);

                        logger.info("[RUBIES] Markup fee ₦{} credited to revenue wallet for ref={}",
                                markupFee, withdrawal.getClientReference());
                    });

                    // 2. Fire-and-forget: actually move the markup fee from the user's
                    //    Rubies wallet into Moniewise's own Rubies revenue wallet.
                    //    Done HERE (on confirmed success) — NOT at initiation time —
                    //    so we never collect a fee for a transfer that Rubies rejected.
                    walletRepository.findByUserId(withdrawal.getUserId()).ifPresent(userWallet -> {
                        if (userWallet.getProviderWalletRef() != null) {
                            User transferUser = userRepository.findById(withdrawal.getUserId()).orElse(null);
                            String fromName = transferUser != null
                                    ? resolveDisplayName(transferUser) : "Moniewise User";
                            collectRubiesMarkupFeeAsync(
                                    markupFee,
                                    userWallet.getProviderWalletRef(),
                                    fromName,
                                    withdrawal.getClientReference(),
                                    withdrawal.getUserId()
                            );
                        }
                    });
                }

                // Push notification
                final String msg = String.format("Your transfer of ₦%.2f to %s (%s) was successful.",
                        withdrawal.getAmount(), withdrawal.getAccountName(), withdrawal.getBankName());
                CompletableFuture.runAsync(() -> {
                    try {
                        notificationService.sendNotification(
                                withdrawal.getUserId().toString(), msg,
                                NotificationType.WITHDRAWAL, null, null, "VIEW_WALLET", "/wallet"
                        );
                    } catch (Exception e) {
                        logger.error("[RUBIES-WEBHOOK] Failed to send transfer success notification", e);
                    }
                });

                logger.info("[RUBIES-WEBHOOK] Withdrawal {} marked COMPLETED", reference);

            } else {
                // Failure: reverse balance automatically
                // narration is at root level in the flat Rubies webhook payload
                String failureReason = root.path("narration").asText("Transfer failed");
                markWithdrawalFailed(withdrawal.getId(), "Rubies: " + failureReason);
                logger.info("[RUBIES-WEBHOOK] Withdrawal {} marked FAILED — reason: {}", reference, failureReason);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[RUBIES-WEBHOOK] Withdrawal confirmation processing crashed", e);
            throw new RuntimeException("Rubies withdrawal webhook processing failed", e);
        }
    }

    /**
     * Handles an inbound credit to a Rubies wallet (someone sent money to the user).
     *
     * <p>Rubies sends the credit to the user's account number. We look up the wallet
     * by {@code creditAccountNumber}, credit the internal balance, and notify the user.
     *
     * @param payloadJson the raw Rubies webhook JSON for a credit/deposit event
     */
    @Transactional
    public void processRubiesDepositWebhook(String payloadJson) {
        try {
            // Rubies webhook is FLAT — no nested "data" wrapper.
            // Fields: paymentReference, creditAccount, drCr, amount, narration, responseCode, sessionId
            JsonNode root = objectMapper.readTree(payloadJson);

            // Only process genuine inbound credits
            String drCr         = root.path("drCr").asText(null);
            String responseCode = root.path("responseCode").asText(null);
            if (!"CR".equalsIgnoreCase(drCr) || !"00".equals(responseCode)) {
                logger.warn("[RUBIES-WEBHOOK] Deposit webhook drCr={} responseCode={} — not a successful credit, skipping",
                        drCr, responseCode);
                return;
            }

            String creditAccountNumber = root.path("creditAccount").asText(null);
            String reference           = root.path("paymentReference").asText(null);
            String amountStr           = root.path("amount").asText(null);
            String narration           = root.path("narration").asText("Inbound transfer");

            if (creditAccountNumber == null || creditAccountNumber.isBlank()) {
                logger.error("[RUBIES-WEBHOOK] Deposit webhook missing creditAccount — raw: {}", payloadJson);
                return;
            }
            if (reference == null || reference.isBlank()) {
                reference = "RUB-DEP-" + System.currentTimeMillis();
                logger.warn("[RUBIES-WEBHOOK] Deposit webhook missing transactionReference — using fallback {}", reference);
            }

            BigDecimal amount;
            try {
                amount = new BigDecimal(amountStr != null ? amountStr.trim() : "0");
            } catch (NumberFormatException e) {
                logger.error("[RUBIES-WEBHOOK] Cannot parse deposit amount '{}' for acct={}", amountStr, creditAccountNumber);
                return;
            }

            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("[RUBIES-WEBHOOK] Deposit amount is zero/negative for acct={}", creditAccountNumber);
                return;
            }

            // Skip credits arriving at Moniewise's own Rubies revenue wallet —
            // those are the REV- markup fee transfers we initiated ourselves.
            // Internal accounting was already done inside processRubiesWithdrawalConfirmation/
            // ExternalTransferSettlementService when the DR SUCCESS webhook arrived.
            // No user wallet exists for this account number — silently ignore.
            String revenueAcct = systemConfigService.getString(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER);
            if (revenueAcct != null && revenueAcct.equalsIgnoreCase(creditAccountNumber)) {
                logger.debug("[RUBIES-WEBHOOK] Credit to Moniewise revenue wallet acct={} ref={} — already accounted, skipping",
                        creditAccountNumber, reference);
                return;
            }

            // Find the wallet by the Rubies account number
            Wallet wallet = walletRepository.findByAccountNumber(creditAccountNumber).orElse(null);
            if (wallet == null) {
                // Try by providerWalletRef as fallback
                wallet = walletRepository.findByProviderWalletRef(creditAccountNumber).orElse(null);
            }
            if (wallet == null || wallet.getUser() == null) {
                logger.error("[RUBIES-WEBHOOK] No wallet found for creditAccountNumber={}", creditAccountNumber);
                return;
            }

            String sessionId = root.path("sessionId").asText(null);
            String originatorName = root.path("originatorName").asText(null);
            Optional<TransactionLog> internalP2pCreditLog =
                    findRubiesP2pCreditLog(wallet.getUser().getId(), reference, sessionId);

            if (isRubiesP2pCredit(reference, internalP2pCreditLog)) {
                logger.info("[RUBIES-WEBHOOK] Processing internal P2P credit: acct={} amount={} ref={} sessionId={}",
                        creditAccountNumber, amount, reference, sessionId);
                settleRubiesP2pCredit(wallet, internalP2pCreditLog, amount, reference, sessionId, originatorName);
                return;
            }

            // ── Reversal detection ─────────────────────────────────────────────
            // When Rubies reverses a previously COMPLETED outbound transfer (e.g. NIP
            // timeout, receiving bank rejection), the funds come back as a CR webhook.
            // The narration typically contains "reversal". If we detect this, mark the
            // original withdrawal as REVERSED and credit the wallet — don't treat it
            // as a regular deposit.
            String narrationLower = narration.toLowerCase();
            if (narrationLower.contains("reversal") || narrationLower.contains("reversed")) {
                Long userId = wallet.getUser().getId();
                Optional<Withdrawal> reversedWithdrawal =
                        withdrawalRepository.findTopByUserIdAndAmountAndStatusOrderByCompletedAtDesc(
                                userId, amount, WithdrawalStatus.COMPLETED);

                if (reversedWithdrawal.isPresent()) {
                    Withdrawal wd = reversedWithdrawal.get();
                    wd.setStatus(WithdrawalStatus.REVERSED);
                    wd.setFailureReason("Bank reversal: " + narration);
                    wd.setProcessedAt(LocalDateTime.now());
                    withdrawalRepository.save(wd);

                    transactionLogRepository.findByReference(wd.getClientReference()).ifPresent(log -> {
                        log.setStatus(TransactionStatus.REVERSED);
                        log.setDescription(log.getDescription() + " | Bank reversal: " + narration);
                        transactionLogRepository.save(log);
                    });
                    transactionLogRepository.findByReference(buildWithdrawalFeeReference(wd)).ifPresent(log -> {
                        log.setStatus(TransactionStatus.REVERSED);
                        transactionLogRepository.save(log);
                    });

                    wallet.setBalance(wallet.getBalance().add(wd.getTotalDebit()));
                    wallet.setUpdatedAt(LocalDateTime.now());
                    walletRepository.save(wallet);
                    monnieCacheInvalidationService.evictUserAfterCommit(userId);
                    evictWalletCache(userId);

                    logger.info("[RUBIES-WEBHOOK] Bank reversal detected: ref={} amount=₦{} original_wd={} — wallet credited ₦{}",
                            reference, amount, wd.getClientReference(), wd.getTotalDebit());

                    final String revMsg = String.format(
                            "Your transfer of ₦%,.2f was reversed by the receiving bank. " +
                            "₦%,.2f (including fees) has been refunded to your wallet.",
                            amount, wd.getTotalDebit());
                    CompletableFuture.runAsync(() -> {
                        try {
                            notificationService.sendNotification(
                                    userId.toString(), revMsg,
                                    NotificationType.WITHDRAWAL, null, null, "VIEW_WALLET", "/wallet"
                            );
                        } catch (Exception e) {
                            logger.error("[RUBIES-WEBHOOK] Failed to send reversal notification", e);
                        }
                    });
                    return;
                }
                logger.info("[RUBIES-WEBHOOK] Narration contains 'reversal' but no matching COMPLETED withdrawal found " +
                        "for user={} amount=₦{} — processing as regular deposit", wallet.getUser().getId(), amount);
            }

            String description = "Inbound transfer: " + narration;
            logger.info("[RUBIES-WEBHOOK] Processing deposit: acct={} amount={} ref={}", creditAccountNumber, amount, reference);

            // Detect P2P transfers: EnvelopeService saves the credit log as
            // "P2P-RB-CR-{providerReference}" before the webhook arrives.
            // If that log exists it means:
            //   1. EnvelopeService already sent the recipient a WALLET_DEPOSIT notification.
            //   2. The balance was NOT credited by EnvelopeService (Rubies handles it) —
            //      so processSuccessfulFunding MUST still run to update the balance.
            //   3. But the notification must be suppressed to avoid a duplicate.
            boolean isInternalP2p = transactionLogRepository
                    .existsByReference("P2P-RB-CR-" + reference);

            if (isInternalP2p) {
                logger.info("[RUBIES-WEBHOOK] P2P credit ref={} — balance update only, " +
                        "suppressing duplicate notification (EnvelopeService already sent one)", reference);
            }

            // Credit the internal wallet (idempotent — skips if reference already processed)
            processSuccessfulFunding(
                    wallet.getUser().getEmail(),
                    amount,
                    amount,
                    BigDecimal.ZERO,
                    reference,
                    description,
                    LocalDateTime.now(),
                    isInternalP2p   // suppress notification for P2P credits
            );

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[RUBIES-WEBHOOK] Deposit processing crashed", e);
            throw new RuntimeException("Rubies deposit webhook processing failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Providus payload extraction helpers
    // (multi-pattern because Providus hasn't confirmed their exact field names)
    // ─────────────────────────────────────────────────────────────────────────────

    private Optional<TransactionLog> findRubiesP2pCreditLog(Long userId, String paymentReference, String sessionId) {
        Set<String> candidateReferences = new LinkedHashSet<>();
        if (isPresent(paymentReference)) {
            candidateReferences.add(RUBIES_P2P_CREDIT_REF_PREFIX + paymentReference);
        }
        if (isPresent(sessionId)) {
            candidateReferences.add(RUBIES_P2P_CREDIT_REF_PREFIX + sessionId);
        }

        if (!candidateReferences.isEmpty()) {
            Optional<TransactionLog> byReference =
                    transactionLogRepository.findFirstByUserIdAndTransactionTypeAndReferenceInOrderByCreatedAtDesc(
                            userId,
                            TransactionType.USER_TO_ENVELOPE,
                            candidateReferences
                    );
            if (byReference.isPresent()) {
                return byReference;
            }
        }

        if (isPresent(sessionId)) {
            Optional<TransactionLog> bySession =
                    transactionLogRepository.findFirstByUserIdAndTransactionTypeAndProviderReferenceOrderByCreatedAtDesc(
                            userId,
                            TransactionType.USER_TO_ENVELOPE,
                            sessionId
                    );
            if (bySession.isPresent()) {
                return bySession;
            }
        }

        if (isPresent(paymentReference)) {
            return transactionLogRepository.findFirstByUserIdAndTransactionTypeAndProviderReferenceOrderByCreatedAtDesc(
                    userId,
                    TransactionType.USER_TO_ENVELOPE,
                    paymentReference
            );
        }

        return Optional.empty();
    }

    private boolean isRubiesP2pCredit(String paymentReference, Optional<TransactionLog> creditLog) {
        return creditLog.isPresent()
                || (isPresent(paymentReference) && paymentReference.startsWith("P2P-RB-"));
    }

    private void settleRubiesP2pCredit(
            Wallet wallet,
            Optional<TransactionLog> creditLog,
            BigDecimal amount,
            String paymentReference,
            String sessionId,
            String originatorName
    ) {
        String markerReference = rubiesP2pSettlementMarkerReference(paymentReference, sessionId);
        if (!isPresent(markerReference)) {
            logger.error("[RUBIES-WEBHOOK] P2P credit missing stable reference; cannot settle safely");
            return;
        }

        if (transactionLogRepository.existsByReference(markerReference)) {
            logger.info("[RUBIES-WEBHOOK] P2P credit already settled marker={} ref={} sessionId={}",
                    markerReference, paymentReference, sessionId);
            completeRubiesP2pCreditLog(creditLog, sessionId);
            return;
        }

        if (isPresent(paymentReference) && transactionLogRepository.existsByReference(paymentReference)) {
            logger.warn("[RUBIES-WEBHOOK] P2P credit ref={} was already processed as a generic deposit; skipping balance update",
                    paymentReference);
            completeRubiesP2pCreditLog(creditLog, sessionId);
            return;
        }

        TransactionLog marker = new TransactionLog();
        marker.setUserId(wallet.getUser().getId());
        marker.setAmount(amount);
        marker.setFee(BigDecimal.ZERO);
        marker.setTransactionType(TransactionType.P2P_RUBIES_SETTLEMENT);
        marker.setReference(markerReference);
        marker.setProviderName(RubiesGateway.PROVIDER_NAME);
        marker.setProviderReference(isPresent(sessionId) ? sessionId : paymentReference);
        marker.setDescription("Rubies P2P credit webhook processed");
        marker.setStatus(TransactionStatus.COMPLETED);
        marker.setCreatedAt(LocalDateTime.now());
        transactionLogRepository.save(marker);

        wallet.setBalance(wallet.getBalance().add(amount));
        wallet.setUpdatedAt(LocalDateTime.now());
        wallet.setLastBalanceSyncAt(LocalDateTime.now());
        walletRepository.save(wallet);
        monnieCacheInvalidationService.evictUserAfterCommit(wallet.getUser().getId());
        evictWalletCache(wallet.getUser().getId());

        completeRubiesP2pCreditLog(creditLog, sessionId);
        sendRubiesP2pCreditNotification(wallet.getUser().getId(), amount, creditLog, originatorName);
    }

    private String rubiesP2pSettlementMarkerReference(String paymentReference, String sessionId) {
        if (isPresent(paymentReference) && !paymentReference.startsWith("RUB-DEP-")) {
            return RUBIES_P2P_SETTLEMENT_MARKER_PREFIX + paymentReference;
        }
        if (isPresent(sessionId)) {
            return RUBIES_P2P_SETTLEMENT_MARKER_PREFIX + sessionId;
        }
        return null;
    }

    private void completeRubiesP2pCreditLog(Optional<TransactionLog> creditLog, String sessionId) {
        creditLog.ifPresent(log -> {
            boolean changed = false;
            if (log.getStatus() != TransactionStatus.COMPLETED) {
                log.setStatus(TransactionStatus.COMPLETED);
                changed = true;
            }
            if (!isPresent(log.getProviderReference()) && isPresent(sessionId)) {
                log.setProviderReference(sessionId);
                changed = true;
            }
            if (changed) {
                transactionLogRepository.save(log);
            }
        });
    }

    private void sendRubiesP2pCreditNotification(
            Long userId,
            BigDecimal amount,
            Optional<TransactionLog> creditLog,
            String originatorName
    ) {
        try {
            String senderName = resolveRubiesP2pSenderName(creditLog, originatorName);
            String message = String.format("\u20A6%,.2f has been credited to your wallet from %s.",
                    amount, senderName);
            notificationService.sendNotification(
                    userId.toString(),
                    message,
                    NotificationType.WALLET_DEPOSIT,
                    null,
                    null,
                    "VIEW_WALLET",
                    "/dashboard"
            );
        } catch (Exception e) {
            logger.error("[RUBIES-WEBHOOK] Failed to send P2P credit notification for userId={}", userId, e);
        }
    }

    private String resolveRubiesP2pSenderName(Optional<TransactionLog> creditLog, String originatorName) {
        if (creditLog.isPresent() && creditLog.get().getCounterpartyUserId() != null) {
            return userRepository.findById(creditLog.get().getCounterpartyUserId())
                    .map(this::resolveDisplayName)
                    .orElse("a Wisemonie user");
        }
        if (isPresent(originatorName)) {
            return originatorName.trim();
        }
        return "a Wisemonie user";
    }

    private String extractProvidusEmail(JsonNode root) {
        // Root-level
        String val = root.path("email").asText(null);
        if (isPresent(val)) return val;
        // customer.email
        val = root.path("customer").path("email").asText(null);
        if (isPresent(val)) return val;
        // data.email / data.customer.email
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            val = data.path("email").asText(null);
            if (isPresent(val)) return val;
            val = data.path("customer").path("email").asText(null);
            if (isPresent(val)) return val;
        }
        // eventData.customer.email (SecureWave-style)
        val = root.path("eventData").path("customer").path("email").asText(null);
        if (isPresent(val)) return val;
        // wallet.email
        val = root.path("wallet").path("email").asText(null);
        if (isPresent(val)) return val;
        return null;
    }

    private String extractProvidusAccountNumber(JsonNode root) {
        for (String field : new String[]{"accountNumber", "destinationAccountNumber",
                "walletAccountNumber", "creditAccountNumber", "account_number"}) {
            String val = root.path(field).asText(null);
            if (isPresent(val)) return val;
        }
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            for (String field : new String[]{"accountNumber", "destinationAccountNumber", "account_number"}) {
                String val = data.path(field).asText(null);
                if (isPresent(val)) return val;
            }
        }
        // wallet.accountNumber
        String val = root.path("wallet").path("accountNumber").asText(null);
        if (isPresent(val)) return val;
        return null;
    }

    private BigDecimal extractProvidusAmount(JsonNode root) {
        for (String field : new String[]{"amount", "amountPaid", "value", "credit_amount", "settlementAmount"}) {
            JsonNode node = root.path(field);
            if (!node.isMissingNode() && !node.isNull()) return decimalFromNode(node);
        }
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            for (String field : new String[]{"amount", "amountPaid", "value"}) {
                JsonNode node = data.path(field);
                if (!node.isMissingNode() && !node.isNull()) return decimalFromNode(node);
            }
        }
        JsonNode eventData = root.path("eventData");
        if (!eventData.isMissingNode()) {
            JsonNode node = eventData.path("amountPaid");
            if (!node.isMissingNode() && !node.isNull()) return decimalFromNode(node);
        }
        return null;
    }

    private String extractProvidusReference(JsonNode root) {
        for (String field : new String[]{"reference", "transactionReference", "transaction_reference",
                "ref", "transactionId", "transaction_id", "txRef", "tx_ref"}) {
            String val = root.path(field).asText(null);
            if (isPresent(val)) return val;
        }
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            for (String field : new String[]{"reference", "transactionReference", "transaction_reference", "ref", "tx_ref"}) {
                String val = data.path(field).asText(null);
                if (isPresent(val)) return val;
            }
        }
        String val = root.path("eventData").path("transactionReference").asText(null);
        if (isPresent(val)) return val;
        return null;
    }

    private String extractProvidusDescription(JsonNode root, BigDecimal amount) {
        for (String field : new String[]{"narration", "description", "paymentDescription",
                "remark", "remarks", "memo"}) {
            String val = root.path(field).asText(null);
            if (isPresent(val)) return val;
        }
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            for (String field : new String[]{"narration", "description", "remark"}) {
                String val = data.path(field).asText(null);
                if (isPresent(val)) return val;
            }
        }
        return String.format("Providus Deposit of ₦%.2f", amount);
    }

    private String extractProvidusFailureReason(JsonNode root) {
        for (String field : new String[]{"message", "reason", "failureReason",
                "failure_reason", "responseMessage", "responseDescription"}) {
            String val = root.path(field).asText(null);
            if (isPresent(val)) return val;
        }
        return "Transfer failed";
    }

    private boolean isPresent(String val) {
        return val != null && !val.isBlank() && !"null".equalsIgnoreCase(val);
    }

    private BigDecimal decimalFromNode(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(node.asText());
    }
}
