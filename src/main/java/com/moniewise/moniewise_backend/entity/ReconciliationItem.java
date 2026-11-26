package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "reconciliation_items")
public class ReconciliationItem {

    public static final String REFERENCE_TYPE_WALLET = "WALLET";
    public static final String REFERENCE_TYPE_WITHDRAWAL = "WITHDRAWAL";
    public static final String REFERENCE_TYPE_TRANSACTION = "TRANSACTION";
    public static final String REFERENCE_TYPE_HOLDINGS = "HOLDINGS";

    public static final String MISMATCH_BALANCE = "BALANCE_MISMATCH";
    public static final String MISMATCH_STATUS = "STATUS_MISMATCH";
    public static final String MISMATCH_MISSING_PROVIDER = "MISSING_PROVIDER_RECORD";
    public static final String MISMATCH_MISSING_INTERNAL = "MISSING_INTERNAL_RECORD";
    public static final String MISMATCH_HOLDINGS = "HOLDINGS_BALANCE_MISMATCH";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_IGNORED = "IGNORED";
    public static final String STATUS_AUTO_RESOLVED = "AUTO_RESOLVED";
    public static final String STATUS_MANUAL_REVIEW = "MANUAL_REVIEW_REQUIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reconciliation_run_id", nullable = false)
    private ReconciliationRun reconciliationRun;

    @Column(name = "reference_type", nullable = false, length = 30)
    private String referenceType;

    @Column(name = "internal_reference", length = 150)
    private String internalReference;

    @Column(name = "provider_reference", length = 150)
    private String providerReference;

    @Column(name = "mismatch_type", nullable = false, length = 50)
    private String mismatchType;

    @Column(name = "internal_value", columnDefinition = "TEXT")
    private String internalValue;

    @Column(name = "provider_value", columnDefinition = "TEXT")
    private String providerValue;

    @Column(name = "status", nullable = false, length = 40)
    private String status = STATUS_OPEN;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @PrePersist
    public void prePersist() {
        if (detectedAt == null) {
            detectedAt = LocalDateTime.now();
        }
        if (status == null || status.isBlank()) {
            status = STATUS_OPEN;
        }
    }
}
