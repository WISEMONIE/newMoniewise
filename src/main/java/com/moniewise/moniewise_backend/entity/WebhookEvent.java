package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
    name = "webhook_events",
    uniqueConstraints = {
        // DB-level guard: prevents double-credit from concurrent duplicate webhooks
        @UniqueConstraint(
            name  = "uq_webhook_events_provider_idempotency",
            columnNames = {"provider_name", "idempotency_key"}
        )
    },
    indexes = {
        @Index(name = "idx_webhook_events_external_reference", columnList = "external_reference"),
        @Index(name = "idx_webhook_events_processing_status",  columnList = "processing_status"),
        @Index(name = "idx_webhook_events_received_at",        columnList = "received_at")
    }
)
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "signature")
    private String signature;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "headers_json", columnDefinition = "TEXT")
    private String headersJson;

    @Column(name = "processing_status", nullable = false, length = 30)
    private String processingStatus = "RECEIVED";

    @Column(name = "processing_attempts", nullable = false)
    private Integer processingAttempts = 0;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @PrePersist
    public void prePersist() {
        if (receivedAt == null) {
            receivedAt = LocalDateTime.now();
        }
    }
}