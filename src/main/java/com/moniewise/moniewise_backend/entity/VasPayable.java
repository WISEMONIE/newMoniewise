package com.moniewise.moniewise_backend.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One airtime or data purchase whose selling amount has arrived in the Rubies
 * revenue account. {@code costAmount} is what PayeeLord already took from the
 * float and is still sitting in that account. {@code marginAmount} is ours.
 * {@code marginCredited} is true only after that margin has been added to the
 * revenue wallet balance. {@code swept} is true after the cost has been sent
 * back to PayeeLord.
 */
@Entity
@Table(name = "vas_payables")
public class VasPayable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_reference", nullable = false, unique = true, length = 80)
    private String purchaseReference;

    @Column(name = "collection_reference", nullable = false, length = 120)
    private String collectionReference;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "selling_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal sellingAmount;

    @Column(name = "cost_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "margin_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal marginAmount = BigDecimal.ZERO;

    @Column(name = "margin_credited", nullable = false)
    private boolean marginCredited;

    @Column(name = "swept", nullable = false)
    private boolean swept;

    @Column(name = "swept_at")
    private LocalDateTime sweptAt;

    @Column(name = "sweep_reference", length = 120)
    private String sweepReference;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPurchaseReference() { return purchaseReference; }
    public void setPurchaseReference(String purchaseReference) { this.purchaseReference = purchaseReference; }

    public String getCollectionReference() { return collectionReference; }
    public void setCollectionReference(String collectionReference) { this.collectionReference = collectionReference; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public BigDecimal getSellingAmount() { return sellingAmount; }
    public void setSellingAmount(BigDecimal sellingAmount) { this.sellingAmount = sellingAmount; }

    public BigDecimal getCostAmount() { return costAmount; }
    public void setCostAmount(BigDecimal costAmount) { this.costAmount = costAmount; }

    public BigDecimal getMarginAmount() { return marginAmount; }
    public void setMarginAmount(BigDecimal marginAmount) { this.marginAmount = marginAmount; }

    public boolean isMarginCredited() { return marginCredited; }
    public void setMarginCredited(boolean marginCredited) { this.marginCredited = marginCredited; }

    public boolean isSwept() { return swept; }
    public void setSwept(boolean swept) { this.swept = swept; }

    public LocalDateTime getSweptAt() { return sweptAt; }
    public void setSweptAt(LocalDateTime sweptAt) { this.sweptAt = sweptAt; }

    public String getSweepReference() { return sweepReference; }
    public void setSweepReference(String sweepReference) { this.sweepReference = sweepReference; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
