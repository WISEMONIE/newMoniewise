package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words and figures a person sees for one saved transfer.
 * The transaction screen and the receipt both read this, so they cannot drift.
 */
@Service
public class TransactionPresentation {

    /**
     * The activity screen may fall back to the email name. A receipt never does.
     */
    public enum Names {
        SCREEN,
        RECEIPT
    }

    private static final Pattern PHONE = Pattern.compile("\\d{10,14}");

    private final UserRepository userRepository;
    private final EnvelopeRepository envelopeRepository;
    private final BudgetRepository budgetRepository;
    private final MarkupCalculatorService markupCalculatorService;

    public TransactionPresentation(
            UserRepository userRepository,
            EnvelopeRepository envelopeRepository,
            BudgetRepository budgetRepository,
            MarkupCalculatorService markupCalculatorService) {
        this.userRepository = userRepository;
        this.envelopeRepository = envelopeRepository;
        this.budgetRepository = budgetRepository;
        this.markupCalculatorService = markupCalculatorService;
    }

    public Display describe(TransactionLog transaction, Names names) {
        String sourceName = envelopeName(transaction.getSourceEnvelopeId());
        String targetName = envelopeName(transaction.getTargetEnvelopeId());
        String budgetName = budgetName(transaction.getBudgetId());

        boolean reversed = isReversed(transaction.getStatus());
        boolean debit = !reversed && isOutgoing(transaction.getTransactionType(), transaction.getAmount());
        String direction = debit ? "OUT" : "IN";

        BigDecimal absoluteAmount = transaction.getAmount().abs();
        // txn.fee stores ONLY the Moniewise markup fee. The NIBSS NIP charge is
        // taken by Rubies and is not stored on the row. The screen adds it back
        // so the figure matches what the person was charged. The stored fee is
        // left untouched.
        BigDecimal fee = transaction.getFee() != null ? transaction.getFee() : BigDecimal.ZERO;
        if ((transaction.getTransactionType() == TransactionType.ENVELOPE_TO_EXTERNAL
                || transaction.getTransactionType() == TransactionType.WALLET_WITHDRAWAL)
                && RubiesGateway.PROVIDER_NAME.equalsIgnoreCase(providerName(transaction))) {
            fee = fee.add(markupCalculatorService.calculateNipFee(absoluteAmount));
        }
        BigDecimal stampDuty = transaction.getStampDuty() != null ? transaction.getStampDuty() : BigDecimal.ZERO;
        if (reversed) {
            fee = BigDecimal.ZERO;
            stampDuty = BigDecimal.ZERO;
        }
        BigDecimal netAmount = reversed
                ? absoluteAmount
                : (debit ? absoluteAmount.add(fee).add(stampDuty) : absoluteAmount.subtract(fee));

        TransactionType type = transaction.getTransactionType();
        String sender;
        String recipient;
        if (type == null) {
            sender = "MonieWise Account";
            recipient = "MonieWise Account";
        } else switch (type) {
            case BUDGET_ALLOCATION:
                sender = "Main Wallet";
                recipient = budgetName != null ? "Budget: " + budgetName : "Budget Envelopes";
                break;
            case BUDGET_CREATION_FEE:
            case WALLET_WITHDRAWAL_FEE:
            case WALLET_ENVELOPE_TRANSFER_FEE:
                sender = "Main Wallet";
                recipient = "Wisemonie Fee";
                break;
            case BUDGET_UNALLOCATED_REFUNDED:
                sender = "Unallocated Funds";
                recipient = "Main Wallet";
                break;
            case BUDGET_CREATION_FEE_REFUND:
                sender = "Wisemonie Fee";
                recipient = "Main Wallet";
                break;
            case WALLET_DEPOSIT:
                sender = "External Transfer / Card";
                recipient = "Main Wallet";
                break;
            case WALLET_WITHDRAWAL:
            case ENVELOPE_TO_EXTERNAL:
                sender = transaction.getTransactionType() == TransactionType.WALLET_WITHDRAWAL ? "Main Wallet" : sourceName;
                String extAccName = transaction.getExternalAccountName();
                String extAccId = transaction.getExternalAccountId();
                recipient = (extAccName != null && !extAccName.isBlank()) ? extAccName
                        : (extAccId != null && !extAccId.isBlank()) ? extAccId
                        : "External Account";
                break;
            case ENVELOPE_TO_ENVELOPE:
                sender = sourceName;
                recipient = targetName;
                break;
            case ENVELOPE_TO_USER:
                sender = sourceName;
                recipient = counterpartyName(transaction, names);
                break;
            case WALLET_TO_USER:
                sender = "Main Wallet";
                recipient = counterpartyName(transaction, names);
                break;
            case USER_TO_ENVELOPE:
                sender = counterpartyName(transaction, names);
                recipient = targetName;
                break;
            case VAS_PURCHASE:
                sender = sourceName;
                recipient = purchaseRecipient(transaction.getDescription());
                break;
            default:
                sender = "MonieWise Account";
                recipient = "MonieWise Account";
                break;
        }

        String title;
        if (type == null) {
            title = "Transaction";
        } else {
            title = switch (type) {
                case WALLET_DEPOSIT -> "Wallet funded";
                case ENVELOPE_TO_ENVELOPE -> "From %s to %s".formatted(sourceName, targetName);
                case ENVELOPE_TO_EXTERNAL -> "Sent to bank";
                case ENVELOPE_TO_USER, WALLET_TO_USER -> "Sent to " + counterpartyName(transaction, names);
                case USER_TO_ENVELOPE -> "Received from " + counterpartyName(transaction, names);
                case BUDGET_CREATION_FEE -> "Budget creation fee";
                case BUDGET_CREATION_FEE_REFUND -> "Creation fee refunded";
                case WALLET_WITHDRAWAL_FEE -> "Withdrawal fee";
                case WALLET_ENVELOPE_TRANSFER_FEE -> "Transfer fee";
                case BUDGET_ALLOCATION -> "Allocated to budget";
                case BUDGET_UNALLOCATED_REFUNDED -> "Refunded to wallet";
                case VAS_PURCHASE -> reversed ? "Purchase reversed" : "Airtime & Data";
                default -> formatEnumName(type);
            };
        }

        if (reversed) {
            if (type == null) {
                title = "Transaction reversed";
            } else switch (type) {
                case ENVELOPE_TO_EXTERNAL:
                    sender = recipient;
                    recipient = sourceName;
                    title = "Transfer reversed";
                    break;
                case WALLET_WITHDRAWAL:
                    sender = recipient;
                    recipient = "Main Wallet";
                    title = "Transfer reversed";
                    break;
                case WALLET_ENVELOPE_TRANSFER_FEE:
                case WALLET_WITHDRAWAL_FEE:
                    sender = "Wisemonie Fee";
                    recipient = "Main Wallet";
                    title = "Fee reversed";
                    break;
                case VAS_PURCHASE:
                    sender = "Provider";
                    recipient = sourceName;
                    title = "Purchase reversed";
                    break;
                default:
                    title = "Transaction reversed";
                    break;
            }
        }

        boolean external = transaction.getTransactionType() == TransactionType.ENVELOPE_TO_EXTERNAL
                || transaction.getTransactionType() == TransactionType.WALLET_WITHDRAWAL;
        String effectiveTargetName = (external
                && transaction.getExternalBankName() != null
                && !transaction.getExternalBankName().isBlank())
                ? transaction.getExternalBankName()
                : targetName;

        String resolvedAccountNumber = (transaction.getExternalAccountNumber() != null
                && !transaction.getExternalAccountNumber().isBlank())
                ? transaction.getExternalAccountNumber()
                : transaction.getExternalAccountId();

        String reference = transaction.getReference() != null ? transaction.getReference() : "N/A";
        String status = transaction.getStatus() != null ? transaction.getStatus().name() : "COMPLETED";

        return new Display(
                sender,
                recipient,
                title,
                heading(transaction.getTransactionType()),
                direction,
                absoluteAmount,
                fee,
                stampDuty,
                netAmount,
                reference,
                status,
                sourceName,
                effectiveTargetName,
                budgetName,
                transaction.getExternalBankName(),
                resolvedAccountNumber);
    }

    /**
     * The line under the wordmark. A funding, a fee, and a purchase are not
     * labeled as a transfer.
     */
    public static String heading(TransactionType type) {
        if (type == null) {
            return "Transaction receipt";
        }
        return switch (type) {
            case WALLET_DEPOSIT, RECONCILIATION_CREDIT, SAVINGS_DEPOSIT,
                    BUDGET_UNALLOCATED_REFUNDED, BUDGET_CREATION_FEE_REFUND,
                    DISBURSEMENT_REFUNDED, ROLLOVER_REFUND, BUDGET_COMPLETION_REFUND ->
                    "Funding receipt";
            case VAS_PURCHASE -> "Purchase receipt";
            case BUDGET_CREATION_FEE, WALLET_WITHDRAWAL_FEE, WALLET_ENVELOPE_TRANSFER_FEE,
                    WALLET_DEPOSIT_FEE, ENVELOPE_EXTERNAL_TRANSFER_FEE ->
                    "Fee receipt";
            case BUDGET_ALLOCATION, BUDGET_TOP_UP, WALLET_TO_BUDGET, BUDGET_EXTENSION ->
                    "Allocation receipt";
            case ENVELOPE_TO_EXTERNAL, WALLET_WITHDRAWAL, WALLET_TO_EXTERNAL,
                    ENVELOPE_TO_USER, WALLET_TO_USER, USER_TO_ENVELOPE, USER_TO_USER,
                    ENVELOPE_TO_ENVELOPE, SAVINGS_WITHDRAWAL, ENVELOPE_DISBURSEMENT ->
                    "Transfer receipt";
            default -> "Transaction receipt";
        };
    }

    public String counterpartyName(TransactionLog transaction, Names names) {
        if (transaction.getCounterpartyUserId() != null) {
            return userRepository.findById(transaction.getCounterpartyUserId())
                    .map(user -> profileName(user, names))
                    .orElse("Wisemonie User");
        }

        if (transaction.getDescription() != null) {
            if (transaction.getDescription().contains("Transfer to ")) {
                return transaction.getDescription().replace("Transfer to ", "").trim();
            }
            if (transaction.getDescription().contains("Received from ")) {
                return transaction.getDescription().replace("Received from ", "").trim();
            }
        }

        return "Wisemonie User";
    }

    public static boolean isOutgoing(TransactionType type, BigDecimal amount) {
        if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
            return true;
        }
        if (type == null) {
            return false;
        }
        return switch (type) {
            case ENVELOPE_DISBURSEMENT,
                    ENVELOPE_TO_EXTERNAL,
                    ENVELOPE_TO_ENVELOPE,
                    ENVELOPE_TO_USER,
                    WALLET_DEDUCTION,
                    WALLET_WITHDRAWAL_FEE,
                    WALLET_ENVELOPE_TRANSFER_FEE,
                    BUDGET_CREATION_FEE,
                    BUDGET_ALLOCATION,
                    WALLET_WITHDRAWAL,
                    VAS_PURCHASE -> true;
            default -> false;
        };
    }

    public static boolean isReversed(TransactionStatus status) {
        return status == TransactionStatus.REVERSED || status == TransactionStatus.FAILED;
    }

    public static String formatEnumName(TransactionType type) {
        if (type == null) {
            return "Transaction";
        }
        return type.name().charAt(0) + type.name().substring(1).toLowerCase().replace('_', ' ');
    }

    /**
     * Airtime and data keep the network and a masked phone. The full number
     * stays off the shared slip.
     */
    static String purchaseRecipient(String description) {
        if (description == null || description.isBlank()) {
            return "Airtime & Data";
        }
        Matcher matcher = PHONE.matcher(description);
        if (!matcher.find()) {
            return "Airtime & Data";
        }
        String digits = matcher.group();
        String masked = digits.substring(0, 4) + "...." + digits.substring(digits.length() - 4);
        String first = description.trim().split(" ")[0];
        if (!first.isEmpty() && first.chars().allMatch(Character::isLetter)) {
            return first + " " + masked;
        }
        return masked;
    }

    private String profileName(User user, Names names) {
        if (user.getProfileData() != null && user.getProfileData().containsKey("name")) {
            Object rawName = user.getProfileData().get("name");
            if (rawName instanceof String name && !name.isBlank()) {
                return name;
            }
        }
        if (names == Names.SCREEN && user.getEmail() != null) {
            return user.getEmail().split("@")[0];
        }
        return "Wisemonie User";
    }

    private String providerName(TransactionLog transaction) {
        String direct = transaction.getProviderName();
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        return userRepository.findById(transaction.getUserId())
                .map(User::getWallet)
                .map(Wallet::getProviderName)
                .orElse(null);
    }

    private String envelopeName(Long envelopeId) {
        if (envelopeId == null) {
            return "System";
        }
        return envelopeRepository.findById(envelopeId)
                .map(Envelope::getName)
                .orElse("Unknown Envelope");
    }

    private String budgetName(Long budgetId) {
        if (budgetId == null) {
            return "No Budget";
        }
        return budgetRepository.findById(budgetId)
                .map(Budget::getName)
                .orElse("Unknown Budget");
    }

    public record Display(
            String sender,
            String recipient,
            String title,
            String heading,
            String direction,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal stampDuty,
            BigDecimal netAmount,
            String reference,
            String status,
            String sourceName,
            String effectiveTargetName,
            String budgetName,
            String bankName,
            String accountNumber) {
    }
}
