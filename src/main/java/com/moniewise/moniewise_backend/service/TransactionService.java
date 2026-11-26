package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.TransactionDecisionResponseDto;
import com.moniewise.moniewise_backend.dto.WithdrawalInitiationResponseDto;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.TransactionDetailResponse;
import com.moniewise.moniewise_backend.dto.response.TransactionListResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

import static com.moniewise.moniewise_backend.enums.TransactionType.*;

@Service
@Transactional(readOnly = true)
public class TransactionService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);

    private final DecisionEngineService decisionEngineService;
    private final WalletService walletService;
    private final TransactionRequestRepository transactionRequestRepository;
    private final TransactionDecisionRepository transactionDecisionRepository;
    private final UserRepository userRepository;
    private final TransactionLogRepository transactionLogRepo;
    private final EnvelopeRepository envelopeRepo;
    private final TransactionPresentation presentation;

    private static final Set<TransactionType> USER_VISIBLE_TYPES = Set.of(
            WALLET_DEPOSIT,
            WALLET_TO_BUDGET,
            BUDGET_ALLOCATION,
            ENVELOPE_TO_ENVELOPE,
            ENVELOPE_TO_EXTERNAL,
            ENVELOPE_TO_USER,
            USER_TO_ENVELOPE,
            BUDGET_CREATION_FEE,
            BUDGET_UNALLOCATED_REFUNDED,
            STRICT_LOCK_ROLLBACK,
            DISBURSEMENT_REFUNDED,
            WALLET_WITHDRAWAL,
            // Wallet-side fee debit for envelope-to-bank transfers.
            // Shown so users understand why their wallet balance dropped.
            WALLET_ENVELOPE_TRANSFER_FEE,
            // Airtime & data purchases (Payeelord VAS).
            VAS_PURCHASE,
            RECONCILIATION_CREDIT,
            // Savings pot movements — without these the user's savings
            // withdrawals, top-ups and pot-to-friend sends were invisible in
            // the transactions list even though the money moved.
            SAVINGS_WITHDRAWAL,
            SAVINGS_DEPOSIT,
            USER_TO_USER,
            WALLET_TO_USER,
            BUDGET_TOP_UP,
            BUDGET_CREATION_FEE_REFUND
    );

    private static final Set<TransactionType> USER_VISIBLE_OUTGOING_TYPES = EnumSet.of(
            ENVELOPE_DISBURSEMENT,
            ENVELOPE_TO_EXTERNAL,
            ENVELOPE_TO_ENVELOPE,
            ENVELOPE_TO_USER,
            WALLET_DEDUCTION,
            BUDGET_CREATION_FEE,
            BUDGET_ALLOCATION,
            WALLET_WITHDRAWAL,
            WALLET_WITHDRAWAL_FEE,
            WALLET_ENVELOPE_TRANSFER_FEE,
            WALLET_TO_BUDGET,
            VAS_PURCHASE,
            BUDGET_TOP_UP,
            WALLET_TO_USER
    );

    private static final Set<TransactionStatus> BALANCE_AFFECTING_STATUSES = EnumSet.of(
            TransactionStatus.COMPLETED,
            TransactionStatus.PROCESSING,
            TransactionStatus.PENDING,
            TransactionStatus.SUCCESS
    );

    public TransactionService(
            DecisionEngineService decisionEngineService,
            WalletService walletService,
            TransactionRequestRepository transactionRequestRepository,
            TransactionDecisionRepository transactionDecisionRepository,
            UserRepository userRepository,
            TransactionLogRepository transactionLogRepo,
            EnvelopeRepository envelopeRepo,
            TransactionPresentation presentation
    ) {
        this.decisionEngineService = decisionEngineService;
        this.walletService = walletService;
        this.transactionRequestRepository = transactionRequestRepository;
        this.transactionDecisionRepository = transactionDecisionRepository;
        this.userRepository = userRepository;
        this.transactionLogRepo = transactionLogRepo;
        this.envelopeRepo = envelopeRepo;
        this.presentation = presentation;
    }

    @Transactional
    public WithdrawalInitiationResponseDto initiateWithdrawal(Long userId, WithdrawalRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String clientReference = UUID.randomUUID().toString();
        TransactionRequest txRequest = new TransactionRequest();
        txRequest.setUser(user);
        txRequest.setAmount(request.getAmount());
        txRequest.setTransactionType(TransactionRequest.TransactionType.WITHDRAWAL);
        txRequest.setClientReference(clientReference);
        txRequest = transactionRequestRepository.save(txRequest);

        TransactionDecision.Decision decision = decisionEngineService.evaluateRequest(txRequest);

        TransactionDecision txDecision = new TransactionDecision();
        txDecision.setTransactionRequest(txRequest);
        txDecision.setDecision(decision);
        txDecision.setKycCheckPassed(true);
        txDecision.setTierCheckPassed(true);
        txDecision.setWalletCheckPassed(true);
        txDecision.setBudgetCheckPassed(true);
        txDecision.setRequiresConfirmation(false);
        if (decision == TransactionDecision.Decision.BLOCK) {
            txDecision.setReasonCode("BLOCKED");
            txDecision.setReasonMessage("Transaction blocked by decision engine");
        }
        transactionDecisionRepository.save(txDecision);

        if (decision == TransactionDecision.Decision.BLOCK) {
            txRequest.setStatus(TransactionRequest.Status.BLOCKED);
            transactionRequestRepository.save(txRequest);
            return new WithdrawalInitiationResponseDto(
                    false,
                    decision,
                    "BLOCKED",
                    "Transaction blocked",
                    txRequest.getId(),
                    null,
                    request.getAmount(),
                    request.getAmount(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    request.getAmount(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO
            );
        }

        Withdrawal withdrawal = walletService.processWithdrawal(userId, request);
        txRequest.setStatus(TransactionRequest.Status.COMPLETED);
        transactionRequestRepository.save(txRequest);

        BigDecimal bankCharge = withdrawal.getTotalDebit()
                .subtract(withdrawal.getAmount())
                .subtract(withdrawal.getFeeAmount());
        BigDecimal remainingBalance = walletService.getWalletByUserId(userId).getBalance();

        return new WithdrawalInitiationResponseDto(
                true,
                decision,
                null,
                null,
                txRequest.getId(),
                withdrawal.getClientReference(),
                withdrawal.getAmount(),
                withdrawal.getAmount(),
                withdrawal.getFeeAmount(),
                bankCharge,
                withdrawal.getTotalDebit(),
                withdrawal.getRecipientReceives(),
                remainingBalance
        );
    }

    @Transactional
    public void initiateTransfer(Long userId, BigDecimal amount, Long sourceEnvelopeId, String destinationReference) {
        // Placeholder until transfer workflow is implemented.
    }

    public Page<TransactionListResponse> getTransactionsForUser(Long userId, int page, int size) {
        int boundedSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, boundedSize);
        Page<TransactionLog> transactionPage =
                transactionLogRepo.findUserVisibleTransactions(userId, USER_VISIBLE_TYPES, pageable);
        return toVisiblePage(userId, transactionPage, pageable);
    }

    /** Same shape as {@link #getTransactionsForUser}, scoped to one envelope (source or target). */
    public Page<TransactionListResponse> getTransactionsForEnvelope(Long userId, Long envelopeId, int page, int size) {
        int boundedSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, boundedSize);
        Page<TransactionLog> transactionPage =
                transactionLogRepo.findByEnvelopeIdAndTransactionTypeIn(userId, envelopeId, USER_VISIBLE_TYPES, pageable);
        return toVisiblePage(userId, transactionPage, pageable);
    }

    /**
     * One bad row must not fail the whole page. If every row fails, the
     * original error is thrown so the client keeps the list it already has.
     */
    private Page<TransactionListResponse> toVisiblePage(Long userId,
                                                         Page<TransactionLog> transactionPage,
                                                         Pageable pageable) {
        Set<Long> envelopeIds = new HashSet<>();
        for (TransactionLog transaction : transactionPage.getContent()) {
            if (transaction.getSourceEnvelopeId() != null) {
                envelopeIds.add(transaction.getSourceEnvelopeId());
            }
            if (transaction.getTargetEnvelopeId() != null) {
                envelopeIds.add(transaction.getTargetEnvelopeId());
            }
        }

        Map<Long, String> envelopeNames = new HashMap<>();
        if (!envelopeIds.isEmpty()) {
            try {
                envelopeRepo.findAllById(envelopeIds)
                        .forEach(envelope -> envelopeNames.put(envelope.getId(), envelope.getName()));
            } catch (RuntimeException ex) {
                logger.warn("Envelope names were not loaded for user {} transaction list ({})",
                        userId, ex.getClass().getSimpleName());
            }
        }

        List<TransactionListResponse> rows = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (TransactionLog transaction : transactionPage.getContent()) {
            try {
                rows.add(mapToListResponse(transaction, envelopeNames));
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
                logger.warn("Transaction {} was left out of the list for user {} ({})",
                        transaction.getId(), userId, ex.getClass().getSimpleName());
            }
        }
        if (rows.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        return new PageImpl<>(rows, pageable, transactionPage.getTotalElements());
    }

    public TransactionDetailResponse getTransactionDetail(Long transactionId, Long userId) {
        TransactionLog log = transactionLogRepo.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        if (!log.getUserId().equals(userId)) {
            throw new SecurityException("Access denied");
        }

        return mapToDetailResponse(log);
    }

    public Map<String, BigDecimal> getMonthTotalsForUser(Long userId, YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();

        Set<TransactionType> incomingTypes = EnumSet.copyOf(USER_VISIBLE_TYPES);
        incomingTypes.removeAll(USER_VISIBLE_OUTGOING_TYPES);

        BigDecimal incoming = transactionLogRepo.sumAbsoluteAmountByUserAndDateRangeAndTypes(
                userId,
                start,
                end,
                incomingTypes,
                BALANCE_AFFECTING_STATUSES
        );
        BigDecimal outgoingAbs = transactionLogRepo.sumAbsoluteAmountByUserAndDateRangeAndTypes(
                userId,
                start,
                end,
                USER_VISIBLE_OUTGOING_TYPES,
                BALANCE_AFFECTING_STATUSES
        );
        BigDecimal fees = transactionLogRepo.sumFeesByUserAndDateRangeAndTypes(
                userId,
                start,
                end,
                USER_VISIBLE_TYPES,
                BALANCE_AFFECTING_STATUSES
        );

        Map<String, BigDecimal> totals = new HashMap<>();
        totals.put("incoming", incoming);
        totals.put("outgoingAbs", outgoingAbs);
        totals.put("fees", fees);
        return totals;
    }

    public TransactionDecisionResponseDto getDecision(Long transactionRequestId, Long userId) {
        TransactionDecision decision = transactionDecisionRepository
                .findByTransactionRequestId(transactionRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Decision not found"));

        // BOLA guard: a decision belongs to the user who owns the underlying
        // transaction request. Mirrors the ownership check in
        // getTransactionDetail — every id-addressable read must be scoped.
        if (!decision.getTransactionRequest().getUser().getId().equals(userId)) {
            throw new SecurityException("Access denied");
        }

        return new TransactionDecisionResponseDto(
                decision.getTransactionRequest().getId(),
                decision.getDecision(),
                decision.getReasonCode(),
                decision.getReasonMessage(),
                decision.isRequiresConfirmation()
        );
    }

    private TransactionListResponse mapToListResponse(TransactionLog transaction, Map<Long, String> envelopeNameCache) {
        String sourceName = transaction.getSourceEnvelopeId() != null
                ? envelopeNameCache.getOrDefault(transaction.getSourceEnvelopeId(), "Unknown Envelope")
                : "System";

        String targetName = transaction.getTargetEnvelopeId() != null
                ? envelopeNameCache.getOrDefault(transaction.getTargetEnvelopeId(), "Unknown Envelope")
                : "System";

        boolean reversed = isReversedOrFailed(transaction.getStatus());
        boolean outgoing = !reversed && isOutgoing(transaction.getTransactionType(), transaction.getAmount());
        BigDecimal displayAmount = transaction.getAmount() != null ? transaction.getAmount().abs() : BigDecimal.ZERO;
        if (outgoing) {
            displayAmount = displayAmount.negate();
        }
        String direction = outgoing ? "OUT" : "IN";

        String title;
        String subtitle = transaction.getDescription();
        String iconType;

        switch (transaction.getTransactionType()) {
            case ENVELOPE_TO_USER:
            case WALLET_TO_USER:
                title = "Transfer to " + getCounterpartyName(transaction);
                subtitle = "P2P Transfer";
                iconType = "USER";
                break;
            case USER_TO_ENVELOPE:
            case USER_TO_USER:
                title = "Received from " + getCounterpartyName(transaction);
                subtitle = "P2P Received";
                iconType = "USER";
                break;
            case ENVELOPE_TO_EXTERNAL:
                title = transaction.getDescription() != null
                        ? transaction.getDescription().replace("Transfer to ", "")
                        : "Bank Transfer";
                subtitle = "Bank Transfer";
                iconType = "BANK";
                break;
            case WALLET_ENVELOPE_TRANSFER_FEE:
                String feeRecipient = transaction.getExternalAccountName();
                title = (feeRecipient != null && !feeRecipient.isBlank())
                        ? "Transfer fee — " + feeRecipient
                        : "Transfer fee";
                subtitle = "Bank transfer fee";
                iconType = "FEE";
                break;
            case ENVELOPE_TO_ENVELOPE:
                title = "Moved to " + targetName;
                subtitle = "From " + sourceName;
                iconType = "SWAP";
                break;
            case WALLET_DEPOSIT:
                title = "Wallet Funded";
                subtitle = "Deposit";
                iconType = "WALLET";
                break;
            case RECONCILIATION_CREDIT:
                title = "Wallet Credit Recovered";
                subtitle = "Reconciliation credit";
                iconType = "WALLET";
                break;
            case ENVELOPE_DISBURSEMENT:
                title = "Daily Disbursement";
                subtitle = sourceName;
                iconType = "CASH";
                break;
            case VAS_PURCHASE:
                title = transaction.getDescription() != null
                        ? transaction.getDescription()
                        : "Airtime & Data";
                subtitle = "Airtime & Data";
                iconType = "AIRTIME";
                break;
            default:
                title = formatEnumName(transaction.getTransactionType());
                subtitle = "Transaction";
                iconType = "DEFAULT";
                break;
        }

        if (reversed) {
            switch (transaction.getTransactionType()) {
                case ENVELOPE_TO_EXTERNAL:
                    title = "Transfer reversed";
                    subtitle = "Refunded to " + sourceName;
                    iconType = "BANK";
                    break;
                case WALLET_WITHDRAWAL:
                    title = "Transfer reversed";
                    subtitle = "Refunded to wallet";
                    iconType = "BANK";
                    break;
                case WALLET_ENVELOPE_TRANSFER_FEE:
                case WALLET_WITHDRAWAL_FEE:
                    title = "Fee reversed";
                    subtitle = "Refunded to wallet";
                    iconType = "FEE";
                    break;
                case VAS_PURCHASE:
                    title = "Purchase reversed";
                    subtitle = "Refunded to envelope";
                    iconType = "AIRTIME";
                    break;
                default:
                    title = "Transaction reversed";
                    subtitle = transaction.getDescription();
                    iconType = "DEFAULT";
                    break;
            }
        }

        String path = "/transactions/" + transaction.getId();

        return new TransactionListResponse(
                transaction.getId(),
                title,
                subtitle,
                displayAmount,
                null,
                iconType,
                direction,
                path,
                transaction.getCreatedAt(),
                transaction.getStatus(),
                transaction.getTransactionType()
        );
    }

    private String formatEnumName(TransactionType type) {
        return TransactionPresentation.formatEnumName(type);
    }

    private String getCounterpartyName(TransactionLog transaction) {
        return presentation.counterpartyName(transaction, TransactionPresentation.Names.SCREEN);
    }

    private TransactionDetailResponse mapToDetailResponse(TransactionLog transaction) {
        TransactionPresentation.Display display = presentation.describe(
                transaction, TransactionPresentation.Names.SCREEN);
        return new TransactionDetailResponse(
                transaction.getId(),
                display.reference(),
                display.status(),
                display.direction(),
                display.title(),
                transaction.getDescription(),
                display.amount(),
                display.fee(),
                display.stampDuty(),
                display.netAmount(),
                display.sender(),
                display.recipient(),
                transaction.getTransactionType(),
                transaction.getCreatedAt(),
                transaction.getBudgetId(),
                display.budgetName(),
                display.sourceName(),
                transaction.getSourceEnvelopeId(),
                display.effectiveTargetName(),
                transaction.getTargetEnvelopeId(),
                transaction.getExternalAccountId(),
                transaction.getExternalBankName(),
                display.accountNumber()
        );
    }

    private boolean isOutgoing(TransactionType type, BigDecimal amount) {
        return TransactionPresentation.isOutgoing(type, amount);
    }

    private boolean isReversedOrFailed(TransactionStatus status) {
        return TransactionPresentation.isReversed(status);
    }
}
