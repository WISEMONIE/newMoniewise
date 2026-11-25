package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "wallets")
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false, length = 10)
    private String currency = "NGN";

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private WalletStatus status = WalletStatus.ACTIVE;

    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "wallet_type")
    private String walletType;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** When the provider account opened. Null for wallets opened before this was recorded. */
    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "is_revenue_wallet", nullable = false)
    private boolean revenueWallet = false;

    @Column(name = "settlement_account_number")
    private String settlementAccountNumber;

    @Column(name = "settlement_bank_code")
    private String settlementBankCode;

    @Column(name = "settlement_bank_name")
    private String settlementBankName;

    @Column(name = "settlement_account_name")
    private String settlementAccountName;

    // =========================
    // Provider mapping fields
    // =========================

    @Column(name = "provider_name", length = 50)
    private String providerName;

    @Column(name = "provider_customer_ref")
    private String providerCustomerRef;

    @Column(name = "provider_wallet_ref")
    private String providerWalletRef;

    @Column(name = "master_wallet_ref")
    private String masterWalletRef;

    @Column(name = "sub_wallet_ref")
    private String subWalletRef;

    @Column(name = "provider_status", length = 50)
    private String providerStatus;

    @Column(name = "last_balance_sync_at")
    private LocalDateTime lastBalanceSyncAt;

    @Type(type = "jsonb")
    @Convert(disableConversion = true)
    @Column(name = "provider_metadata", columnDefinition = "jsonb")
    private Map<String, Object> providerMetadata = new HashMap<>();

    @PrePersist
    public void prePersist() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
