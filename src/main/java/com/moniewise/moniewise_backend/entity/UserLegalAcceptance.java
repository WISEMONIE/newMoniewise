package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.LegalDocumentType;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_legal_acceptances",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_user_doc_version_acceptance",
                        columnNames = {"user_id", "doc_type", "version"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_user_legal_acceptances_user_doc",
                        columnList = "user_id,doc_type,version"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLegalAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "legal_document_id", nullable = false)
    private LegalDocument legalDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 50)
    private LegalDocumentType docType;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt;

    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    @Column(name = "device_info", columnDefinition = "TEXT")
    private String deviceInfo;

    @PrePersist
    protected void onCreate() {
        if (acceptedAt == null) {
            acceptedAt = LocalDateTime.now();
        }
    }
}