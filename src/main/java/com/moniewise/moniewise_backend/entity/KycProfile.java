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
@Table(name = "kyc_profiles")
public class KycProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true)
    private String bvn;

    @Column(name = "bvn_verified", nullable = false)
    private boolean bvnVerified = false;

    @Column(name = "source_of_funds")
    private String sourceOfFunds;

    @Column(name = "source_of_wealth")
    private String sourceOfWealth;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false)
    private KycStatus kycStatus = KycStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false)
    private RiskLevel riskLevel = RiskLevel.MEDIUM;

    // ── Fields populated from SecureWave BVN Verification ────────────────────

    /** "name_on_card" from SecureWave — the legal name printed on the card. */
    @Column(name = "name_on_card")
    private String nameOnCard;

    /** "enrolment_bank" — the bank where the BVN was first registered. */
    @Column(name = "enrolment_bank")
    private String enrolmentBank;

    /** "enrolment_branch" — the specific branch of the enrolment bank. */
    @Column(name = "enrolment_branch")
    private String enrolmentBranch;

    /** "formatted_registration_date" — human-readable BVN registration date. */
    @Column(name = "formatted_registration_date")
    private String formattedRegistrationDate;

    /** "level_of_account" — the tier level (e.g. Tier 2). */
    @Column(name = "level_of_account")
    private String levelOfAccount;

    /** "nin" — National Identification Number linked to this BVN. */
    @Column(name = "nin")
    private String nin;

    /** "watchlisted" — "YES" or "NO" from NIBSS watchlist check. */
    @Column(name = "watchlisted")
    private String watchlisted;

    /** "verification_status" from SecureWave (e.g. "VERIFIED"). */
    @Column(name = "bvn_verification_status")
    private String bvnVerificationStatus;

    // ── personal_info (flattened from SecureWave response) ───────────────────

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "middle_name")
    private String middleName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "gender")
    private String gender;

    @Column(name = "date_of_birth")
    private String dateOfBirth;

    @Column(name = "state_of_origin")
    private String stateOfOrigin;

    @Column(name = "lga_of_origin")
    private String lgaOfOrigin;

    @Column(name = "nationality")
    private String nationality;

    @Column(name = "marital_status")
    private String maritalStatus;

    // ── residential_info (flattened from SecureWave response) ────────────────

    @Column(name = "state_of_residence")
    private String stateOfResidence;

    @Column(name = "lga_of_residence")
    private String lgaOfResidence;

    @Column(name = "residential_address", length = 500)
    private String residentialAddress;

    // ── Audit timestamps ──────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum KycStatus {
        PENDING, VERIFIED, REJECTED
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }
}
