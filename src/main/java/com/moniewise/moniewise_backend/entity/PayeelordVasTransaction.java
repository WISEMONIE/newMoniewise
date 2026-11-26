package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.VasTransactionStatus;
import com.moniewise.moniewise_backend.enums.VasTransactionType;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ledger / audit record for a single airtime or data purchase via Payeelord.
 *
 * <p><strong>Why this exists separately from {@code TransactionLog}:</strong> VAS
 * purchases need Payeelord-specific fields (network, recipient number, the
 * cost/selling/margin split, the provider's own transaction id, raw response for
 * support) that don't fit the generic ledger shape. This table is the
 * single source of truth for reconciliation against {@code GET /api/data-transactions}
 * and for matching up the secondary webhook confirmation.
 *
 * <h3>Pricing fields — how they relate</h3>
 * <pre>
 *   faceAmount     = the airtime/data value actually delivered to the recipient
 *   costAmount     = what Payeelord charged YOUR float
 *   sellingAmount  = what the USER's wallet was debited
 *   marginAmount   = sellingAmount − costAmount   (your profit on this transaction)
 * </pre>
 *
 * <h3>Status flow</h3>
 * <pre>
 *   PENDING  → SUCCESSFUL                         (purchase confirmed by Payeelord)
 *   PENDING  → FAILED → REVERSED                  (purchase failed, debit reversed instantly — synchronous!)
 *   PENDING  → MANUAL_REVIEW                      (provider may have delivered; ops must reconcile)
 * </pre>
 */
@Entity
@Table(name = "payeelord_vas_transactions", indexes = {
        @Index(name = "idx_payeelord_txn_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_payeelord_txn_status", columnList = "status"),
        @Index(name = "idx_payeelord_txn_reference", columnList = "reference"),
        @Index(name = "idx_payeelord_txn_provider_id", columnList = "payeelord_transaction_id")
})
public class PayeelordVasTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    /** Budget envelope the purchase was funded from (the money left this envelope). */
    @Column(name = "envelope_id")
    private Long envelopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VasTransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VasTransactionStatus status;

    /** Our internal idempotency/tracking reference — generated before calling Payeelord. */
    @Column(nullable = false, unique = true, length = 60)
    private String reference;

    /** Payeelord's own {@code transaction_id} — captured from the sync response or webhook. */
    @Column(name = "payeelord_transaction_id", length = 80)
    private String payeelordTransactionId;

    /** MTN | GLO | AIRTEL | 9MOBILE */
    @Column(nullable = false, length = 20)
    private String network;

    @Column(name = "mobile_number", nullable = false, length = 20)
    private String mobileNumber;

    /** FK to the catalog row purchased — null for airtime (which has no catalog). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "data_plan_id")
    private PayeelordDataPlan dataPlan;

    @Column(name = "face_amount", nullable = false)
    private BigDecimal faceAmount;

    @Column(name = "cost_amount", nullable = false)
    private BigDecimal costAmount;

    @Column(name = "selling_amount", nullable = false)
    private BigDecimal sellingAmount;

    @Column(name = "margin_amount", nullable = false)
    private BigDecimal marginAmount = BigDecimal.ZERO;

    /** Payeelord float balance before this purchase — from the sync response, audit only. */
    @Column(name = "balance_before")
    private BigDecimal balanceBefore;

    /** Payeelord float balance after this purchase — from the sync response, audit only. */
    @Column(name = "balance_after")
    private BigDecimal balanceAfter;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    /** Full raw JSON response from Payeelord — kept for support/debugging/reconciliation. */
    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    /** Set when the secondary webhook lands and confirms this transaction (audit trail only). */
    @Column(name = "webhook_confirmed_at")
    private LocalDateTime webhookConfirmedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Long getId()                                    { return id; }
    public void setId(Long id)                             { this.id = id; }

    public Long getUserId()                                { return userId; }
    public void setUserId(Long userId)                     { this.userId = userId; }

    public Long getWalletId()                              { return walletId; }
    public void setWalletId(Long walletId)                 { this.walletId = walletId; }

    public Long getEnvelopeId()                            { return envelopeId; }
    public void setEnvelopeId(Long envelopeId)             { this.envelopeId = envelopeId; }

    public VasTransactionType getType()                    { return type; }
    public void setType(VasTransactionType type)           { this.type = type; }

    public VasTransactionStatus getStatus()                { return status; }
    public void setStatus(VasTransactionStatus status)     { this.status = status; }

    public String getReference()                           { return reference; }
    public void setReference(String reference)             { this.reference = reference; }

    public String getPayeelordTransactionId()              { return payeelordTransactionId; }
    public void setPayeelordTransactionId(String v)        { this.payeelordTransactionId = v; }

    public String getNetwork()                             { return network; }
    public void setNetwork(String network)                 { this.network = network; }

    public String getMobileNumber()                        { return mobileNumber; }
    public void setMobileNumber(String mobileNumber)       { this.mobileNumber = mobileNumber; }

    public PayeelordDataPlan getDataPlan()                 { return dataPlan; }
    public void setDataPlan(PayeelordDataPlan dataPlan)    { this.dataPlan = dataPlan; }

    public BigDecimal getFaceAmount()                      { return faceAmount; }
    public void setFaceAmount(BigDecimal faceAmount)       { this.faceAmount = faceAmount; }

    public BigDecimal getCostAmount()                      { return costAmount; }
    public void setCostAmount(BigDecimal costAmount)       { this.costAmount = costAmount; }

    public BigDecimal getSellingAmount()                   { return sellingAmount; }
    public void setSellingAmount(BigDecimal sellingAmount) { this.sellingAmount = sellingAmount; }

    public BigDecimal getMarginAmount()                    { return marginAmount; }
    public void setMarginAmount(BigDecimal marginAmount)   { this.marginAmount = marginAmount; }

    public BigDecimal getBalanceBefore()                   { return balanceBefore; }
    public void setBalanceBefore(BigDecimal balanceBefore) { this.balanceBefore = balanceBefore; }

    public BigDecimal getBalanceAfter()                    { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter)   { this.balanceAfter = balanceAfter; }

    public String getFailureReason()                       { return failureReason; }
    public void setFailureReason(String failureReason)     { this.failureReason = failureReason; }

    public String getRawResponse()                         { return rawResponse; }
    public void setRawResponse(String rawResponse)         { this.rawResponse = rawResponse; }

    public LocalDateTime getWebhookConfirmedAt()           { return webhookConfirmedAt; }
    public void setWebhookConfirmedAt(LocalDateTime v)     { this.webhookConfirmedAt = v; }

    public LocalDateTime getCreatedAt()                    { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)      { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt()                    { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt)      { this.updatedAt = updatedAt; }
}
