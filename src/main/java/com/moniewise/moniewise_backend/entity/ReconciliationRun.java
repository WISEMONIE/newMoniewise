package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    public static final String RUN_TYPE_FULL = "FULL";
    public static final String RUN_TYPE_INCREMENTAL = "INCREMENTAL";
    public static final String RUN_TYPE_REVENUE_STATEMENT = "REVENUE_STATEMENT";

    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String CREATED_BY_SYSTEM = "SYSTEM";
    public static final String CREATED_BY_ADMIN = "ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_name", nullable = false, length = 100)
    private String providerName;

    @Column(name = "run_type", nullable = false, length = 30)
    private String runType;

    @Column(name = "status", nullable = false, length = 30)
    private String status = STATUS_STARTED;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_by", nullable = false, length = 30)
    private String createdBy = CREATED_BY_SYSTEM;

    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    @OneToMany(mappedBy = "reconciliationRun", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReconciliationItem> items = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
        if (status == null || status.isBlank()) {
            status = STATUS_STARTED;
        }
        if (createdBy == null || createdBy.isBlank()) {
            createdBy = CREATED_BY_SYSTEM;
        }
    }
}
