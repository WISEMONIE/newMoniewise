package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.config.BudgetLifeCycleManager;
import com.moniewise.moniewise_backend.config.GenericNotificationEvent;
import com.moniewise.moniewise_backend.controller.BudgetController;
import com.moniewise.moniewise_backend.dto.ExternalTransferQuoteResponse;
import com.moniewise.moniewise_backend.dto.TransferFeeQuote;
import com.moniewise.moniewise_backend.dto.response.WithdrawalQuoteResponse;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.request.P2PTransferRequest;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.dto.response.ExternalTransferResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.*;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.externalTransfers.PaymentProvider;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.ProvidusExpressGateway;
import com.moniewise.moniewise_backend.psp.SecureWaveGateway;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EnvelopeService {

    private static final Logger logger = LoggerFactory.getLogger(EnvelopeService.class);

    /** Rubies MFB NIP bank code — used for internal (Rubies-to-Rubies) P2P book transfers. */
    private static final String RUBIES_BANK_CODE = "090175";
    private static final String RUBIES_BANK_NAME = "Rubies MFB";

    private final EnvelopeRepository envelopeRepository;
    private final BudgetRepository budgetRepository;
    private final RevenueLogRepository revenueLogRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionLogRepository transactionLogRepository;
//    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final WalletService walletService;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final BudgetLifeCycleManager budgetLifeCycleManager;
    private final BudgetService budgetService;
    private final PendingDisbursementRepository pendingDisbursementRepository;
    private final JdbcTemplate jdbcTemplate;
    private final BeneficiaryService beneficiaryService;
    private final PaymentProvider paymentProvider;
    private final ProvidusExpressGateway providusExpressGateway;
    private final PaymentGatewayResolver paymentGatewayResolver;

    private final TransferFeeService transferFeeService;
    private final MonnieCacheInvalidationService monnieCacheInvalidationService;
    private final ProcessingTransferRecoveryScheduler transferRecoveryScheduler;
    private final PayeelordVasTransactionRepository vasTransactionRepository;
    private final ActivationJourneyNudgeService activationJourneyNudgeService;

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    public EnvelopeService(
            EnvelopeRepository envelopeRepository,
            BudgetRepository budgetRepository,
            RevenueLogRepository revenueLogRepository,
            UserService userService,
            TransactionLogRepository transactionLogRepository,
            NotificationService notificationService,
            ApplicationEventPublisher eventPublisher, WalletService walletService,
            ScheduledTaskRepository scheduledTaskRepository,
            @Lazy BudgetLifeCycleManager budgetLifeCycleManager,
            BudgetService budgetService, PendingDisbursementRepository pendingDisbursementRepository,
            JdbcTemplate jdbcTemplate, BeneficiaryService beneficiaryService, PaymentProvider paymentProvider,
            ProvidusExpressGateway providusExpressGateway, PaymentGatewayResolver paymentGatewayResolver,
            TransferFeeService transferFeeService,
            MonnieCacheInvalidationService monnieCacheInvalidationService,
            @Lazy ProcessingTransferRecoveryScheduler transferRecoveryScheduler,
            PayeelordVasTransactionRepository vasTransactionRepository,
            ActivationJourneyNudgeService activationJourneyNudgeService) {
        this.envelopeRepository = envelopeRepository;
        this.budgetRepository = budgetRepository;
        this.revenueLogRepository = revenueLogRepository;
        this.userService = userService;
        this.transactionLogRepository = transactionLogRepository;
        this.eventPublisher = eventPublisher;
//        this.notificationService = notificationService;
        this.walletService = walletService;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.budgetLifeCycleManager = budgetLifeCycleManager;
        this.budgetService = budgetService;
        this.pendingDisbursementRepository = pendingDisbursementRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.beneficiaryService = beneficiaryService;
        this.paymentProvider = paymentProvider;
        this.providusExpressGateway = providusExpressGateway;
        this.paymentGatewayResolver = paymentGatewayResolver;
        this.transferFeeService = transferFeeService;
        this.monnieCacheInvalidationService = monnieCacheInvalidationService;
        this.transferRecoveryScheduler = transferRecoveryScheduler;
        this.vasTransactionRepository = vasTransactionRepository;
        this.activationJourneyNudgeService = activationJourneyNudgeService;
    }

    @PostConstruct
    public void init() {
        logger.info("Revenue Wallet User ID: {}", revenueWalletUserId);
    }

    // Ã¢Å“â€¦ TIMEZONE FIX: Uses Lagos Time for consistency
    private LocalDateTime fetchCurrentDateTimeFromDatabase() {
        return ZonedDateTime.now(ZoneId.of("Africa/Lagos")).toLocalDateTime();
    }

    // Restored this method from your original code
    private LocalDateTime fetchUserLocalTime(User user) {
        ZonedDateTime nowUTC = ZonedDateTime.now(ZoneId.of("UTC"));
        return nowUTC.withZoneSameInstant(user.getZoneId()).toLocalDateTime();
    }

    private String processProvidusP2pIfEnabled(User sender, User recipient, BigDecimal amount) {
        if (!providusExpressGateway.isEnabled()) {
            return null;
        }

        Wallet senderWallet = walletService.getWalletByUserId(sender.getId());
        Wallet recipientWallet = walletService.getWalletByUserId(recipient.getId());

        if (!isProvidusWallet(senderWallet) || !isProvidusWallet(recipientWallet)) {
            throw new IllegalStateException("Providus P2P is enabled, but both users do not have Providus wallets.");
        }

        if (senderWallet.getProviderCustomerRef() == null || senderWallet.getProviderCustomerRef().isBlank()) {
            throw new IllegalStateException("Sender Providus customer reference is missing.");
        }

        if (recipientWallet.getAccountNumber() == null || recipientWallet.getAccountNumber().isBlank()) {
            throw new IllegalStateException("Recipient Providus account number is missing.");
        }

        String reference = providusExpressGateway.initiateWalletTransfer(
                senderWallet.getProviderCustomerRef(),
                recipientWallet.getAccountNumber(),
                amount
        );

        logger.info("Providus P2P transfer completed from user {} to user {} with reference {}",
                sender.getId(), recipient.getId(), reference);

        return reference;
    }

    private boolean isProvidusWallet(Wallet wallet) {
        return wallet != null
                && wallet.getProviderName() != null
                && wallet.getProviderName().equalsIgnoreCase(ProvidusExpressGateway.PROVIDER_NAME);
    }

    private String initiateProvidusExternalTransfer(User sender,
                                                    BudgetController.ExternalAccount externalAccount,
                                                    String resolvedAccountName,
                                                    BigDecimal amount,
                                                    String reference,
                                                    String narration) {
        Wallet senderWallet = walletService.getWalletByUserId(sender.getId());

        if (!isProvidusWallet(senderWallet)) {
            throw new IllegalStateException("Providus integration is enabled, but the sender does not have a Providus wallet.");
        }

        if (senderWallet.getProviderCustomerRef() == null || senderWallet.getProviderCustomerRef().isBlank()) {
            throw new IllegalStateException("Sender Providus customer reference is missing.");
        }

        return providusExpressGateway.initiateCustomerBankTransfer(
                senderWallet.getProviderCustomerRef(),
                externalAccount.getBankCode(),
                externalAccount.getAccountNumber(),
                resolvedAccountName,
                getSafeName(sender),
                amount,
                reference,
                narration
        );
    }

    // =========================================================================
    // 1. MOVE MONEY (INTERNAL) - FIXED Ã¢Å“â€¦
    // =========================================================================
    @Transactional
    public void moveMoney(Long sourceId, Long targetId, Double amount, String email, String withdrawalReason) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (sourceId.equals(targetId)) throw new IllegalArgumentException("Source and target envelopes must be different");

        User user = userService.findByEmail(email);

        List<Envelope> lockedEnvelopes = envelopeRepository.findAllByIdInForUpdate(List.of(sourceId, targetId));
        Envelope source = lockedEnvelopes.stream()
                .filter(envelope -> envelope.getId().equals(sourceId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Source not found"));
        Envelope target = lockedEnvelopes.stream()
                .filter(envelope -> envelope.getId().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Target not found"));

        if (!source.getBudget().getUser().getId().equals(user.getId())
                || !target.getBudget().getUser().getId().equals(user.getId())) {
            throw new EntityNotFoundException("Envelope not found");
        }

        assertNoAutoTransferInProgress(sourceId);
        Budget sourceBudget = source.getBudget();

        if (!sourceBudget.getId().equals(target.getBudget().getId())) {
            throw new IllegalArgumentException("Envelopes must belong to the same budget");
        }
        if (sourceBudget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Budget must be active");
        }

        validateTransferRules(source, sourceBudget, now);

        // Ã°Å¸â€ºâ€˜ FIX START: RE-FETCH LOGIC FOR MOVE MONEY
        getRemainingLimit(source, email); // Force DB Update
        // Ã°Å¸â€ºâ€˜ FIX END

        BigDecimal transferAmount = BigDecimal.valueOf(amount);
        if (transferAmount.compareTo(source.getRemainingAmount()) > 0) {
            throw new IllegalArgumentException("Insufficient spendable limit. Available: Ã¢â€šÂ¦" + source.getRemainingAmount());
        }
        if (transferAmount.compareTo(source.getTotalRemainingAmount()) > 0) {
            throw new IllegalArgumentException("Insufficient funds in vault.");
        }

        // Subtract Source
        BigDecimal newSourceTotal = source.getTotalRemainingAmount().subtract(transferAmount);
        BigDecimal newSourcePocket = source.getRemainingAmount().subtract(transferAmount);
        source.setTotalRemainingAmount(newSourceTotal);
        source.setRemainingAmount(newSourcePocket);

//        recalculateTargetEnvelopeLimit(source, sourceBudget);
        envelopeRepository.save(source);
        envelopeRepository.flush();

        // Add Target
        target.setTotalRemainingAmount(target.getTotalRemainingAmount().add(transferAmount));
        target.setRemainingAmount(target.getRemainingAmount().add(transferAmount));

        recalculateTargetEnvelopeLimit(target, sourceBudget);
        envelopeRepository.save(target);

        // Logs
        String description;
        String type = (String) source.getConditions().getOrDefault("type", "");

        if ("emergency".equalsIgnoreCase(type)) {
            if (withdrawalReason == null || withdrawalReason.trim().isEmpty()) {
                throw new IllegalArgumentException("Emergency withdrawals require a valid reason.");
            } else {
                description = String.format("EMERGENCY WITHDRAWAL: %s (To: %s)",
                        withdrawalReason, target.getName());
            }
        } else {
            description = String.format("From %s Ã¢â€ â€™ %s Ã¢â‚¬Â¢ Moved Ã¢â€šÂ¦%.2f",
                    source.getName(), target.getName(), transferAmount);
        }

        TransactionLog transactionLog = new TransactionLog(
                user.getId(), sourceBudget.getId(), sourceId, targetId, transferAmount,
                TransactionType.ENVELOPE_TO_ENVELOPE, description
        );
        transactionLog.setStatus(TransactionStatus.COMPLETED);
        transactionLog.setReference("ENV-MOV-" + sourceId + "-" + System.currentTimeMillis());
        transactionLog.setCreatedAt(now);
        transactionLogRepository.save(transactionLog);

        BigDecimal remainingLimit = newSourcePocket;
        String period = source.getConditions().getOrDefault("type", "period").toString().equals("daily") ? "today" : "this period";

//        notificationService.sendNotification(
//                user.getId().toString(),
//                String.format("Moved Ã¢â€šÂ¦%.2f. %s Remaining: Ã¢â€šÂ¦%.2f.", transferAmount, period, remainingLimit),
//                NotificationType.ENVELOPE_TRANSFER,
//                sourceBudget.getId(), sourceId, "VIEW_ENVELOPE", "/envelopes/" + sourceId
//        );
        // Ã¢Å“â€¦ ADD THIS NEW BLOCK
        Map<String, Object> params = Map.of(
                "amount", String.format("%,.2f", transferAmount),
                "period", source.getConditions().getOrDefault("type", "period").equals("daily") ? "today" : "this period",
                "remaining", String.format("%,.2f", newSourcePocket)
        );

        eventPublisher.publishEvent(new GenericNotificationEvent(
                this,
                user.getId().toString(),
                NotificationType.ENVELOPE_TRANSFER,
                params,
                sourceBudget.getId(),
                sourceId,
                "/envelopes/" + sourceId
        ));
    }

    // =========================================================================
    // 2. P2P TRANSFER - FIXED & CLEANED Ã¢Å“â€¦
    // =========================================================================
    @Transactional(rollbackFor = Exception.class)
    public void transferToMonieWiseUser(P2PTransferRequest request, String senderEmail) {

        BigDecimal amount = request.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        User sender = userService.findByEmail(senderEmail);
        if (request.getTransactionPin() == null || request.getTransactionPin().isBlank()) {
            throw new IllegalArgumentException("Transaction PIN is required");
        }
        if (!userService.verifyTransactionPin(sender, request.getTransactionPin())) {
            throw new IllegalArgumentException("Invalid transaction PIN");
        }

        User recipient = userService.findByEmailOrPhone(request.getRecipientIdentity())
                .orElseThrow(() -> new EntityNotFoundException("Recipient not found"));

        if (sender.getId().equals(recipient.getId())) {
            throw new IllegalArgumentException("You cannot transfer to yourself.");
        }

        // Initial Load
        Envelope sourceEnvelope = envelopeRepository.findByIdAndBudgetUserEmailForUpdate(request.getSourceEnvelopeId(), senderEmail)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));

        assertNoAutoTransferInProgress(sourceEnvelope.getId());

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        validateTransferRules(sourceEnvelope, sourceEnvelope.getBudget(), now);

        if (sourceEnvelope.getMaturedAt() != null && sourceEnvelope.getMaturedAt().isAfter(now)) {
            throw new IllegalStateException("This envelope is locked until " + sourceEnvelope.getMaturedAt().toLocalDate());
        }

        // Ã°Å¸â€ºâ€˜ FIX START: RE-FETCH LOGIC
        getRemainingLimit(sourceEnvelope, senderEmail); // Force DB Update
        // Ã°Å¸â€ºâ€˜ FIX END

        // Validate Funds (Fresh Data)
        if (amount.compareTo(sourceEnvelope.getRemainingAmount()) > 0) {
            String cleanLimit = String.format("%,.2f", sourceEnvelope.getRemainingAmount());
            throw new IllegalStateException("Transfer exceeds your spending limit. Available: Ã¢â€šÂ¦" + cleanLimit);
        }
//        if (amount.compareTo(sourceEnvelope.getTotalRemainingAmount()) > 0) {
//            throw new IllegalStateException("Insufficient funds in vault.");
//        }


//        BigDecimal availableVaultBalance = totalRemaining.subtract(heldAmount);
        BigDecimal availableVaultBalance =
                safeAmount(sourceEnvelope.getTotalRemainingAmount())
                        .subtract(safeAmount(sourceEnvelope.getHeldAmount()));

        if (amount.compareTo(availableVaultBalance) > 0) {
            throw new IllegalStateException("Insufficient funds in vault.");
        }

        boolean providerBackedP2p = shouldUseProviderBackedP2p(sender, recipient);
        String providerReference = null;

        // Names resolved up-front — needed in both the Rubies narration and the logs below
        String senderName    = getSafeName(sender);
        String recipientName = getSafeName(recipient);

        // Log-reference prefix and provider name are set inside each branch
        String logRefPrefix    = "P2P-DB-";
        String logCrRefPrefix  = "P2P-CR-";
        String logProviderName = null;

        if (providerBackedP2p) {
            // ── Rubies-to-Rubies internal book transfer ──────────────────────────
            // Both users have Rubies wallets under the Moniewise BaaS umbrella.
            // Rubies processes this as an internal transfer (no NIBSS hop) and
            // typically settles synchronously with response code "00".
            Wallet senderWallet    = walletService.getWalletByUserId(sender.getId());
            Wallet recipientWallet = walletService.getWalletByUserId(recipient.getId());

            // Reference with P2P-RB- prefix so the webhook handler can identify it
            // and skip the Withdrawal lookup gracefully.
            String p2pReference      = "P2P-RB-" + sender.getId() + "-" + System.currentTimeMillis();
            String debitAccountName  = walletService.resolveDisplayName(sender);
            String creditAccountName = walletService.resolveDisplayName(recipient);

            logger.info("[P2P-RUBIES] Initiating internal transfer ref={} from={} to={} amount={}",
                    p2pReference, senderWallet.getProviderWalletRef(),
                    recipientWallet.getProviderWalletRef(), amount);

            // Must resolve the Rubies gateway explicitly — resolveDefault() would pick
            // whichever PSP is currently active in system_config, which may not be Rubies.
            PaymentGateway gateway = paymentGatewayResolver.resolveByProviderName(RubiesGateway.PROVIDER_NAME);
            try {
                providerReference = gateway.initiateTransferWithContext(
                        senderWallet.getProviderWalletRef(),
                        debitAccountName,
                        RUBIES_BANK_CODE,
                        RUBIES_BANK_NAME,
                        recipientWallet.getProviderWalletRef(),
                        creditAccountName,
                        amount,
                        p2pReference,
                        "Wisemonie P2P: " + senderName + " to " + recipientName
                );
            } catch (RuntimeException ex) {
                // Sanitise Rubies float-related errors — don't expose internal float state to users.
                String rawCause = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
                if (rawCause.contains("insufficient float")
                        || rawCause.contains("not enough float")
                        || (rawCause.contains("insufficient balance") && rawCause.contains("rubies"))) {
                    throw new RuntimeException(
                            "Transfer temporarily unavailable. Please try again in a few minutes or contact support.");
                }
                throw ex;
            }

            // Rubies confirmed (or pending) — update sender's internal ledger.
            // DO NOT credit the recipient here: Rubies sends a CR webhook to the
            // recipient's account number, which processRubiesDepositWebhook() handles.
            // Calling fundWallet() here AND letting the CR webhook credit would double-credit.
            sourceEnvelope.setTotalRemainingAmount(sourceEnvelope.getTotalRemainingAmount().subtract(amount));
            sourceEnvelope.setRemainingAmount(sourceEnvelope.getRemainingAmount().subtract(amount));
            envelopeRepository.save(sourceEnvelope);

            logRefPrefix    = "P2P-RB-DB-";
            logCrRefPrefix  = "P2P-RB-CR-";
            logProviderName = RubiesGateway.PROVIDER_NAME;

            logger.info("[P2P-RUBIES] Transfer accepted: ref={} sessionId={}", p2pReference, providerReference);

        } else {
            // ── Internal DB-only path ────────────────────────────────────────────
            // Legacy (Providus/SecureWave) users or mixed-provider pairs.
            // No PSP call — just a ledger adjustment.
            sourceEnvelope.setTotalRemainingAmount(sourceEnvelope.getTotalRemainingAmount().subtract(amount));
            sourceEnvelope.setRemainingAmount(sourceEnvelope.getRemainingAmount().subtract(amount));
            envelopeRepository.save(sourceEnvelope);
            walletService.fundWallet(recipient.getId(), amount, null, true);
        }

        // Credit & Logs
//        String providerReference = processProvidusP2pIfEnabled(sender, recipient, amount);

//        String baseRef = UUID.randomUUID().toString();
        String baseRef = providerReference != null && !providerReference.isBlank()
                ? providerReference
                : recipient.getId() + "-" + System.currentTimeMillis();

        String description = "Transfer to " + recipientName;
        String type = (String) sourceEnvelope.getConditions().getOrDefault("type", "");
        if ("emergency".equalsIgnoreCase(type) && request.getWithdrawalReason() != null) {
            description = "EMERGENCY: " + request.getWithdrawalReason();
        } else if (request.getNote() != null) {
            description = request.getNote();
        }


        TransactionLog senderLog = TransactionLog.builder()
                .userId(sender.getId())
                .budgetId(sourceEnvelope.getBudget().getId())
                .sourceEnvelopeId(sourceEnvelope.getId())
                .counterpartyUserId(recipient.getId())
                .amount(amount.negate())
                .fee(BigDecimal.ZERO)
                .transactionType(TransactionType.ENVELOPE_TO_USER)
                .status(TransactionStatus.COMPLETED)
                .reference(logRefPrefix + baseRef)
                .providerName(logProviderName)
                .providerReference(providerReference)
                .description(description)
                .createdAt(now)
                .build();
        transactionLogRepository.save(senderLog);

        TransactionLog recipientLog = TransactionLog.builder()
                .userId(recipient.getId())
                .counterpartyUserId(sender.getId())
                .amount(amount)
                .fee(BigDecimal.ZERO)
                .transactionType(TransactionType.USER_TO_ENVELOPE)
                .status(TransactionStatus.COMPLETED)
                .reference(logCrRefPrefix + baseRef)
                .providerName(logProviderName)
                .providerReference(providerReference)
                .description("Received from " + senderName)
                .createdAt(now)
                .build();
        transactionLogRepository.save(recipientLog);

//        notificationService.sendNotification(sender.getId().toString(), "Sent Ã¢â€šÂ¦" + amount + " to " + recipientName, NotificationType.ENVELOPE_TRANSFER, sourceEnvelope.getBudget().getId(), sourceEnvelope.getId(), "VIEW_ENVELOPE", "/envelopes/" + sourceEnvelope.getId());
//        notificationService.sendNotification(recipient.getId().toString(), senderName + " sent you Ã¢â€šÂ¦" + amount, NotificationType.WALLET_DEPOSIT, null, null, "VIEW_WALLET", "/dashboard");

        // Ã¢Å“â€¦ ADD NEW EVENT: Sender Notification
        Map<String, Object> senderParams = Map.of(
                "amount", String.format("%,.2f", amount),
                "recipient", recipientName,
                "period", "today", // or logic to determine period
                "remaining", String.format("%,.2f", sourceEnvelope.getRemainingAmount())
        );

        eventPublisher.publishEvent(new GenericNotificationEvent(
                this,
                sender.getId().toString(),
                NotificationType.ENVELOPE_TRANSFER,
                senderParams,
                sourceEnvelope.getBudget().getId(),
                sourceEnvelope.getId(),
                "/envelopes/" + sourceEnvelope.getId()
        ));

        // Activation journey off-switch: comment out this one invocation to
        // stop the first direct-spend completion push/email.
        activationJourneyNudgeService.nudgeAfterFirstDirectSpend(
                sender.getId(),
                sourceEnvelope.getBudget().getId(),
                sourceEnvelope.getId());

        // Ã¢Å“â€¦ ADD NEW EVENT: Recipient Notification
        if (!providerBackedP2p) {
            // Rubies P2P recipient alerts are sent by the CR webhook after the balance is credited.
            Map<String, Object> recipientParams = Map.of(
                    "amount", String.format("%,.2f", amount),
                    "senderName", senderName
            );

            eventPublisher.publishEvent(new GenericNotificationEvent(
                    this,
                    recipient.getId().toString(),
                    NotificationType.WALLET_DEPOSIT, // Or P2P_RECEIVED if you have it
                    recipientParams,
                    null, // No budget context for recipient usually
                    null,
                    "/dashboard"
            ));
        }
        try { beneficiaryService.addBeneficiary(sender.getId(), recipient.getEmail(), recipientName); } catch (Exception e) {}
    }

    // =========================================================================
    // 3. GET REMAINING LIMIT - FIXED Ã¢Å“â€¦
    // =========================================================================
    public BigDecimal getRemainingLimit(Long envelopeId, String email) {
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible: " + envelopeId));
        return getRemainingLimit(envelope, email);
    }

    BigDecimal getRemainingLimit(Envelope envelope, String email) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        Budget budget = envelope.getBudget();

        if (budget == null || budget.getStatus() != BudgetStatus.ACTIVE || budgetStartsInFuture(budget, now)) {
            BigDecimal previousRemaining = safeAmount(envelope.getRemainingAmount());
            if (previousRemaining.compareTo(BigDecimal.ZERO) != 0) {
                envelope.setRemainingAmount(BigDecimal.ZERO);
                envelopeRepository.save(envelope);
                monnieCacheInvalidationService.evictUserIdentifierAfterCommit(email);
            }
            return BigDecimal.ZERO;
        }

        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null) {
            return BigDecimal.ZERO;
        }

        // 1. Handling Emergency Envelopes (No Limit)
        String type = conditions.getOrDefault("type", "standard").toString();
        if ("emergency".equalsIgnoreCase(type)) {
            return envelope.getRemainingAmount();
        }

        if (!conditions.containsKey("limit")) {
            throw new IllegalArgumentException("Limit missing");
        }

        BigDecimal limit = new BigDecimal(conditions.get("limit").toString());
        LocalDateTime periodStart;

        // 2. Determine the Time Window
        boolean createdToday = envelope.getCreatedAt().toLocalDate().isEqual(now.toLocalDate());

        switch (type) {
            case "daily":
                if (conditions.containsKey("disbursementTime")) {
                    String timeStr = (String) conditions.get("disbursementTime");
                    try {
                        LocalTime startTime = LocalTime.parse(timeStr);
                        if (!createdToday && now.toLocalTime().isBefore(startTime)) {
                            return BigDecimal.ZERO;
                        }
                    } catch (DateTimeParseException e) {
                        logger.error("Invalid time format", e);
                    }
                }
                periodStart = now.toLocalDate().atStartOfDay();
                break;

            case "weekly":
                periodStart = now.toLocalDate().minusDays(now.getDayOfWeek().getValue() - 1).atStartOfDay();
                break;
            case "monthly":
                // Calendar-anchored: resets on the 1st of each month.
                periodStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
                break;
            case "quarterly": {
                // Calendar-anchored: resets on Jan/Apr/Jul/Oct 1.
                int qStartMonth = ((now.getMonthValue() - 1) / 3) * 3 + 1;
                periodStart = LocalDate.of(now.getYear(), qStartMonth, 1).atStartOfDay();
                break;
            }
            case "biannual": {
                // Calendar-anchored: resets on Jan 1 and Jul 1.
                int hStartMonth = now.getMonthValue() <= 6 ? 1 : 7;
                periodStart = LocalDate.of(now.getYear(), hStartMonth, 1).atStartOfDay();
                break;
            }
            case "dynamic":
                @SuppressWarnings("unchecked")
                List<String> rawDays = (List<String>) conditions.getOrDefault("days", List.of());
                List<String> allowedDays = rawDays.stream()
                        .map(String::toUpperCase)
                        .toList();

                String currentDay = now.getDayOfWeek().name();

                if (!allowedDays.contains(currentDay)) {
                    return BigDecimal.ZERO;
                }

                String timeStr = (String) conditions.getOrDefault("disbursementTime", "08:00");
                LocalTime targetTime;
                try {
                    targetTime = LocalTime.parse(timeStr);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid disbursementTime format");
                }

                if (now.toLocalTime().isBefore(targetTime)) {
                    return BigDecimal.ZERO;
                }

//                String timeStr = (String) conditions.getOrDefault("disbursementTime", "08:00");
                periodStart = now.toLocalDate().atTime(LocalTime.parse(timeStr));
                break;
            case "safe_lock":
            case "strict_lock":
            case "emergency":
            case "savings_sweep":
                periodStart = now.minusYears(1);
//                periodEnd = now.plusYears(1);
                return envelope.getRemainingAmount();
            default:
                throw new IllegalArgumentException("Unsupported envelope type: " + type);
        }

        // 3. THE FIX: DEFINE WHAT COUNTS AS SPENDING
        // We strictly define: "Money leaving the envelope".
        // We do NOT include refunds or deposits here.
        // NOTE: ENVELOPE_EXTERNAL_TRANSFER_FEE is intentionally excluded — fees are
        // now deducted from the wallet, NOT from the envelope.  Including the FEE
        // companion log here would cause getRemainingLimit to overstate spending by
        // the markup fee amount and write an incorrectly low remainingAmount back to
        // the DB every time this method is called.
        List<TransactionType> spendingTypes = List.of(
                TransactionType.ENVELOPE_TO_ENVELOPE, // Moving money out
                TransactionType.ENVELOPE_TO_EXTERNAL, // Sending to Bank
                TransactionType.ENVELOPE_TO_USER      // P2P Transfer
        );

        // 4. Ã°Å¸Å¡â‚¬ EXECUTE NUCLEAR QUERY (Now returns a POSITIVE total of spending)
        BigDecimal spentAmount = transactionLogRepository.calculateTotalSpent(
                envelope.getId(),
                periodStart,
                spendingTypes,
                List.of(
                        TransactionStatus.COMPLETED,
                        TransactionStatus.PROCESSING,
                        TransactionStatus.PENDING
                )
        );

        // Also account for VAS (airtime/data) purchases — stored in payeelord_vas_transactions,
        // not TransactionLog, so the query above misses them entirely.
        BigDecimal vasSpent = safeAmount(vasTransactionRepository.sumSettledSellingAmount(
                envelope.getId(), VasTransactionStatus.SUCCESSFUL, periodStart));

        BigDecimal remainingLimit = limit.subtract(spentAmount).subtract(vasSpent);

        remainingLimit = remainingLimit.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remainingLimit;

        // Ã¢Å“â€¦ VAULT CAP FIX
        BigDecimal totalRemaining = envelope.getTotalRemainingAmount() != null
                ? envelope.getTotalRemainingAmount()
                : BigDecimal.ZERO;

        BigDecimal heldAmount = envelope.getHeldAmount() != null
                ? envelope.getHeldAmount()
                : BigDecimal.ZERO;

//        BigDecimal availableVaultBalance = totalRemaining.subtract(heldAmount);
        BigDecimal availableVaultBalance =
                safeAmount(envelope.getTotalRemainingAmount()).subtract(safeAmount(envelope.getHeldAmount()));

        if (remainingLimit.compareTo(availableVaultBalance) > 0) {
            remainingLimit = availableVaultBalance;
        }

//        BigDecimal vaultBalance = envelope.getTotalRemainingAmount();
//        if (remainingLimit.compareTo(vaultBalance) > 0) {
//            remainingLimit = vaultBalance;
//        }



        BigDecimal previousRemaining = safeAmount(envelope.getRemainingAmount());
        envelope.setRemainingAmount(remainingLimit);
        envelopeRepository.save(envelope);
        if (previousRemaining.compareTo(remainingLimit) != 0) {
            monnieCacheInvalidationService.evictUserIdentifierAfterCommit(email);
        }

        return envelope.getRemainingAmount();
    }

    // -------------------------------------------------------------------------
    // VAS spend (airtime/data via Payeelord) — envelope-funded
    // -------------------------------------------------------------------------
    // Mirrors the envelope side of the external-transfer flow exactly: HOLD on
    // spend, SETTLE on provider success, RELEASE on provider failure. Unlike
    // external transfers there is no separate wallet fee — the whole selling
    // amount comes out of the envelope. Called as discrete @Transactional steps
    // by PayeelordVasService (which makes the slow Payeelord HTTP call between
    // hold and settle, deliberately outside any transaction).

    /**
     * Validates ownership + period limit + vault balance + envelope rules, then
     * HOLDS {@code amount} against the envelope (remaining −= amount, held += amount).
     * Throws a user-friendly {@link IllegalStateException} if the spend isn't allowed.
     */
    @Transactional
    public Envelope holdEnvelopeForVas(Long envelopeId, String email, BigDecimal amount) {
        Envelope source = envelopeRepository.findByIdAndBudgetUserEmailForUpdate(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible: " + envelopeId));

        assertNoAutoTransferInProgress(source.getId());
        BigDecimal availableLimit = getRemainingLimit(source, email);

        validateTransferRules(source, source.getBudget(), fetchCurrentDateTimeFromDatabase());

        if (amount.compareTo(availableLimit) > 0) {
            throw new IllegalStateException(String.format(
                    "Insufficient envelope balance. Max spendable: ₦%,.2f.", availableLimit));
        }

        BigDecimal availableVault = safeAmount(source.getTotalRemainingAmount())
                .subtract(safeAmount(source.getHeldAmount()));
        if (amount.compareTo(availableVault) > 0) {
            throw new IllegalStateException(String.format(
                    "Insufficient funds. Max spendable: ₦%,.2f.", availableVault));
        }

        source.setRemainingAmount(safeAmount(source.getRemainingAmount()).subtract(amount));
        source.setHeldAmount(safeAmount(source.getHeldAmount()).add(amount));
        envelopeRepository.save(source);
        monnieCacheInvalidationService.evictUserIdentifierAfterCommit(email);
        return source;
    }

    /**
     * Finalises a successful VAS spend: releases the hold and reduces the vault
     * (held −= amount, totalRemaining −= amount), then propagates the spend to the
     * parent budget's remaining_amount. Mirrors ExternalTransferSettlementService.
     */
    @Transactional
    public void settleEnvelopeVas(Long envelopeId, BigDecimal amount) {
        Envelope source = envelopeRepository.findById(envelopeId)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found: " + envelopeId));

        source.setHeldAmount(safeAmount(source.getHeldAmount()).subtract(amount).max(BigDecimal.ZERO));
        source.setTotalRemainingAmount(safeAmount(source.getTotalRemainingAmount()).subtract(amount).max(BigDecimal.ZERO));
        envelopeRepository.save(source);

        Budget budget = source.getBudget();
        if (budget != null) {
            budget.setRemainingAmount(safeAmount(budget.getRemainingAmount()).subtract(amount).max(BigDecimal.ZERO));
            budgetRepository.save(budget);
            String ownerEmail = budget.getUser().getEmail();
            if (ownerEmail != null) {
                monnieCacheInvalidationService.evictUserIdentifierAfterCommit(ownerEmail);
            }
        }
    }

    /**
     * Reverses a held VAS spend after a provider failure: restores the period
     * limit and releases the hold (remaining += amount, held −= amount). The vault
     * was never reduced (settle didn't run), so there is nothing else to undo.
     */
    @Transactional
    public void releaseEnvelopeVasHold(Long envelopeId, BigDecimal amount) {
        Envelope source = envelopeRepository.findById(envelopeId)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found: " + envelopeId));

        source.setRemainingAmount(safeAmount(source.getRemainingAmount()).add(amount));
        source.setHeldAmount(safeAmount(source.getHeldAmount()).subtract(amount).max(BigDecimal.ZERO));
        envelopeRepository.save(source);
    }

    // -------------------------------------------------------------------------
    // HELPERS & OTHER METHODS
    // -------------------------------------------------------------------------

    private void validateTransferRules(Envelope source, Budget budget, LocalDateTime now) {
        if (budget == null || budget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalStateException("This budget is not active yet");
        }
        if (budgetStartsInFuture(budget, now)) {
            throw new IllegalStateException("This budget starts on " + budget.getStartDate());
        }

        Map<String, Object> conditions = source.getConditions();
        if (conditions == null || !conditions.containsKey("type")) return;

        String type = ((String) conditions.get("type")).toLowerCase();
        boolean createdToday = source.getCreatedAt().toLocalDate().isEqual(now.toLocalDate());

        switch (type) {
            case "dynamic":
                List<String> days = (List<String>) conditions.getOrDefault("days", List.of());
                List<String> upperDays = days.stream().map(String::toUpperCase).collect(Collectors.toList());
                String todayName = now.getDayOfWeek().name();

                if (!upperDays.contains(todayName)) {
                    throw new IllegalArgumentException("Dynamic transfers are only allowed on: " + days);
                }
                String timeStr = (String) conditions.getOrDefault("disbursementTime", "00:00");
                try {
                    LocalTime startTime = LocalTime.parse(timeStr);
                    if (now.toLocalTime().isBefore(startTime)) {
                        throw new IllegalArgumentException("Dynamic funds are locked until " + startTime);
                    }
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid time format");
                }
                break;

            case "daily":
                if (conditions.containsKey("disbursementTime")) {
                    String dailyTimeStr = (String) conditions.get("disbursementTime");
                    LocalTime startTime = LocalTime.parse(dailyTimeStr);
                    if (!createdToday && now.toLocalTime().isBefore(startTime)) {
                        throw new IllegalArgumentException("Daily funds are locked until " + startTime);
                    }
                }
                break;

            case "strict_lock":
            case "safe_lock":
                if (source.getMaturedAt() != null && now.isBefore(source.getMaturedAt())) {
                    throw new IllegalStateException("This envelope is locked until " + source.getMaturedAt().toLocalDate());
                }
                else if (conditions.containsKey("lockStartDate") && conditions.containsKey("lockDurationDays")) {
                    LocalDate lockStart = LocalDate.parse((String) conditions.get("lockStartDate"));
                    int duration = Integer.parseInt(conditions.get("lockDurationDays").toString());
                    LocalDate unlockDate = lockStart.plusDays(duration);
                    if (now.toLocalDate().isBefore(unlockDate)) {
                        throw new IllegalStateException("This envelope is locked until " + unlockDate);
                    }
                }
                break;
            case "emergency":
                break;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ExternalTransferResponse transferToExternal(
            Long sourceId,
            BudgetController.ExternalAccount externalAccount,
            Double amountDouble,
            String email,
            String withdrawalReason,
            String narration,
            String transactionPin
    ) {
        BigDecimal amount = BigDecimal.valueOf(amountDouble);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("Amount must be positive");

//        User user = userService.findByEmail(email);
//        Wallet linkedWallet = hydrateExternalAccountFromLinkedBank(externalAccount, user);

        User user = userService.findByEmail(email);

        // ── Detect PSP once so every branching point below can use it ────────
        Wallet userWallet = walletService.getWalletByUserId(user.getId());

        if (userWallet == null || userWallet.getProviderWalletRef() == null) {
            throw new IllegalStateException("Rubies wallet is not properly configured for user");
        }

        logger.info("USER WALLET DEBUG: id={}, provider={}, ref={}",
                userWallet.getId(),
                userWallet.getProviderName(),
                userWallet.getProviderWalletRef());

        boolean isRubies = RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(
                userWallet != null ? userWallet.getProviderName() : null);

        Wallet linkedWallet = null;
        Map<String, Object> secureWaveBankInfo = Map.of();

        if (!isRubies) {
            // Rubies uses OPay-style transfers: destination bank details come in the request,
            // not from a pre-linked settlement account. Skip settlement account loading.
            if (providusExpressGateway.isEnabled()) {
                linkedWallet = hydrateExternalAccountFromLinkedBank(externalAccount, user);
            } else {
                secureWaveBankInfo = walletService.getLinkedBankInfo(user.getId(), user.getEmail());

                if (secureWaveBankInfo == null || secureWaveBankInfo.isEmpty()) {
                    throw new IllegalStateException("No withdrawal bank account found. Please set your withdrawal bank first.");
                }
            }
        }

        // verify transaction pin
        if (!userService.verifyTransactionPin(user, transactionPin)) {
            throw new IllegalArgumentException("Invalid transaction PIN");
        }

        Envelope source = envelopeRepository.findByIdAndBudgetUserEmailForUpdate(sourceId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));

        // Ã°Å¸â€ºâ€˜ ADDED RE-FETCH HERE TO BE SAFE Ã°Å¸â€ºâ€˜
        assertNoAutoTransferInProgress(source.getId());
        getRemainingLimit(source, email);

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        validateTransferRules(source, source.getBudget(), now);

        String providerName = isRubies
                ? RubiesGateway.PROVIDER_NAME
                : (providusExpressGateway.isEnabled() ? ProvidusExpressGateway.PROVIDER_NAME : SecureWaveGateway.PROVIDER_NAME);

        // markupFee  = Moniewise revenue (flat fee for Rubies; flat fee for legacy).
        // bankCharge = NIBSS NIP fee charged by Rubies at BaaS level (₦10.75/₦26.88/₦53.75).
        //              This goes to Rubies / NIBSS — Moniewise does NOT collect it.
        // stampDuty  = ₦50 on transfers > ₦10K (charged by Rubies — NOT revenue).
        // totalDebit = amount + bankCharge + markupFee + stampDuty
        BigDecimal markupFee;
        BigDecimal bankCharge;
        BigDecimal stampDuty;
        BigDecimal totalDebit;
        if (isRubies) {
            if (userWallet.getProviderWalletRef() == null) {
                throw new IllegalStateException("Rubies wallet not configured");
            }
            // Rubies: markup-tier fee (waived for premium users) + NIP bank charge + stamp duty
            WithdrawalQuoteResponse rubiesQuote = walletService.quoteWithdrawal(amount, user.getId());
            markupFee  = rubiesQuote.getFee();
            bankCharge = rubiesQuote.getBankCharge();
            stampDuty  = rubiesQuote.getStampDuty();
            totalDebit = rubiesQuote.getTotalDebit();
        } else {
            TransferFeeQuote feeQuote = transferFeeService.quoteFee(
                    providerName,
                    TransferFeeTransferType.ENVELOPE_TO_EXTERNAL,
                    amount
            );
            markupFee  = feeQuote.getFee();
            bankCharge = BigDecimal.ZERO;   // legacy providers handle their fees differently
            stampDuty  = BigDecimal.ZERO;
            totalDebit = feeQuote.getTotalDebit();
        }
        // totalFee is what goes into error messages; markupFee is what goes on txn.setFee()
        final BigDecimal fee      = markupFee;  // alias kept for downstream uses (txn log, FEE log)
        final BigDecimal totalFee = bankCharge.add(markupFee).add(stampDuty);

        // Period-limit check — only the send amount counts against the envelope limit.
        // Fees come from the main wallet, not the envelope.
        BigDecimal periodRemaining = source.getRemainingAmount();
        if (amount.compareTo(periodRemaining) > 0) {
            throw new IllegalStateException(String.format(
                    "Insufficient envelope balance. Max sendable: ₦%,.2f.",
                    periodRemaining));
        }

        // Vault balance check — physical funds in the envelope minus what's already held.
        BigDecimal availableVaultBalance =
                safeAmount(source.getTotalRemainingAmount())
                        .subtract(safeAmount(source.getHeldAmount()));

        if (amount.compareTo(availableVaultBalance) > 0) {
            throw new IllegalStateException(String.format(
                    "Insufficient funds. Max sendable: ₦%,.2f.",
                    availableVaultBalance));
        }

        // Wallet fee check — deduct fees (NIP + markup) from the main wallet now.
        // If the wallet can't cover them, this throws with a user-friendly message
        // before any envelope state is touched.
        walletService.deductTransferFee(user.getId(), totalFee, bankCharge, markupFee);
//        String resolvedName = resolveExternalRecipientName(externalAccount, linkedWallet);
//        if (resolvedName == null) {
//            throw new IllegalArgumentException("Invalid Account Number");
//        }

        String resolvedName;
        String bankName;
        String bankCode;
        String accountNumber;

        if (isRubies) {
            // OPay-style: bankCode and accountNumber are supplied in every transfer request.
            if (isBlank(externalAccount.getBankCode())) {
                throw new IllegalArgumentException("Bank code is required for Rubies transfers");
            }
            if (isBlank(externalAccount.getAccountNumber())) {
                throw new IllegalArgumentException("Account number is required for Rubies transfers");
            }
            accountNumber = externalAccount.getAccountNumber().trim();
            bankCode      = externalAccount.getBankCode().trim();
            bankName      = isBlank(externalAccount.getBankName())
                            ? bankCode
                            : externalAccount.getBankName().trim();
            // Name-enquiry against the destination bank via the Rubies gateway
            resolvedName = walletService.resolveBankAccount(user.getId(), bankCode, accountNumber);
            if (isBlank(resolvedName)) {
                throw new IllegalArgumentException("Could not resolve account name — please verify the bank code and account number.");
            }

        } else if (providusExpressGateway.isEnabled()) {
            resolvedName = resolveExternalRecipientName(externalAccount, linkedWallet);
            bankName = externalAccount.getBankName();
            bankCode = externalAccount.getBankCode() != null ? externalAccount.getBankCode().trim() : null;
            accountNumber = externalAccount.getAccountNumber();

            if (resolvedName == null) {
                throw new IllegalArgumentException("Invalid Account Number");
            }

        } else {
            resolvedName = String.valueOf(
                    secureWaveBankInfo.getOrDefault("account_name", "Saved withdrawal account")
            );

            bankName = String.valueOf(
                    secureWaveBankInfo.getOrDefault("bank_name", "Saved bank")
            );

            accountNumber = String.valueOf(
                    secureWaveBankInfo.getOrDefault("account_number", "")
            );

            bankCode = null; // Not available for legacy SecureWave/Providus path

            if (resolvedName.isBlank() || accountNumber.isBlank()) {
                throw new IllegalStateException("Withdrawal bank info is incomplete. Please update your withdrawal bank.");
            }
        }

        String myReference = "EXT-" + java.util.UUID.randomUUID().toString();
        String description;
        String type = (String) source.getConditions().getOrDefault("type", "");

        if ("emergency".equalsIgnoreCase(type)) {
            if (withdrawalReason == null || withdrawalReason.trim().isEmpty()) {
                throw new IllegalArgumentException("Emergency withdrawals require a valid reason.");
            } else {
                description = String.format("EMERGENCY WITHDRAWAL: %s", withdrawalReason != null ? withdrawalReason : "Unspecified");
            }
        } else {
//            description = "Transfer to " + resolvedName + " (" + externalAccount.getBankName() + ")";
            description = "Transfer to " + resolvedName + " (" + bankName + ")";

        }

//      source.setTotalRemainingAmount(source.getTotalRemainingAmount().subtract(amount));
//      source.setRemainingAmount(source.getRemainingAmount().subtract(amount));
//      envelopeRepository.save(source);

        // Only hold the send amount against the envelope — fees came from the wallet.
        source.setRemainingAmount(source.getRemainingAmount().subtract(amount));
        source.setHeldAmount(safeAmount(source.getHeldAmount()).add(amount));
        envelopeRepository.save(source);

        TransactionLog feeTxn = null;

        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            feeTxn = TransactionLog.builder()
                    .userId(user.getId())
                    .budgetId(source.getBudget().getId())
                    .sourceEnvelopeId(sourceId)
                    .externalAccountId(null)
                    .externalBankName(bankName)
                    .externalBankCode(bankCode)
                    .externalAccountNumber(accountNumber)
                    .externalAccountName(resolvedName)
                    .amount(fee.negate())
                    .fee(BigDecimal.ZERO)
                    .transactionType(TransactionType.ENVELOPE_EXTERNAL_TRANSFER_FEE)
                    .status(TransactionStatus.PENDING)
                    .reference(myReference + "-FEE")
                    .providerName(providerName)
                    .description("External transfer fee for " + myReference)
                    .createdAt(LocalDateTime.now())
                    .build();

            transactionLogRepository.save(feeTxn);
        }

        TransactionLog txn = TransactionLog.builder()
                .userId(user.getId())
                .budgetId(source.getBudget().getId())
                .sourceEnvelopeId(sourceId)
                .externalAccountId(null)
                .externalBankName(bankName)
                .externalBankCode(bankCode)
                .externalAccountNumber(accountNumber)
                .externalAccountName(resolvedName)
                .amount(amount.negate())
                .fee(fee)
                .stampDuty(stampDuty)
                .transactionType(TransactionType.ENVELOPE_TO_EXTERNAL)
                .status(TransactionStatus.PENDING)
                .reference(myReference)
                .providerName(providerName)
                .description(description)
                .createdAt(LocalDateTime.now())
                .build();
        transactionLogRepository.save(txn);

        // Create the wallet-side fee debit log so users can see why their wallet
        // balance dropped.  This is placed before the provider call so that if the
        // provider fails the @Transactional rollback removes the log automatically.
        // Reference: WFT-{myReference}  |  Status: PROCESSING → COMPLETED / FAILED
        if (totalFee.compareTo(BigDecimal.ZERO) > 0) {
            walletService.logTransferFeeDebit(
                    user.getId(), totalFee, bankCharge, markupFee,
                    myReference, amount, resolvedName, bankName);
        }

        String providerRef;
        try {
            String providerNarration = narration != null && !narration.trim().isEmpty()
                    ? narration.trim()
                    : "Transfer from " + source.getName();

            if (isRubies) {
                // OPay-style: supply destination bank details inline with every Rubies transfer.
                // The envelope reference (EXT-UUID) is echoed back as paymentReference in the
                // DR webhook, which settleExternalTransferIfExists() picks up to settle the envelope.
                PaymentGateway rubiesGateway =
                        paymentGatewayResolver.resolveByProviderName(RubiesGateway.PROVIDER_NAME);
                providerRef = rubiesGateway.initiateTransferWithContext(
                        userWallet.getProviderWalletRef(),
                        walletService.resolveDisplayName(user),
                        externalAccount.getBankCode(),
                        bankName,
                        accountNumber,
                        resolvedName,
                        amount,
                        myReference,
                        providerNarration
                );
                // NOTE: the markup fee is transferred to the Moniewise Rubies revenue wallet
                // only after the SUCCESS webhook confirms delivery — handled in
                // ExternalTransferSettlementService.settleExternalTransfer().
            } else if (providusExpressGateway.isEnabled()) {
                providerRef = initiateProvidusExternalTransfer(
                        user,
                        externalAccount,
                        resolvedName,
                        amount,
                        myReference,
                        providerNarration
                );
            } else {
                providerRef = paymentProvider.initiateWithdrawal(
                        user.getEmail(),
                        amount,
                        providerNarration
                );
            }
            if (shouldAutoSettleExternalTransfer(providerName)) {
                finalizeExternalTransferAcceptedWithoutWebhook(
                        source.getId(),
                        txn.getReference(),
                        providerRef,
                        amount   // fees already came from wallet; only envelope amount settles here
                );
                // SecureWave auto-settles immediately — mark the wallet fee log COMPLETED now.
                // (For Rubies the settlement service handles this when TSQ confirms.)
                transactionLogRepository.findByReference("WFT-" + myReference).ifPresent(wftLog -> {
                    wftLog.setStatus(TransactionStatus.COMPLETED);
                    transactionLogRepository.save(wftLog);
                });
            } else {
                txn.setProviderReference(providerRef);
                txn.setStatus(TransactionStatus.PROCESSING);
                transactionLogRepository.save(txn);

                // Rubies does NOT send DR webhooks for outbound NIP transfers.
                // Fire an async TSQ poll immediately (at 5s / 15s / 35s) so the transfer
                // settles within seconds rather than waiting for the 3-min scheduler cycle.
                if (isRubies) {
                    transferRecoveryScheduler.scheduleImmediateRecovery(
                            txn.getReference(), providerRef);
                }
            }

            if (feeTxn != null) {
                feeTxn.setStatus(TransactionStatus.PROCESSING);
                // NOTE: do NOT set providerReference on the FEE companion log.
                // Both the main EXT- log and this FEE log would end up with the same
                // provider_reference (Rubies session ID), causing
                // IncorrectResultSizeDataAccessException when settleExternalTransferIfExists
                // calls findByProviderReference(sessionId) — it finds 2 rows, not 1,
                // and Spring Data throws. That exception causes the webhook to return 500,
                // Rubies retries, same error, eventually stops — leaving EXT- records
                // stuck in PROCESSING forever. The FEE record's status is updated by
                // reference ("EXT-UUID-FEE") inside settleExternalTransfer anyway.
                transactionLogRepository.save(feeTxn);
            }

            // Ã¢Å“â€¦ ADD NEW EVENT
            Map<String, Object> params = Map.of(
                    "amount", String.format("%,.2f", amount),
                    "recipient", resolvedName
            );

            eventPublisher.publishEvent(new GenericNotificationEvent(
                    this,
                    user.getId().toString(),
                    NotificationType.EXTERNAL_TRANSFER,
                    params,
                    source.getBudget().getId(),
                    sourceId,
                    "/envelopes/" + sourceId
            ));
        } catch (Exception e) {
            logger.error("External transfer failed: {}", e.getMessage());
//            source.setTotalRemainingAmount(source.getTotalRemainingAmount().add(amount));
//            source.setRemainingAmount(source.getRemainingAmount().add(amount));
//            envelopeRepository.save(source);

            // Restore envelope: only `amount` was held (fees came from wallet).
            // The @Transactional rollback will also undo the wallet fee deduction automatically.
            source.setHeldAmount(safeAmount(source.getHeldAmount()).subtract(amount));
            source.setRemainingAmount(source.getRemainingAmount().add(amount));

            envelopeRepository.save(source);

            txn.setStatus(TransactionStatus.FAILED);
            txn.setDescription(description + " | Failed: " + e.getMessage());
            transactionLogRepository.save(txn);

            if (feeTxn != null) {
                feeTxn.setStatus(TransactionStatus.FAILED);
                feeTxn.setDescription("External transfer fee failed/reversed for " + myReference);
                transactionLogRepository.save(feeTxn);
            }
            // Sanitise provider-level float errors — don't expose internal float state to users.
            String rawCause = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (rawCause.contains("insufficient float")
                    || rawCause.contains("not enough float")
                    || (rawCause.contains("insufficient balance") && rawCause.contains("rubies"))) {
                throw new RuntimeException(
                        "Transfer temporarily unavailable. Please try again in a few minutes or contact support.");
            }
            throw new RuntimeException("Transfer failed: " + e.getMessage());
        }

        return new ExternalTransferResponse(
                myReference,
                providerRef,
                amount,
                resolvedName,
//                externalAccount.getBankName(),
                bankName,
                TransactionStatus.PROCESSING
        );
    }

    private Wallet hydrateExternalAccountFromLinkedBank(BudgetController.ExternalAccount externalAccount, User user) {
        if (externalAccount == null) {
            throw new IllegalArgumentException("External account details are required");
        }

        Wallet wallet = walletService.getWalletByUserId(user.getId());
        boolean hasRequestAccountNumber = !isBlank(externalAccount.getAccountNumber());
        boolean matchesLinkedAccount = hasRequestAccountNumber
                && !isBlank(wallet.getSettlementAccountNumber())
                && externalAccount.getAccountNumber().trim().equals(wallet.getSettlementAccountNumber().trim());

        if (!hasRequestAccountNumber && !isBlank(wallet.getSettlementAccountNumber())) {
            externalAccount.setAccountNumber(wallet.getSettlementAccountNumber());
            matchesLinkedAccount = true;
        }

        if (matchesLinkedAccount) {
            if (isBlank(externalAccount.getBankCode())) {
                externalAccount.setBankCode(wallet.getSettlementBankCode());
            }
            if (isBlank(externalAccount.getBankName())) {
                externalAccount.setBankName(wallet.getSettlementBankName());
            }
            if (isBlank(externalAccount.getRecipientName())) {
                externalAccount.setRecipientName(wallet.getSettlementAccountName());
            }
        }

        if (isBlank(externalAccount.getAccountNumber())) {
            throw new IllegalArgumentException("Account number is required");
        }
        if (isBlank(externalAccount.getBankCode())) {
            throw new IllegalArgumentException("Bank code is required. Please refresh your linked withdrawal account.");
        }

        return wallet;
    }

    private String resolveExternalRecipientName(BudgetController.ExternalAccount externalAccount, Wallet linkedWallet) {
        boolean matchesLinkedAccount = linkedWallet != null
                && !isBlank(linkedWallet.getSettlementAccountNumber())
                && !isBlank(externalAccount.getAccountNumber())
                && externalAccount.getAccountNumber().trim().equals(linkedWallet.getSettlementAccountNumber().trim());

        if (matchesLinkedAccount && !isBlank(linkedWallet.getSettlementAccountName())) {
            return linkedWallet.getSettlementAccountName().trim();
        }

        return providusExpressGateway.isEnabled()
                ? providusExpressGateway.resolveAccount(externalAccount.getBankCode(), externalAccount.getAccountNumber())
                : paymentProvider.resolveAccount(externalAccount.getBankCode(), externalAccount.getAccountNumber());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Transactional
    public EnvelopeResponse createEnvelope(EnvelopeRequest request, String email, boolean isSilent) {
        Budget budget = budgetRepository.findById(request.getBudgetId())
                .orElseThrow(() -> new EntityNotFoundException("Budget not found with ID: " + request.getBudgetId()));

        if (!budget.getUser().getEmail().equals(email)) {
            throw new SecurityException("Unauthorized access to budget");
        }
        if (budget.getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalArgumentException("Can only add envelopes to active budgets");
        }

        Envelope envelope = createEnvelopeEntity(request, budget, email, isSilent);
        return toResponse(envelope);
    }

    Envelope createEnvelopeEntity(EnvelopeRequest request, Budget budget, String email, boolean isSilent) {
        return createEnvelopeEntity(request, budget, email, isSilent, false);
    }

    Envelope createEnvelopeEntity(EnvelopeRequest request, Budget budget, String email,
                                  boolean isSilent, boolean deferScheduling) {
        BigDecimal amount;
        if (request.getExactAmount() != null && request.getExactAmount().compareTo(BigDecimal.ZERO) > 0) {
            amount = request.getExactAmount();
        } else {
            amount = budget.getTotalAmount()
                    .multiply(request.getPercentage())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        long budgetDurationDays = (budget.getStartDate() != null && budget.getEndDate() != null)
                ? java.time.temporal.ChronoUnit.DAYS.between(budget.getStartDate(), budget.getEndDate()) + 1
                : (budget.getDurationDays() != null ? budget.getDurationDays() : 30L);
        if (budgetDurationDays <= 0) budgetDurationDays = 1;

        validateEnvelopeConditions(request, amount, (int) budgetDurationDays);

        Map<String, Object> conditions = request.getConditions();
        String type = "standard";
        if (conditions != null && conditions.containsKey("type")) {
            type = (String) conditions.get("type");
            switch (type) {
                case "daily":
                    conditions.putIfAbsent("gracePeriodMinutes", 30);
                    break;
                case "weekly":
                    conditions.putIfAbsent("gracePeriodMinutes", 30);
                    break;
                case "dynamic":
                    conditions.putIfAbsent("gracePeriodMinutes", 30);
                    break;
                case "safe_lock":
                case "strict_lock":
                    conditions.putIfAbsent("gracePeriodMinutes", 1440);
                    break;
                case "emergency":
                    conditions.put("limit", amount);
                    conditions.putIfAbsent("gracePeriodMinutes", 0);
                    break;
            }
        }

        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        if (budgetStartsInFuture(budget, now)
                && ("safe_lock".equalsIgnoreCase(type) || "strict_lock".equalsIgnoreCase(type))
                && budget.getStartDate() != null) {
            conditions.put("lockStartDate", budget.getStartDate().toString());
        }

        Envelope envelope = new Envelope(budget, request.getName(), amount, conditions);
        envelope.setCreatedAt(now);
        envelope.setInitialAmount(amount);
        envelope.setTotalRemainingAmount(amount);
        envelope.setHeldAmount(BigDecimal.ZERO);
        envelopeRepository.save(envelope);

        recalculateTargetEnvelopeLimit(envelope, budget);

        LocalDateTime firstDisbursement = budgetLifeCycleManager.calculateNextDisbursementTime(envelope);
        envelope.setNextDisbursementAt(firstDisbursement);
        envelope.setHasMatured(false);

        String typez = (String) conditions.getOrDefault("type", "");
        if (budgetStartsInFuture(budget, now)) {
            envelope.setRemainingAmount(BigDecimal.ZERO);
        } else if ("emergency".equalsIgnoreCase(typez)) {
            envelope.setRemainingAmount(amount);
        } else if ("daily".equalsIgnoreCase(typez) || "weekly".equalsIgnoreCase(typez) || "dynamic".equalsIgnoreCase(typez)) {
            boolean firstDisbursementIsToday = firstDisbursement != null
                    && firstDisbursement.toLocalDate().isEqual(now.toLocalDate());
            if (firstDisbursementIsToday) {
                envelope.setRemainingAmount(BigDecimal.ZERO);
            } else {
                BigDecimal startingPocket = getPeriodLimit(envelope.getConditions());
                startingPocket = startingPocket.min(envelope.getTotalRemainingAmount());
                envelope.setRemainingAmount(startingPocket);
                envelope.setLastDisbursedAt(now);
            }
        } else {
            BigDecimal startingPocket = getPeriodLimit(envelope.getConditions());
            startingPocket = startingPocket.min(envelope.getTotalRemainingAmount());
            envelope.setRemainingAmount(startingPocket);
        }

        if (deferScheduling) {
            envelopeRepository.save(envelope);
        } else {
            budgetLifeCycleManager.scheduleDynamicTasks(envelope);
        }

        if (!isSilent) {
            Map<String, Object> params = Map.of(
                    "amount", String.format("%,.2f", amount),
                    "envelopeName", envelope.getName()
            );
            eventPublisher.publishEvent(new GenericNotificationEvent(
                    this, budget.getUser().getId().toString(), NotificationType.ENVELOPE_CREATED,
                    params, budget.getId(), envelope.getId(), "/envelopes/" + envelope.getId()
            ));
        }
        return envelope;
    }

    @Transactional
    public EnvelopeResponse getEnvelopeById(Long envelopeId, String email) {
        // This forces the backend to recalculate the current spendable pocket
        // before the frontend receives the response.
        getRemainingLimit(envelopeId, email);

        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));
        return toResponse(envelope);
    }

    @Transactional
    public EnvelopeResponse updateEnvelopeConditions(Long envelopeId, EnvelopeRequest request, String email) {
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));
        Budget b = envelope.getBudget();
        // +1: see createEnvelope() above - matches the frontend's inclusive
        // day count instead of ChronoUnit.DAYS.between()'s exclusive one.
        long updDuration = (b.getStartDate() != null && b.getEndDate() != null)
                ? java.time.temporal.ChronoUnit.DAYS.between(b.getStartDate(), b.getEndDate()) + 1
                : (b.getDurationDays() != null ? b.getDurationDays() : 30L);
        if (updDuration <= 0) updDuration = 1;
        validateEnvelopeConditions(request, envelope.getAmount(), (int) updDuration);
        envelope.setConditions(request.getConditions());
        envelope.setRemainingAmount(getPeriodLimit(request.getConditions()));
        envelopeRepository.save(envelope);

        // Ã¢Å“â€¦ ADD NEW EVENT
        Map<String, Object> params = Map.of(
                "envelopeName", envelope.getName(),
                "budgetName", envelope.getBudget().getName()
        );

        eventPublisher.publishEvent(new GenericNotificationEvent(
                this,
                envelope.getBudget().getUser().getId().toString(),
                NotificationType.ENVELOPE_UPDATED,
                params,
                envelope.getBudget().getId(),
                envelope.getId(),
                "/envelopes/" + envelope.getId()
        ));
        return toResponse(envelope);
    }

    @Transactional
    public void claimDisbursement(Long pendingDisbursementId, String email) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        PendingDisbursement pd = pendingDisbursementRepository.findById(pendingDisbursementId)
                .orElseThrow(() -> new IllegalArgumentException("Not found"));

        if (!pd.getStatus().equals(Status.PENDING) || now.isAfter(pd.getExpiresAt())) {
            throw new IllegalStateException("Disbursement expired");
        }

        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(pd.getEnvelopeId(), email)
                .orElseThrow(() -> new SecurityException("Envelope not found or not accessible"));

        envelope.setRemainingAmount(envelope.getRemainingAmount().add(pd.getAmount()));
        envelopeRepository.save(envelope);

        pd.setStatus(Status.CLAIMED);
        pd.setProcessedAt(now);
        pendingDisbursementRepository.save(pd);

        // Ã¢Å“â€¦ ADD NEW EVENT
        Map<String, Object> params = Map.of(
                "amount", String.format("%,.2f", pd.getAmount()),
                "envelopeName", envelope.getName()
        );

        eventPublisher.publishEvent(new GenericNotificationEvent(
                this,
                envelope.getBudget().getUser().getId().toString(),
                NotificationType.DISBURSEMENT_SUCCESS,
                params,
                envelope.getBudget().getId(),
                envelope.getId(),
                "/envelopes/" + envelope.getId()
        ));
    }

    public void deleteEnvelope(Long envelopeId, String email) {
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found or not accessible"));
        if (envelope.getBudget().getStatus() == BudgetStatus.ACTIVE
                || envelope.getBudget().getStatus() == BudgetStatus.SCHEDULED) {
            throw new IllegalStateException("Cannot delete envelope from a funded budget");
        }
        envelopeRepository.delete(envelope);

//        notificationService.sendNotification(
//                envelope.getBudget().getUser().getId().toString(),
//                String.format("Deleted envelope '%s' from budget '%s'.",
//                        envelope.getName(), envelope.getBudget().getName()),
//                NotificationType.ENVELOPE_DELETED
//        );
        // Ã¢Å“â€¦ ADD NEW EVENT
        Map<String, Object> params = Map.of(
                "envelopeName", envelope.getName(),
                "budgetName", envelope.getBudget().getName()
        );

        eventPublisher.publishEvent(new GenericNotificationEvent(
                this,
                envelope.getBudget().getUser().getId().toString(),
                NotificationType.ENVELOPE_DELETED,
                params,
                envelope.getBudget().getId(),
                null, // Envelope is deleted, no ID to link
                "/budgets/" + envelope.getBudget().getId()
        ));
    }

//    private EnvelopeResponse toResponse(Envelope envelope) {
//        // Optimization: Use the entity's stored value for speed.
//        // We only recalculate during specific events (transfers, scheduler).
//        BigDecimal visibleBalance = getSpendableBalance(envelope);
//
//        return new EnvelopeResponse(
//                envelope.getId(),
//                envelope.getBudget().getId(),
//                envelope.getName(),
//                envelope.getAmount(),
//                visibleBalance,
//                envelope.getAmount(),
//                envelope.getTotalRemainingAmount(),
//                visibleBalance, // Use visible balance for "current pocket"
//                getPeriodLimit(envelope.getConditions()), // Helper calculation (fast, no DB)
//
//                // Ã°Å¸â€ºâ€˜ Optimization: Avoid calling external service inside loop if possible.
//                // If you MUST calculate usage, do it using entity data:
//                // Usage = Limit - Pocket (if positive)
//                getPeriodLimit(envelope.getConditions()).subtract(envelope.getRemainingAmount()).max(BigDecimal.ZERO),
//
//                envelope.getConditions(),
//                envelope.getCreatedAt(),
//                envelope.getLastDisbursedAt(),
//                envelope.getNextDisbursementAt()
//        );
//    }

    private EnvelopeResponse toResponse(Envelope envelope) {
        BigDecimal periodLimit = getPeriodLimit(envelope.getConditions());
        BigDecimal periodRemaining = getSpendableBalance(envelope);

        BigDecimal usedThisPeriod = periodLimit
                .subtract(periodRemaining)
                .max(BigDecimal.ZERO);

        return new EnvelopeResponse(
                envelope.getId(),
                envelope.getBudget().getId(),
                envelope.getName(),

                envelope.getAmount(),
                periodRemaining,

                envelope.getInitialAmount(),
                envelope.getTotalRemainingAmount(),
                periodRemaining,
                periodLimit,
                usedThisPeriod,

                envelope.getHeldAmount() != null ? envelope.getHeldAmount() : BigDecimal.ZERO,

                Boolean.TRUE.equals(envelope.getIsAutomated()),

                envelope.getConditions(),
                envelope.getCreatedAt(),
                envelope.getLastDisbursedAt(),
                envelope.getNextDisbursementAt()
        );
    }

    /**
     * Envelopes for the dashboard "quick spend" row, sorted spendable-first.
     *
     * <p>Spendable-now envelopes ({@code periodRemaining > 0} — the disbursement window is open and
     * the pocket still has money, see {@link #getSpendableBalance}) come first, ordered by balance
     * desc. The remaining (locked) envelopes follow, ordered by soonest {@code nextDisbursementAt}
     * so the client can show an "unlocks in…" hint. Capped at {@code limit}. Reuses
     * {@link #toResponse} so the shape matches the regular envelope endpoints.
     */
    @Transactional(readOnly = true)
    public List<EnvelopeResponse> getDashboardEnvelopes(Long budgetId, int limit) {
        return envelopeRepository.findByBudgetId(budgetId).stream()
                .map(this::toResponse)
                .sorted((a, b) -> {
                    boolean aSpendable = isSpendableNow(a);
                    boolean bSpendable = isSpendableNow(b);
                    if (aSpendable != bSpendable) return aSpendable ? -1 : 1;
                    if (aSpendable) {
                        // both spendable now → highest balance first
                        return nz(b.getPeriodRemaining()).compareTo(nz(a.getPeriodRemaining()));
                    }
                    // both locked → soonest to unlock first (nulls last)
                    LocalDateTime an = a.getNextDisbursementAt();
                    LocalDateTime bn = b.getNextDisbursementAt();
                    if (an == null && bn == null) return 0;
                    if (an == null) return 1;
                    if (bn == null) return -1;
                    return an.compareTo(bn);
                })
                .limit(Math.max(0, limit))
                .collect(Collectors.toList());
    }

    private boolean isSpendableNow(EnvelopeResponse e) {
        return e.getPeriodRemaining() != null
                && e.getPeriodRemaining().compareTo(BigDecimal.ZERO) > 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private boolean budgetStartsInFuture(Budget budget, LocalDateTime now) {
        return budget != null
                && budget.getStartDate() != null
                && now.toLocalDate().isBefore(budget.getStartDate());
    }

    @Transactional
    public void resetEnvelopeLimits(Envelope envelope) {
        Map<String, Object> conditions = envelope.getConditions();
        if (conditions != null && conditions.containsKey("type")) {
            String type = conditions.get("type").toString();
            if (List.of("daily", "weekly", "dynamic").contains(type)) {
                envelope.setRemainingAmount(getPeriodLimit(conditions));
                envelopeRepository.save(envelope);
            }
        }
    }

    private BigDecimal getPeriodLimit(Map<String, Object> conditions) {
        if (conditions != null && conditions.containsKey("limit") && conditions.get("limit") instanceof Number) {
            return new BigDecimal(conditions.get("limit").toString());
        }
        return BigDecimal.ZERO;
    }

    private void recalculateTargetEnvelopeLimit(Envelope envelope, Budget budget) {
        String type = (String) envelope.getConditions().get("type");
        if (type == null) return;
        if (!Set.of("daily", "weekly", "dynamic").contains(type)) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate effectiveStart = budget.getStartDate() != null && budget.getStartDate().isAfter(today)
                ? budget.getStartDate()
                : today;
        LocalDate budgetEnd = budget.getEndDate();
        if (budgetEnd == null || budgetEnd.isBefore(effectiveStart)) {
            return;
        }

        BigDecimal totalRemaining = envelope.getTotalRemainingAmount();
        long remainingUnits = 0;
        BigDecimal newLimit;

        switch (type) {
            case "daily" -> {
                remainingUnits = ChronoUnit.DAYS.between(effectiveStart, budgetEnd) + 1; // includes start day

                if (remainingUnits <= 0) remainingUnits = 1;
                newLimit = totalRemaining.divide(BigDecimal.valueOf(remainingUnits), 2, RoundingMode.HALF_UP);
            }
            case "weekly" -> {
                remainingUnits = ChronoUnit.WEEKS.between(effectiveStart, budgetEnd) + 1;
                if (remainingUnits <= 0) remainingUnits = 1;
                newLimit = totalRemaining.divide(BigDecimal.valueOf(remainingUnits), 2, RoundingMode.HALF_UP);
            }
            case "dynamic" -> {
                // Read the release days from "days" (the key the frontend and
                // every other backend reader use). The old code read a
                // non-existent "selectedDays" string, so it always fell through
                // to the daily count below — dividing a Sunday-only ₦4,000
                // envelope by ~30 days (₦133) instead of by the ~5 Sundays.
                List<String> selectedDays = extractReleaseDays(envelope.getConditions());
                if (selectedDays.isEmpty()) {
                    remainingUnits = ChronoUnit.DAYS.between(effectiveStart, budgetEnd) + 1;
                } else {
                    long daysUntilEnd = ChronoUnit.DAYS.between(effectiveStart, budgetEnd);
                    long remainingOccurrences = 0;
                    for (int i = 0; i <= daysUntilEnd; i++) {
                        LocalDate checkDate = effectiveStart.plusDays(i);
                        if (selectedDays.contains(checkDate.getDayOfWeek().name())) {
                            remainingOccurrences++;
                        }
                    }
                    remainingUnits = remainingOccurrences > 0 ? remainingOccurrences : 1;
                }
                newLimit = totalRemaining.divide(BigDecimal.valueOf(remainingUnits), 2, RoundingMode.HALF_UP);
            }
            default -> {
                return;
            }
        }

        envelope.getConditions().put("limit", newLimit);
        envelope.getConditions().put("limit_value", newLimit);
        envelope.getConditions().put("remaining_units", remainingUnits);
        envelopeRepository.save(envelope);
    }

    /**
     * Reads an envelope's selected release days from conditions. The frontend
     * (and every other backend reader) stores them under "days" as a List of
     * upper-case day names ("SUNDAY", ...); a legacy "selectedDays"
     * comma-separated string is accepted as a fallback. Returns upper-cased
     * names ready to compare against {@link java.time.DayOfWeek#name()}.
     */
    private List<String> extractReleaseDays(Map<String, Object> conditions) {
        Object daysObj = conditions.get("days");
        if (daysObj == null) {
            daysObj = conditions.get("selectedDays"); // legacy key/format
        }
        if (daysObj == null) {
            return List.of();
        }
        List<String> raw = (daysObj instanceof List<?>)
                ? ((List<?>) daysObj).stream().map(String::valueOf).toList()
                : Arrays.asList(daysObj.toString().split(","));
        return raw.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .toList();
    }

    public void triggerRecalculation(Envelope envelope) {
        recalculateTargetEnvelopeLimit(envelope, envelope.getBudget());
    }

    private String getSafeName(User user) {
        if (user.getProfileData() != null) {
            Object nameObj = user.getProfileData().getOrDefault("fullName", user.getProfileData().get("name"));
            if (nameObj != null && !nameObj.toString().trim().isEmpty()) {
                return nameObj.toString();
            }
        }
        if (user.getEmail() != null) {
            String handle = user.getEmail().split("@")[0];
            return handle.substring(0, 1).toUpperCase() + handle.substring(1);
        }
        return "User";
    }

    public PendingDisbursement findPendingDisbursementByEnvelopeId(Long envelopeId, String email) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new SecurityException("Envelope not found or not accessible"));
        return pendingDisbursementRepository.findFirstByEnvelopeIdAndStatus(envelopeId, Status.PENDING)
                .filter(pd -> pd.getExpiresAt().isAfter(now))
                .orElse(null);
    }

    /**
     * Validates envelope conditions both structurally (required fields) and
     * contextually (does the release plan make sense for the budget duration?).
     *
     * <p>Duration tiers — mirrors the Flutter add/edit envelope dialog logic:
     * <ul>
     *   <li>1 day  → only {@code emergency} is meaningful</li>
     *   <li>2–6 days  → {@code daily}, {@code dynamic}, {@code emergency}</li>
     *   <li>7 days  → {@code daily}, {@code emergency}</li>
     *   <li>8–13 days → {@code daily}, {@code dynamic}, {@code emergency}</li>
     *   <li>14+ days  → all types</li>
     * </ul>
     */
    private void validateEnvelopeConditions(EnvelopeRequest request,
                                             BigDecimal allocatedAmount,
                                             int budgetDurationDays) {
        Map<String, Object> conditions = request.getConditions();
        if (conditions == null || !conditions.containsKey("type")) {
            throw new IllegalArgumentException("Envelope conditions must include 'type'");
        }
        String type = conditions.get("type").toString().toLowerCase();

        // ── Duration-based plan eligibility (same rules as the Flutter UI) ───────
        boolean isOneDayBudget    = budgetDurationDays <= 1;
        boolean isShortBudget     = budgetDurationDays >= 2 && budgetDurationDays <= 6;
        boolean isOneWeekBudget   = budgetDurationDays == 7;
        boolean isMultiWeekBudget = budgetDurationDays >= 14;

        // savings_sweep is a one-shot instant transfer — duration is irrelevant
        if ("savings_sweep".equals(type)) {
            return;
        }

        if (isOneDayBudget && !"emergency".equals(type)) {
            throw new IllegalArgumentException(
                "A 1-day budget only supports the 'emergency' release plan.");
        }
        if (isShortBudget && "weekly".equals(type)) {
            throw new IllegalArgumentException(
                "A weekly release plan requires at least 8 days. " +
                "Your budget is only " + budgetDurationDays + " day(s).");
        }
        if (isOneWeekBudget && "weekly".equals(type)) {
            throw new IllegalArgumentException(
                "A weekly release plan fires only once in a 7-day budget. " +
                "Use daily, dynamic, or emergency instead.");
        }
        if (!isMultiWeekBudget && budgetDurationDays > 7 && "weekly".equals(type)) {
            throw new IllegalArgumentException(
                "A weekly release plan requires at least 14 days. " +
                "Your budget is only " + budgetDurationDays + " day(s).");
        }

        // ── Structural field validation ───────────────────────────────────────────
        switch (type) {
            case "daily":
                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
                    throw new IllegalArgumentException("Daily envelope must include a numeric 'limit'");
                }
                if (!conditions.containsKey("disbursementTime")) {
                    throw new IllegalArgumentException("Daily envelope must include 'disbursementTime'");
                }
                try {
                    LocalTime.parse((String) conditions.get("disbursementTime"));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid disbursementTime format: " + conditions.get("disbursementTime"));
                }
                break;
            case "weekly":
            case "monthly":
            case "quarterly":
            case "biannual":
                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
                    throw new IllegalArgumentException(type + " envelope must include a numeric 'limit'");
                }
                break;
            case "dynamic":
                if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number)) {
                    throw new IllegalArgumentException("Dynamic envelope must include a numeric 'limit'");
                }
                if (!conditions.containsKey("days") || ((List<?>) conditions.get("days")).isEmpty()) {
                    throw new IllegalArgumentException("Dynamic envelope must have non-empty 'days'");
                }
                if (!conditions.containsKey("disbursementTime")) {
                    throw new IllegalArgumentException("Dynamic envelope must include 'disbursementTime'");
                }
                try {
                    LocalTime.parse((String) conditions.get("disbursementTime"));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid disbursementTime format: " + conditions.get("disbursementTime"));
                }
                @SuppressWarnings("unchecked")
                List<String> days = (List<String>) conditions.get("days");
                try {
                    days.forEach(day -> DayOfWeek.valueOf(day.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid day in dynamic condition: " + e.getMessage());
                }
                break;
            case "safe_lock":
            case "strict_lock":
                if (!conditions.containsKey("lockStartDate") || !conditions.containsKey("lockDurationDays") || !conditions.containsKey("interestRate")) {
                    throw new IllegalArgumentException("Lock envelope must include 'lockStartDate', 'lockDurationDays', and 'interestRate'");
                }
                try {
                    LocalDate.parse((String) conditions.get("lockStartDate"));
                    Integer.parseInt(conditions.get("lockDurationDays").toString());
                    new BigDecimal(conditions.get("interestRate").toString());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid lock conditions: " + e.getMessage());
                }
                break;
            case "emergency":
                break;
            case "savings_sweep":
                if (!conditions.containsKey("targetSavingsGoalId")) {
                    throw new IllegalArgumentException("Savings sweep envelope must include 'targetSavingsGoalId'");
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported envelope type: " + type);
        }
    }

    // Ã°Å¸â€˜â€¡ NEW HELPER METHOD FOR STRICT VISIBILITY
    private BigDecimal getSpendableBalance(Envelope envelope) {
        LocalDateTime now = fetchCurrentDateTimeFromDatabase();
        Budget budget = envelope.getBudget();
        if (budget == null || budget.getStatus() != BudgetStatus.ACTIVE || budgetStartsInFuture(budget, now)) {
            return BigDecimal.ZERO;
        }

        Map<String, Object> conditions = envelope.getConditions();
        if (conditions == null) {
            return BigDecimal.ZERO;
        }
        String type = (String) conditions.getOrDefault("type", "");

        // Graceful Start: Always show money on creation day
        boolean createdToday = envelope.getCreatedAt().toLocalDate().isEqual(now.toLocalDate());

        try {
            if ("daily".equals(type)) {
                if (conditions.containsKey("disbursementTime")) {
                    LocalTime startTime = LocalTime.parse((String) conditions.get("disbursementTime"));
                    // STRICT: If not created today AND time is early -> HIDE MONEY
                    if (!createdToday && now.toLocalTime().isBefore(startTime)) {
                        return BigDecimal.ZERO;
                    }
                }
            }
            else if ("dynamic".equals(type)) {
                // 1. Day Check
                List<String> rawDays = (List<String>) conditions.getOrDefault("days", List.of());
                if (rawDays != null && !rawDays.isEmpty()) {
                    List<String> allowedDays = rawDays.stream().map(String::toUpperCase).collect(Collectors.toList());
                    String currentDay = now.getDayOfWeek().name();

                    if (!allowedDays.contains(currentDay)) {
                        return BigDecimal.ZERO; // Wrong Day -> HIDE MONEY
                    }
                }

                // 2. Time Check
                if (conditions.containsKey("disbursementTime")) {
                    LocalTime startTime = LocalTime.parse((String) conditions.get("disbursementTime"));
                    // If not created today AND time is early -> HIDE MONEY
                    if (!createdToday && now.toLocalTime().isBefore(startTime)) {
                        return BigDecimal.ZERO;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error calculating spendable balance for envelope {}", envelope.getId(), e);
        }

        // Default: If rules pass (or it's Weekly/Emergency), show the actual pocket money
        return envelope.getRemainingAmount();
    }

    /**
     * Returns true when both sender and recipient have Rubies wallets,
     * meaning the transfer can be routed as an internal Rubies book transfer
     * (bank code 090175, no NIBSS hop, near-instant settlement).
     *
     * <p>Falls back to the internal DB-only path for any other combination
     * (legacy Providus/SecureWave wallets or mixed providers).
     */
    private boolean shouldUseProviderBackedP2p(User sender, User recipient) {
        try {
            Wallet senderWallet    = walletService.getWalletByUserId(sender.getId());
            Wallet recipientWallet = walletService.getWalletByUserId(recipient.getId());
            return senderWallet    != null
                && recipientWallet != null
                && RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(senderWallet.getProviderName())
                && RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(recipientWallet.getProviderName());
        } catch (Exception e) {
            logger.warn("[P2P] Could not determine provider-backed P2P eligibility — falling back to internal path: {}",
                    e.getMessage());
            return false;
        }
    }

    private void assertNoAutoTransferInProgress(Long envelopeId) {
        boolean autoTransferInProgress =
                transactionLogRepository.existsBySourceEnvelopeIdAndTransactionTypeAndStatusInAndReferenceStartingWith(
                        envelopeId,
                        TransactionType.ENVELOPE_TO_EXTERNAL,
                        List.of(TransactionStatus.PENDING, TransactionStatus.PROCESSING),
                        "AUTO-EXT-");
        if (autoTransferInProgress) {
            throw new IllegalStateException(
                    "Auto-transfer is already processing for this envelope. Please wait for it to complete or fail.");
        }
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    @Transactional
    public ExternalTransferQuoteResponse quoteExternalTransfer(
            Long envelopeId,
            BigDecimal amount,
            String note,
            String email
    ) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        User user = userService.findByEmail(email);

        Envelope source = envelopeRepository.findById(envelopeId)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));

        if (!source.getBudget().getUser().getId().equals(user.getId())) {
            throw new SecurityException("You are not allowed to transfer from this envelope");
        }
        if (source.getBudget() == null || source.getBudget().getStatus() != BudgetStatus.ACTIVE) {
            throw new IllegalStateException("This budget is not active yet");
        }

        BigDecimal availableLimit = getRemainingLimit(envelopeId, email);

        source = envelopeRepository.findById(envelopeId)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found during refresh"));

        // ── Load the user's wallet once to detect which PSP they are on ─────────
        Wallet wallet = walletService.getWalletByUserId(user.getId());
        boolean isRubies = RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(
                wallet != null ? wallet.getProviderName() : null);

        if (isRubies) {
            // Rubies uses markup-tier fees, not the flat TransferFeeService fee table.
            // Settlement bank details are NOT pre-linked for Rubies users — they are
            // supplied at transfer time (OPay-style), so we return null for bank fields here.
            WithdrawalQuoteResponse quote = walletService.quoteWithdrawal(amount, user.getId());

            // markupFee  = Moniewise revenue (flat fee)
            // bankCharge = NIBSS NIP fee charged by Rubies (₦10.75 / ₦26.88 / ₦53.75)
            // stampDuty  = ₦50 on transfers > ₦10K
            // totalDebit = amount + bankCharge + markupFee + stampDuty
            BigDecimal markupFee   = quote.getFee();
            BigDecimal bankCharge  = quote.getBankCharge();
            BigDecimal stampDuty   = quote.getStampDuty();
            BigDecimal totalFee    = bankCharge.add(markupFee).add(stampDuty);
            BigDecimal totalDebit  = quote.getTotalDebit();

            // Fees come from the wallet — only check the send amount against the envelope limit.
            if (amount.compareTo(availableLimit) > 0) {
                throw new IllegalStateException(String.format(
                        "Insufficient envelope balance. Max sendable: ₦%,.2f.",
                        availableLimit));
            }

            BigDecimal availableVaultBalance =
                    safeAmount(source.getTotalRemainingAmount())
                            .subtract(safeAmount(source.getHeldAmount()));

            if (amount.compareTo(availableVaultBalance) > 0) {
                throw new IllegalStateException(String.format(
                        "Insufficient funds. Max sendable: ₦%,.2f.",
                        availableVaultBalance));
            }

            // Check wallet can cover the fees (show user now, before reaching review → PIN).
            BigDecimal walletBalance = safeAmount(wallet != null ? wallet.getBalance() : null);
            if (walletBalance.compareTo(totalFee) < 0) {
                throw new IllegalStateException(String.format(
                        "Your wallet balance is not enough to cover the \u20A6%,.2f transfer charges "
                                + "(NIP fee: \u20A6%,.2f + Service fee: \u20A6%,.2f). "
                                + "Your dashboard total includes money in budgets and savings, but transfer charges "
                                + "can only be paid from your wallet balance. Please top up your wallet to continue. "
                                + "Wallet available: \u20A6%,.2f.",
                        totalFee, bankCharge, markupFee, walletBalance));
            }

            return new ExternalTransferQuoteResponse(
                    envelopeId,
                    amount,
                    markupFee,
                    bankCharge,
                    stampDuty,
                    totalDebit,
                    quote.getRecipientReceives(),
                    RubiesGateway.PROVIDER_NAME,
                    quote.getFeePolicy(),
                    quote.getFeeSource(),
                    quote.getMessage(),
                    null,   // destination bank is supplied in the transfer request, not pre-linked
                    null,
                    null
            );
        }

        // ── Legacy path (Providus / SecureWave) ──────────────────────────────
        String providerName = providusExpressGateway.isEnabled()
                ? ProvidusExpressGateway.PROVIDER_NAME
                : SecureWaveGateway.PROVIDER_NAME;

        TransferFeeQuote feeQuote = transferFeeService.quoteFee(
                providerName,
                TransferFeeTransferType.ENVELOPE_TO_EXTERNAL,
                amount
        );

        BigDecimal fee = feeQuote.getFee();
        BigDecimal totalDebit = feeQuote.getTotalDebit();

        // Fees come from the wallet — only check the send amount against the envelope limit.
        if (amount.compareTo(availableLimit) > 0) {
            throw new IllegalStateException(String.format(
                    "Insufficient envelope balance. Max sendable: ₦%,.2f.",
                    availableLimit));
        }

        BigDecimal availableVaultBalance =
                safeAmount(source.getTotalRemainingAmount())
                        .subtract(safeAmount(source.getHeldAmount()));

        if (amount.compareTo(availableVaultBalance) > 0) {
            throw new IllegalStateException(String.format(
                    "Insufficient funds. Max sendable: ₦%,.2f.",
                    availableVaultBalance));
        }

        // Check wallet covers the service fee.
        BigDecimal legacyWalletBalance = safeAmount(wallet != null ? wallet.getBalance() : null);
        if (legacyWalletBalance.compareTo(fee) < 0) {
            throw new IllegalStateException(String.format(
                    "Your wallet balance is not enough to cover the \u20A6%,.2f service fee. "
                            + "Your dashboard total includes money in budgets and savings, but transfer charges "
                            + "can only be paid from your wallet balance. Please top up your wallet to continue. "
                            + "Wallet available: \u20A6%,.2f.",
                    fee, legacyWalletBalance));
        }

        if (wallet.getSettlementAccountNumber() == null || wallet.getSettlementAccountNumber().isBlank()) {
            throw new IllegalStateException("Please link a withdrawal bank account before transferring.");
        }

        return new ExternalTransferQuoteResponse(
                envelopeId,
                amount,
                fee,
                BigDecimal.ZERO,    // non-Rubies: no separate NIP bank charge to expose
                BigDecimal.ZERO,    // non-Rubies: no stamp duty
                totalDebit,
                feeQuote.getRecipientReceives(),
                providerName,
                feeQuote.getFeeType().name(),
                feeQuote.getFeeSource().name(),
                buildEnvelopeTransferFeeMessage(amount, fee, totalDebit),
                wallet.getSettlementBankName(),
                wallet.getSettlementAccountNumber(),
                wallet.getSettlementAccountName()
        );
    }

    private String buildEnvelopeTransferFeeMessage(BigDecimal amount, BigDecimal fee, BigDecimal totalDebit) {
        if (fee.compareTo(BigDecimal.ZERO) <= 0) {
            return "No transfer fee applies. ₦" + formatMoney(amount) + " will be sent.";
        }

        // Fee is deducted from the main wallet — only the send amount leaves the envelope.
        return "A ₦" + formatMoney(fee) + " service fee will be charged to your wallet. "
                + "₦" + formatMoney(amount) + " will be deducted from this envelope.";
    }

    private String formatMoney(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private boolean shouldAutoSettleExternalTransfer(String providerName) {
        return providerName != null && providerName.equalsIgnoreCase("SECUREWAVE");
    }

    @Transactional
    public void finalizeExternalTransferAcceptedWithoutWebhook(
            Long sourceEnvelopeId,
            String transactionReference,
            String providerReference,
            BigDecimal totalDebit
    ) {
        Envelope source = envelopeRepository.findById(sourceEnvelopeId)
                .orElseThrow(() -> new EntityNotFoundException("Source envelope not found"));

        BigDecimal heldAmount = source.getHeldAmount() != null
                ? source.getHeldAmount()
                : BigDecimal.ZERO;

        BigDecimal totalRemainingAmount = source.getTotalRemainingAmount() != null
                ? source.getTotalRemainingAmount()
                : BigDecimal.ZERO;

        if (heldAmount.compareTo(totalDebit) < 0) {
            logger.warn(
                    "Envelope {} heldAmount {} is less than totalDebit {} during SecureWave auto-settlement",
                    sourceEnvelopeId,
                    heldAmount,
                    totalDebit
            );
        }

        source.setHeldAmount(
                heldAmount.subtract(totalDebit).max(BigDecimal.ZERO)
        );

        source.setTotalRemainingAmount(
                totalRemainingAmount.subtract(totalDebit).max(BigDecimal.ZERO)
        );

        if (source.getRemainingAmount() != null && source.getRemainingAmount().compareTo(BigDecimal.ZERO) < 0) {
            source.setRemainingAmount(BigDecimal.ZERO);
        }

        envelopeRepository.save(source);

        transactionLogRepository.findByReference(transactionReference).ifPresent(txn -> {
            txn.setProviderReference(providerReference);
            txn.setStatus(TransactionStatus.COMPLETED);
            txn.setDescription(appendDescription(txn.getDescription(), "Confirmed by SecureWave accepted response"));
            transactionLogRepository.save(txn);
        });

        transactionLogRepository.findByReference(transactionReference + "-FEE").ifPresent(feeTxn -> {
            feeTxn.setProviderReference(providerReference);
            feeTxn.setStatus(TransactionStatus.COMPLETED);
            feeTxn.setDescription(appendDescription(feeTxn.getDescription(), "Confirmed by SecureWave accepted response"));
            transactionLogRepository.save(feeTxn);
        });

        if (source.getBudget() != null && source.getBudget().getUser() != null) {
            // Activation journey off-switch: comment out this one invocation to
            // stop the first direct-spend completion push/email.
            activationJourneyNudgeService.nudgeAfterFirstDirectSpend(
                    source.getBudget().getUser().getId(),
                    source.getBudget().getId(),
                    source.getId());
        }

        logger.info(
                "SecureWave external transfer auto-settled. envelopeId={}, reference={}, providerReference={}, totalDebit={}",
                sourceEnvelopeId,
                transactionReference,
                providerReference,
                totalDebit
        );
    }

    private String appendDescription(String oldDescription, String extra) {
        if (oldDescription == null || oldDescription.isBlank()) {
            return extra;
        }

        if (oldDescription.contains(extra)) {
            return oldDescription;
        }

        return oldDescription + " | " + extra;
    }
}
