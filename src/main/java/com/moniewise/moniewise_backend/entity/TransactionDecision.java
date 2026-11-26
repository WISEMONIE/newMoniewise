package com.moniewise.moniewise_backend.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "transaction_decisions")
public class TransactionDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "transaction_request_id", nullable = false, unique = true)
    private TransactionRequest transactionRequest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "reason_message")
    private String reasonMessage;

    @Column(name = "kyc_check_passed", nullable = false)
    private boolean kycCheckPassed;

    @Column(name = "tier_check_passed", nullable = false)
    private boolean tierCheckPassed;

    @Column(name = "wallet_check_passed", nullable = false)
    private boolean walletCheckPassed;

    @Column(name = "budget_check_passed", nullable = false)
    private boolean budgetCheckPassed;

    @Column(name = "requires_confirmation", nullable = false)
    private boolean requiresConfirmation;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public enum Decision {
        ALLOW, BLOCK, REQUIRE_CONFIRMATION, MANUAL_REVIEW
    }
}