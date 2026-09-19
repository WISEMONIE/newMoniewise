package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.request.AutoTransferSetupRequest;
import com.moniewise.moniewise_backend.dto.response.AutoTransferResponse;
import com.moniewise.moniewise_backend.dto.response.WithdrawalQuoteResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EnvelopeAutoTransferService {

    private static final Logger logger = LoggerFactory.getLogger(EnvelopeAutoTransferService.class);

    private final EnvelopeAutoTransferRepository autoTransferRepository;
    private final EnvelopeRepository envelopeRepository;
    private final UserService userService;
    private final WalletService walletService;
    private final PaymentGatewayResolver paymentGatewayResolver;
    private final TransactionLogRepository transactionLogRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final ProcessingTransferRecoveryScheduler transferRecoveryScheduler;
    private final MonnieCacheInvalidationService monnieCacheInvalidationService;

    public EnvelopeAutoTransferService(
            EnvelopeAutoTransferRepository autoTransferRepository,
            EnvelopeRepository envelopeRepository,
            UserService userService,
            WalletService walletService,
            PaymentGatewayResolver paymentGatewayResolver,
            TransactionLogRepository transactionLogRepository,
            OutboxEventRepository outboxEventRepository,
            ScheduledTaskRepository scheduledTaskRepository,
            @Lazy ProcessingTransferRecoveryScheduler transferRecoveryScheduler,
            MonnieCacheInvalidationService monnieCacheInvalidationService) {
        this.autoTransferRepository = autoTransferRepository;
        this.envelopeRepository = envelopeRepository;
        this.userService = userService;
        this.walletService = walletService;
        this.paymentGatewayResolver = paymentGatewayResolver;
        this.transactionLogRepository = transactionLogRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.transferRecoveryScheduler = transferRecoveryScheduler;
        this.monnieCacheInvalidationService = monnieCacheInvalidationService;
    }

    @Transactional
    public AutoTransferResponse setupAutoTransfer(Long envelopeId, AutoTransferSetupRequest request, String email) {
        User user = userService.findByEmail(email);

        if (!userService.verifyTransactionPin(user, request.getTransactionPin())) {
            throw new IllegalArgumentException("Invalid transaction PIN");
        }

        Envelope envelope = envelopeRepository.findByIdAndBudgetUserEmailForUpdate(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));

        String resolvedName = walletService.resolveBankAccount(
                user.getId(), request.getBankCode(), request.getAccountNumber());
        if (resolvedName == null || resolvedName.isBlank()) {
            throw new IllegalArgumentException("Could not resolve account name — please verify the bank code and account number.");
        }

        EnvelopeAutoTransfer config = autoTransferRepository.findByEnvelopeId(envelopeId)
                .orElse(new EnvelopeAutoTransfer());
        config.setEnvelope(envelope);
        config.setUser(user);
        config.setBankCode(request.getBankCode());
        config.setBankName(request.getBankName());
        config.setAccountNumber(request.getAccountNumber());
        config.setAccountName(resolvedName);
        config.setUpdatedAt(LocalDateTime.now());
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(LocalDateTime.now());
        }
        autoTransferRepository.save(config);

        envelope.setIsAutomated(true);
        envelopeRepository.save(envelope);

        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());

        return buildResponse(config, envelope);
    }

    @Transactional
    public void disableAutoTransfer(Long envelopeId, String email) {
        User user = userService.findByEmail(email);
        Envelope envelope = envelopeRepository.findByIdAndBudgetUserEmailForUpdate(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));

        autoTransferRepository.deleteByEnvelopeId(envelopeId);

        envelope.setIsAutomated(false);
        envelopeRepository.save(envelope);

        scheduledTaskRepository.cancelPendingByEnvelopeIdAndTaskType(envelopeId, "AUTO_TRANSFER");

        monnieCacheInvalidationService.evictUserAfterCommit(user.getId());
    }

    public AutoTransferResponse getAutoTransferStatus(Long envelopeId, String email) {
        User user = userService.findByEmail(email);
        Envelope envelope = envelopeRepository.findByIdAndBudget_UserEmail(envelopeId, email)
                .orElseThrow(() -> new EntityNotFoundException("Envelope not found"));

        EnvelopeAutoTransfer config = autoTransferRepository.findByEnvelopeIdAndUserId(envelopeId, user.getId())
                .orElse(null);

        if (config == null && !isAutoTransferProcessStarted(envelopeId)) return null;
        return buildResponse(config, envelope);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeAutoTransfer(Long envelopeId) {
        Envelope envelope = envelopeRepository.findByIdForUpdate(envelopeId).orElse(null);
        if (envelope == null) {
            logger.warn("Auto-transfer: envelope {} not found", envelopeId);
            return;
        }

        if (!Boolean.TRUE.equals(envelope.getIsAutomated())) {
            logger.info("Auto-transfer skipped for envelope {}: automation disabled", envelopeId);
            return;
        }

        EnvelopeAutoTransfer config = autoTransferRepository.findByEnvelopeId(envelopeId).orElse(null);
        if (config == null) {
            logger.warn("Auto-transfer: no config for envelope {}", envelopeId);
            return;
        }

        BigDecimal amount = envelope.getRemainingAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            logger.info("Auto-transfer skipped for envelope {}: no disbursed amount available", envelopeId);
            return;
        }

        User user = config.getUser();
        Long userId = user.getId();
        Wallet wallet = walletService.getWalletByUserId(userId);

        if (wallet == null || wallet.getProviderWalletRef() == null) {
            logger.error("Auto-transfer: wallet not configured for user {}", userId);
            saveNotification(NotificationType.AUTO_TRANSFER_FAILED, envelope,
                    Map.of("envelopeName", safeName(envelope), "reason", "Wallet not configured"));
            return;
        }

        boolean isRubies = RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(wallet.getProviderName());
        if (!isRubies) {
            logger.error("Auto-transfer: only Rubies wallets are supported. User {} has {}", userId, wallet.getProviderName());
            saveNotification(NotificationType.AUTO_TRANSFER_FAILED, envelope,
                    Map.of("envelopeName", safeName(envelope), "reason", "Wallet provider not supported for auto-transfers"));
            return;
        }

        WithdrawalQuoteResponse quote = walletService.quoteWithdrawal(amount, userId);
        BigDecimal markupFee = quote.getFee();
        BigDecimal bankCharge = quote.getBankCharge();
        BigDecimal stampDuty = quote.getStampDuty();
        BigDecimal totalFee = bankCharge.add(markupFee).add(stampDuty);

        BigDecimal walletBalance = wallet.getBalance() != null ? wallet.getBalance() : BigDecimal.ZERO;
        if (walletBalance.compareTo(totalFee) < 0) {
            logger.warn("Auto-transfer skipped for envelope {}: wallet cannot cover fees. Need ₦{}, have ₦{}",
                    envelopeId, totalFee, walletBalance);
            saveNotification(NotificationType.AUTO_TRANSFER_INSUFFICIENT_FUNDS, envelope,
                    Map.of("envelopeName", safeName(envelope),
                            "amount", String.format("%,.2f", amount),
                            "fee", String.format("%,.2f", totalFee)));
            return;
        }

        BigDecimal availableVault = safeAmount(envelope.getTotalRemainingAmount())
                .subtract(safeAmount(envelope.getHeldAmount()));
        if (amount.compareTo(availableVault) > 0) {
            logger.warn("Auto-transfer skipped for envelope {}: insufficient vault balance. Need ₦{}, available ₦{}",
                    envelopeId, amount, availableVault);
            saveNotification(NotificationType.AUTO_TRANSFER_INSUFFICIENT_FUNDS, envelope,
                    Map.of("envelopeName", safeName(envelope),
                            "amount", String.format("%,.2f", amount),
                            "fee", String.format("%,.2f", totalFee)));
            return;
        }

        walletService.deductTransferFee(userId, totalFee, bankCharge, markupFee);

        envelope.setRemainingAmount(envelope.getRemainingAmount().subtract(amount));
        envelope.setHeldAmount(safeAmount(envelope.getHeldAmount()).add(amount));
        envelopeRepository.save(envelope);

        String reference = "AUTO-EXT-" + UUID.randomUUID();

        TransactionLog feeTxn = null;
        if (markupFee.compareTo(BigDecimal.ZERO) > 0) {
            feeTxn = TransactionLog.builder()
                    .userId(userId)
                    .budgetId(envelope.getBudget().getId())
                    .sourceEnvelopeId(envelopeId)
                    .externalBankName(config.getBankName())
                    .externalBankCode(config.getBankCode())
                    .externalAccountNumber(config.getAccountNumber())
                    .externalAccountName(config.getAccountName())
                    .amount(markupFee.negate())
                    .fee(BigDecimal.ZERO)
                    .transactionType(TransactionType.ENVELOPE_EXTERNAL_TRANSFER_FEE)
                    .status(TransactionStatus.PENDING)
                    .reference(reference + "-FEE")
                    .providerName(RubiesGateway.PROVIDER_NAME)
                    .description("Auto-transfer fee for " + reference)
                    .createdAt(LocalDateTime.now())
                    .build();
            transactionLogRepository.save(feeTxn);
        }

        TransactionLog txn = TransactionLog.builder()
                .userId(userId)
                .budgetId(envelope.getBudget().getId())
                .sourceEnvelopeId(envelopeId)
                .externalBankName(config.getBankName())
                .externalBankCode(config.getBankCode())
                .externalAccountNumber(config.getAccountNumber())
                .externalAccountName(config.getAccountName())
                .amount(amount.negate())
                .fee(markupFee)
                .stampDuty(stampDuty)
                .transactionType(TransactionType.ENVELOPE_TO_EXTERNAL)
                .status(TransactionStatus.PENDING)
                .reference(reference)
                .providerName(RubiesGateway.PROVIDER_NAME)
                .description("Auto-transfer to " + config.getAccountName() + " (" + config.getBankName() + ")")
                .createdAt(LocalDateTime.now())
                .build();
        transactionLogRepository.save(txn);

        if (totalFee.compareTo(BigDecimal.ZERO) > 0) {
            walletService.logTransferFeeDebit(
                    userId, totalFee, bankCharge, markupFee,
                    reference, amount, config.getAccountName(), config.getBankName());
        }

        PaymentGateway rubiesGateway = paymentGatewayResolver.resolveByProviderName(RubiesGateway.PROVIDER_NAME);
        String providerRef = rubiesGateway.initiateTransferWithContext(
                wallet.getProviderWalletRef(),
                walletService.resolveDisplayName(user),
                config.getBankCode(),
                config.getBankName(),
                config.getAccountNumber(),
                config.getAccountName(),
                amount,
                reference,
                "Auto-transfer from " + envelope.getName()
        );

        txn.setProviderReference(providerRef);
        txn.setStatus(TransactionStatus.PROCESSING);
        transactionLogRepository.save(txn);

        if (feeTxn != null) {
            feeTxn.setStatus(TransactionStatus.PROCESSING);
            transactionLogRepository.save(feeTxn);
        }

        transferRecoveryScheduler.scheduleImmediateRecovery(reference, providerRef);

        monnieCacheInvalidationService.evictUserAfterCommit(userId);


        logger.info("Auto-transfer initiated: envelope={}, amount=₦{}, ref={}, recipient={}",
                envelopeId, amount, reference, config.getAccountName());
    }

    public void notifyAutoTransferExhausted(Envelope envelope) {
        saveNotification(NotificationType.AUTO_TRANSFER_FAILED, envelope,
                Map.of("envelopeName", safeName(envelope),
                        "reason", "Auto-transfer failed after 3 attempts. Please check your envelope and try a manual transfer."));
    }

    private void saveNotification(NotificationType type, Envelope envelope, Map<String, Object> params) {
        Long userId = envelope.getBudget().getUser().getId();
        Long budgetId = envelope.getBudget().getId();

        OutboxEvent event = new OutboxEvent();
        event.setEventType(type.name());
        event.setUserId(userId);
        event.setBudgetId(budgetId);
        event.setEnvelopeId(envelope.getId());
        event.setPayload(new HashMap<>(params));
        event.setStatus("PENDING");
        event.setRetryCount(0);
        event.setCreatedAt(LocalDateTime.now());
        event.setTtlSeconds(86_400L);
        outboxEventRepository.save(event);
    }

    private AutoTransferResponse buildResponse(EnvelopeAutoTransfer config, Envelope envelope) {
        AutoTransferResponse response = new AutoTransferResponse();
        boolean processing = isAutoTransferProcessStarted(envelope.getId());
        response.setId(config != null ? config.getId() : null);
        response.setEnvelopeId(envelope.getId());
        response.setBankCode(config != null ? config.getBankCode() : null);
        response.setBankName(config != null ? config.getBankName() : null);
        response.setAccountNumber(config != null ? config.getAccountNumber() : null);
        response.setAccountName(config != null ? config.getAccountName() : null);
        response.setAutomated(Boolean.TRUE.equals(envelope.getIsAutomated()));
        response.setProcessing(processing);
        response.setProcessStatus(processing
                ? "PROCESSING"
                : (Boolean.TRUE.equals(envelope.getIsAutomated()) ? "ACTIVE" : "DISABLED"));
        response.setProcessMessage(processing
                ? "Auto-transfer has started and is awaiting final confirmation."
                : null);
        response.setCreatedAt(config != null ? config.getCreatedAt() : null);
        return response;
    }

    private boolean isAutoTransferProcessStarted(Long envelopeId) {
        return transactionLogRepository.existsBySourceEnvelopeIdAndTransactionTypeAndStatusInAndReferenceStartingWith(
                envelopeId,
                TransactionType.ENVELOPE_TO_EXTERNAL,
                List.of(TransactionStatus.PENDING, TransactionStatus.PROCESSING),
                "AUTO-EXT-");
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private String safeName(Envelope envelope) {
        return envelope.getName() != null ? envelope.getName() : "Envelope";
    }
}
