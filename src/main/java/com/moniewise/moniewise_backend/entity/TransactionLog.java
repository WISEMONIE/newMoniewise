//package com.moniewise.moniewise_backend.entity;
//
//import com.moniewise.moniewise_backend.config.TransactionTypeConverter;
//import com.moniewise.moniewise_backend.enums.TransactionStatus;
//import com.moniewise.moniewise_backend.enums.TransactionType;
//
//import javax.persistence.*;
//import java.math.BigDecimal;
//import java.time.LocalDateTime;
//
//@Entity
//@Table(name = "transaction_logs", indexes = {
//        // Covers every user-facing transaction list/page query (findByUserIdOrderByCreatedAtDesc,
//        // findUserVisibleTransactions, etc.). Without this Postgres scans the entire table
//        // and sorts in-memory for every request — the root cause of slow transaction screens.
//        @Index(name = "idx_txlog_user_created", columnList = "user_id, created_at"),
//        // Covers envelope-level spend queries (calculateTotalSpent, findBySourceEnvelopeIdAndTimeRange).
//        @Index(name = "idx_txlog_envelope_date", columnList = "source_envelope_id, created_at"),
//        // Covers budget-level transaction lookups (findByBudgetIdOrderByCreatedAtDesc).
//        @Index(name = "idx_txlog_budget_created", columnList = "budget_id, created_at")
//})
//public class TransactionLog {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @Column(name = "user_id", nullable = false)
//    private Long userId;
//
//    @Column(name = "budget_id")
//    private Long budgetId;
//
//    @Column(name = "source_envelope_id")
//    private Long sourceEnvelopeId; // Null for deposits
//
//    @Column(name = "target_envelope_id")
//    private Long targetEnvelopeId; // Null for external transfers
//
//    @Column(name = "external_account_id")
//    private String externalAccountId; // Stores accountNumber for MVP
//
//    @Column(nullable = false)
//    private BigDecimal amount;
//
//    @Column(name = "fee")
//    private BigDecimal fee;
//
////    @Column(name = "transaction_type", nullable = false)
////    @Enumerated(EnumType.STRING)
////    private TransactionType transactionType;
//
//    @Column(name = "transaction_type", nullable = false)
//    @Convert(converter = TransactionTypeConverter.class)
//    private TransactionType transactionType;
//
////    @Column(name = "transaction_type", nullable = false)
////    private String transactionType; // e.g., "envelope_to_envelope", "envelope_to_external", "failed_external_transfer"
//
//    @Column(name = "description")
//    private String description; // For failure reasons
//
//    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
//    private LocalDateTime createdAt;
//
//    @Column(nullable = false, unique = true)
//    private String reference;
//
//    @Column(name = "provider_name", length = 50)
//    private String providerName;
//
//    @Column(name = "provider_reference")
//    private String providerReference;
//
//    @Column(nullable = false)
//    @Enumerated(EnumType.STRING)
//    private TransactionStatus status;
//
//    @Column(name = "counterparty_user_id")
//    private Long counterpartyUserId; // Nullable (Only for P2P)
//
//    @Column(name = "external_bank_name")
//    private String externalBankName;
//
//    @Column(name = "external_account_number")
//    private String externalAccountNumber;
//
//    @Column(name = "external_account_name")
//    private String externalAccountName;
//
//    // Constructors
//    public TransactionLog() {}
//
//    public TransactionLog(Long userId, Long budgetId, Long sourceEnvelopeId, Long targetEnvelopeId,
//                         BigDecimal amount,  TransactionType transactionType, String description) {
//        this.userId = userId;
//        this.budgetId = budgetId;
//        this.sourceEnvelopeId = sourceEnvelopeId;
//        this.targetEnvelopeId = targetEnvelopeId;
//        this.amount = amount;
//        this.transactionType = transactionType;
//        this.description = description;
//        // createdAt set via setCreatedAt() in service
//    }
//
//    public TransactionLog(Long userId, Long budgetId, Long sourceEnvelopeId, Long targetEnvelopeId,
//                          String externalAccountId, BigDecimal amount, BigDecimal fee, TransactionType transactionType, String description) {
//        this.userId = userId;
//        this.budgetId = budgetId;
//        this.sourceEnvelopeId = sourceEnvelopeId;
//        this.targetEnvelopeId = targetEnvelopeId;
//        this.externalAccountId = externalAccountId;
//        this.amount = amount;
//        this.fee = fee;
//        this.transactionType = transactionType;
//        this.description = description;
//        // createdAt set via setCreatedAt() in service
//    }
//
//    // Getters and Setters
//    public Long getId() { return id; }
//    public void setId(Long id) { this.id = id; }
//    public Long getUserId() { return userId; }
//    public void setUserId(Long userId) { this.userId = userId; }
//    public Long getBudgetId() { return budgetId; }
//    public void setBudgetId(Long budgetId) { this.budgetId = budgetId; }
//    public Long getSourceEnvelopeId() { return sourceEnvelopeId; }
//    public void setSourceEnvelopeId(Long sourceEnvelopeId) { this.sourceEnvelopeId = sourceEnvelopeId; }
//    public Long getTargetEnvelopeId() { return targetEnvelopeId; }
//    public void setTargetEnvelopeId(Long targetEnvelopeId) { this.targetEnvelopeId = targetEnvelopeId; }
//    public String getExternalAccountId() { return externalAccountId; }
//    public void setExternalAccountId(String externalAccountId) { this.externalAccountId = externalAccountId; }
//    public BigDecimal getAmount() { return amount; }
//    public void setAmount(BigDecimal amount) { this.amount = amount; }
//    public BigDecimal getFee() { return fee; }
//    public void setFee(BigDecimal fee) { this.fee = fee; }
//    public TransactionType getTransactionType() { return transactionType; }
//    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }
//    public String getDescription() { return description; }
//    public void setDescription(String description) { this.description = description; }
//    public LocalDateTime getCreatedAt() { return createdAt; }
//    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
//
//    public String getReference() { return reference; }
//    public void setReference(String reference) { this.reference = reference; }
//
//    public String getProviderName() { return providerName; }
//    public void setProviderName(String providerName) { this.providerName = providerName; }
//
//    public String getProviderReference() { return providerReference; }
//    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
//
//    public TransactionStatus getStatus() { return status; }
//    public void setStatus(TransactionStatus status) { this.status = status; }
//
//    public Long getCounterpartyUserId() { return counterpartyUserId; }
//    public void setCounterpartyUserId(Long counterpartyUserId) { this.counterpartyUserId = counterpartyUserId; }
//
//
//// ==================================================================
//    // MANUAL BUILDER PATTERN (Paste this inside TransactionLog class)
//    // ==================================================================
//
//    public static TransactionLogBuilder builder() {
//        return new TransactionLogBuilder();
//    }
//
//    public static class TransactionLogBuilder {
//        private Long userId;
//        private Long budgetId;
//        private Long sourceEnvelopeId;
//        private Long targetEnvelopeId;
//        private String externalAccountId;
//        private Long counterpartyUserId;
//        private BigDecimal amount;
//        private BigDecimal fee;
//        private String reference;
//        private String providerName;
//        private String providerReference;
//        private TransactionStatus status;
//        private TransactionType transactionType;
//        private String description;
//        private LocalDateTime createdAt;
//
//        @Column(name = "external_bank_name")
//        private String externalBankName;
//
//        @Column(name = "external_account_number")
//        private String externalAccountNumber;
//
//        @Column(name = "external_account_name")
//        private String externalAccountName;
//
//        TransactionLogBuilder() { }
//
//        public TransactionLogBuilder userId(Long userId) {
//            this.userId = userId;
//            return this;
//        }
//
//        public TransactionLogBuilder budgetId(Long budgetId) {
//            this.budgetId = budgetId;
//            return this;
//        }
//
//        public TransactionLogBuilder sourceEnvelopeId(Long sourceEnvelopeId) {
//            this.sourceEnvelopeId = sourceEnvelopeId;
//            return this;
//        }
//
//        public TransactionLogBuilder targetEnvelopeId(Long targetEnvelopeId) {
//            this.targetEnvelopeId = targetEnvelopeId;
//            return this;
//        }
//
//        public TransactionLogBuilder externalAccountId(String externalAccountId) {
//            this.externalAccountId = externalAccountId;
//            return this;
//        }
//
//        public TransactionLogBuilder counterpartyUserId(Long counterpartyUserId) {
//            this.counterpartyUserId = counterpartyUserId;
//            return this;
//        }
//
//        public TransactionLogBuilder amount(BigDecimal amount) {
//            this.amount = amount;
//            return this;
//        }
//
//        public TransactionLogBuilder fee(BigDecimal fee) {
//            this.fee = fee;
//            return this;
//        }
//
//        public TransactionLogBuilder reference(String reference) {
//            this.reference = reference;
//            return this;
//        }
//
//        public TransactionLogBuilder providerName(String providerName) {
//            this.providerName = providerName;
//            return this;
//        }
//
//        public TransactionLogBuilder providerReference(String providerReference) {
//            this.providerReference = providerReference;
//            return this;
//        }
//
//        public TransactionLogBuilder status(TransactionStatus status) {
//            this.status = status;
//            return this;
//        }
//
//        public TransactionLogBuilder transactionType(TransactionType transactionType) {
//            this.transactionType = transactionType;
//            return this;
//        }
//
//        public TransactionLogBuilder description(String description) {
//            this.description = description;
//            return this;
//        }
//
//        public TransactionLogBuilder createdAt(LocalDateTime createdAt) {
//            this.createdAt = createdAt;
//            return this;
//        }
//
//        public TransactionLog build() {
//            // Use the constructor with all arguments (Lombok @AllArgsConstructor generates this,
//            // or you can ensure your manual constructor matches this)
//            TransactionLog log = new TransactionLog();
//            log.setUserId(this.userId);
//            log.setBudgetId(this.budgetId);
//            log.setSourceEnvelopeId(this.sourceEnvelopeId);
//            log.setTargetEnvelopeId(this.targetEnvelopeId);
//            log.setExternalAccountId(this.externalAccountId);
//            log.setCounterpartyUserId(this.counterpartyUserId);
//            log.setAmount(this.amount);
//            log.setFee(this.fee);
//            log.setReference(this.reference);
//            log.setProviderName(this.providerName);
//            log.setProviderReference(this.providerReference);
//            log.setStatus(this.status);
//            log.setTransactionType(this.transactionType);
//            log.setDescription(this.description);
//            log.setCreatedAt(this.createdAt);
//            return log;
//        }
//    }
//
//}
//
//
//

package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.config.TransactionTypeConverter;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transaction_logs", indexes = {
        @Index(name = "idx_txlog_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_txlog_envelope_date", columnList = "source_envelope_id, created_at"),
        @Index(name = "idx_txlog_budget_created", columnList = "budget_id, created_at")
})
public class TransactionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "budget_id")
    private Long budgetId;

    @Column(name = "source_envelope_id")
    private Long sourceEnvelopeId;

    @Column(name = "target_envelope_id")
    private Long targetEnvelopeId;

    /**
     * This column is still kept for backward compatibility.
     * Do NOT store bank account numbers here if the DB column has a FK to wallets.
     * For external bank transfers, keep this null and use:
     * externalBankName, externalAccountNumber, externalAccountName.
     */
    @Column(name = "external_account_id")
    private String externalAccountId;

    @Column(name = "external_bank_name")
    private String externalBankName;

    @Column(name = "external_bank_code", length = 20)
    private String externalBankCode;

    @Column(name = "external_account_number")
    private String externalAccountNumber;

    @Column(name = "external_account_name")
    private String externalAccountName;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "fee")
    private BigDecimal fee;

    @Column(name = "transaction_type", nullable = false)
    @Convert(converter = TransactionTypeConverter.class)
    private TransactionType transactionType;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime createdAt;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "provider_name", length = 50)
    private String providerName;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    @Column(name = "counterparty_user_id")
    private Long counterpartyUserId;

    @Column(name = "stamp_duty")
    private BigDecimal stampDuty;

    public TransactionLog() {
    }

    public TransactionLog(
            Long userId,
            Long budgetId,
            Long sourceEnvelopeId,
            Long targetEnvelopeId,
            BigDecimal amount,
            TransactionType transactionType,
            String description
    ) {
        this.userId = userId;
        this.budgetId = budgetId;
        this.sourceEnvelopeId = sourceEnvelopeId;
        this.targetEnvelopeId = targetEnvelopeId;
        this.amount = amount;
        this.transactionType = transactionType;
        this.description = description;
    }

    public TransactionLog(
            Long userId,
            Long budgetId,
            Long sourceEnvelopeId,
            Long targetEnvelopeId,
            String externalAccountId,
            BigDecimal amount,
            BigDecimal fee,
            TransactionType transactionType,
            String description
    ) {
        this.userId = userId;
        this.budgetId = budgetId;
        this.sourceEnvelopeId = sourceEnvelopeId;
        this.targetEnvelopeId = targetEnvelopeId;
        this.externalAccountId = externalAccountId;
        this.amount = amount;
        this.fee = fee;
        this.transactionType = transactionType;
        this.description = description;
    }

    public static TransactionLogBuilder builder() {
        return new TransactionLogBuilder();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getBudgetId() {
        return budgetId;
    }

    public void setBudgetId(Long budgetId) {
        this.budgetId = budgetId;
    }

    public Long getSourceEnvelopeId() {
        return sourceEnvelopeId;
    }

    public void setSourceEnvelopeId(Long sourceEnvelopeId) {
        this.sourceEnvelopeId = sourceEnvelopeId;
    }

    public Long getTargetEnvelopeId() {
        return targetEnvelopeId;
    }

    public void setTargetEnvelopeId(Long targetEnvelopeId) {
        this.targetEnvelopeId = targetEnvelopeId;
    }

    public String getExternalAccountId() {
        return externalAccountId;
    }

    public void setExternalAccountId(String externalAccountId) {
        this.externalAccountId = externalAccountId;
    }

    public String getExternalBankName() {
        return externalBankName;
    }

    public void setExternalBankName(String externalBankName) {
        this.externalBankName = externalBankName;
    }

    public String getExternalBankCode() {
        return externalBankCode;
    }

    public void setExternalBankCode(String externalBankCode) {
        this.externalBankCode = externalBankCode;
    }

    public String getExternalAccountNumber() {
        return externalAccountNumber;
    }

    public void setExternalAccountNumber(String externalAccountNumber) {
        this.externalAccountNumber = externalAccountNumber;
    }

    public String getExternalAccountName() {
        return externalAccountName;
    }

    public void setExternalAccountName(String externalAccountName) {
        this.externalAccountName = externalAccountName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public Long getCounterpartyUserId() {
        return counterpartyUserId;
    }

    public void setCounterpartyUserId(Long counterpartyUserId) {
        this.counterpartyUserId = counterpartyUserId;
    }

    public BigDecimal getStampDuty() {
        return stampDuty;
    }

    public void setStampDuty(BigDecimal stampDuty) {
        this.stampDuty = stampDuty;
    }

    public static class TransactionLogBuilder {

        private Long userId;
        private Long budgetId;
        private Long sourceEnvelopeId;
        private Long targetEnvelopeId;
        private String externalAccountId;
        private String externalBankName;
        private String externalBankCode;
        private String externalAccountNumber;
        private String externalAccountName;
        private Long counterpartyUserId;
        private BigDecimal amount;
        private BigDecimal fee;
        private String reference;
        private String providerName;
        private String providerReference;
        private TransactionStatus status;
        private TransactionType transactionType;
        private String description;
        private LocalDateTime createdAt;
        private BigDecimal stampDuty;

        TransactionLogBuilder() {
        }

        public TransactionLogBuilder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public TransactionLogBuilder budgetId(Long budgetId) {
            this.budgetId = budgetId;
            return this;
        }

        public TransactionLogBuilder sourceEnvelopeId(Long sourceEnvelopeId) {
            this.sourceEnvelopeId = sourceEnvelopeId;
            return this;
        }

        public TransactionLogBuilder targetEnvelopeId(Long targetEnvelopeId) {
            this.targetEnvelopeId = targetEnvelopeId;
            return this;
        }

        public TransactionLogBuilder externalAccountId(String externalAccountId) {
            this.externalAccountId = externalAccountId;
            return this;
        }

        public TransactionLogBuilder externalBankName(String externalBankName) {
            this.externalBankName = externalBankName;
            return this;
        }

        public TransactionLogBuilder externalBankCode(String externalBankCode) {
            this.externalBankCode = externalBankCode;
            return this;
        }

        public TransactionLogBuilder externalAccountNumber(String externalAccountNumber) {
            this.externalAccountNumber = externalAccountNumber;
            return this;
        }

        public TransactionLogBuilder externalAccountName(String externalAccountName) {
            this.externalAccountName = externalAccountName;
            return this;
        }

        public TransactionLogBuilder counterpartyUserId(Long counterpartyUserId) {
            this.counterpartyUserId = counterpartyUserId;
            return this;
        }

        public TransactionLogBuilder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public TransactionLogBuilder fee(BigDecimal fee) {
            this.fee = fee;
            return this;
        }

        public TransactionLogBuilder reference(String reference) {
            this.reference = reference;
            return this;
        }

        public TransactionLogBuilder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        public TransactionLogBuilder providerReference(String providerReference) {
            this.providerReference = providerReference;
            return this;
        }

        public TransactionLogBuilder status(TransactionStatus status) {
            this.status = status;
            return this;
        }

        public TransactionLogBuilder transactionType(TransactionType transactionType) {
            this.transactionType = transactionType;
            return this;
        }

        public TransactionLogBuilder description(String description) {
            this.description = description;
            return this;
        }

        public TransactionLogBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public TransactionLogBuilder stampDuty(BigDecimal stampDuty) {
            this.stampDuty = stampDuty;
            return this;
        }

        public TransactionLog build() {
            TransactionLog log = new TransactionLog();

            log.setUserId(this.userId);
            log.setBudgetId(this.budgetId);
            log.setSourceEnvelopeId(this.sourceEnvelopeId);
            log.setTargetEnvelopeId(this.targetEnvelopeId);
            log.setExternalAccountId(this.externalAccountId);
            log.setExternalBankName(this.externalBankName);
            log.setExternalBankCode(this.externalBankCode);
            log.setExternalAccountNumber(this.externalAccountNumber);
            log.setExternalAccountName(this.externalAccountName);
            log.setCounterpartyUserId(this.counterpartyUserId);
            log.setAmount(this.amount);
            log.setFee(this.fee);
            log.setReference(this.reference);
            log.setProviderName(this.providerName);
            log.setProviderReference(this.providerReference);
            log.setStatus(this.status);
            log.setTransactionType(this.transactionType);
            log.setDescription(this.description);
            log.setCreatedAt(this.createdAt);
            log.setStampDuty(this.stampDuty);

            return log;
        }
    }
}