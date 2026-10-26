package com.moniewise.moniewise_backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.moniewise.moniewise_backend.enums.Gender;
import com.moniewise.moniewise_backend.enums.Role;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.springframework.data.jpa.convert.threeten.Jsr310JpaConverters;

import javax.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Entity

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
@Table(name = "users", indexes = {
        @Index(name = "idx_email", columnList = "email"),
        @Index(name = "idx_phone", columnList = "phone")
})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "is_deleted")
    private boolean isDeleted = false;

    /**
     * Stamped when the user taps Delete but money still has to be withdrawn
     * before the account can close. Their intent is already captured — and
     * PIN-verified — at that moment, so this timestamp is what lets closure
     * finish on its own if they withdraw and never come back to tap Delete a
     * second time. Without it, savings/budgets are destroyed and the account
     * silently stays open. Cleared on cancellation and on actual closure.
     */
    @Column(name = "closure_requested_at")
    private LocalDateTime closureRequestedAt;

    @Column(name = "is_verified", nullable = false)
    private boolean isVerified = false; // Default to false

    /**
     * True ONLY for the seeded app-review account (see ReviewAccountSeeder).
     * It lets that one account skip the first-login-per-device email OTP — which
     * a store reviewer physically can't receive — while its password is still
     * verified normally. This is NOT an auth bypass: it is scoped to a single
     * DB-flagged row and never touches password validation. Default false.
     */
    @Column(name = "test_account", nullable = false)
    private boolean testAccount = false;

    @JsonIgnore  // Add this annotation
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL)
    private Wallet wallet;

    @Column(unique = true)
    private String phone;

    @Column(name = "gender")
    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Column(name = "fcm_token")
    private String fcmToken; // Added for FCM push notifications

    @Column
    private String password; // NULL for OAuth users

    // newly added --->04/06/25
    @Column(name = "tnc_accepted")
    private Boolean tncAccepted;

    @Convert(disableConversion = true) // Disable auto-converter
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> profileData;

    // please remember to change to true --- NOtE this is testing and we switching to prod soon
    @Column(unique = true)
    private String bvn;

    @Column(nullable = false)
    private String timezone = "Africa/Lagos";

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Role role = Role.USER; // Default to USER

    @Column(name = "created_at", updatable = false)
    @Convert(converter = Jsr310JpaConverters.LocalDateTimeConverter.class) // Add this
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * How many "complete your profile" nudge emails have been sent to this
     * user since signup. Used by {@code IncompleteSignupLifecycleManager} to
     * compute when the next nudge is due (and, eventually, when to purge a
     * stale registration that never completed KYC/profile + wallet creation).
     * 0 = no nudge sent yet.
     */
    @Column(name = "onboarding_reminder_count", nullable = false)
    private int onboardingReminderCount = 0;

    /**
     * Timestamp of the most recent "complete your profile" nudge email.
     * NULL means none has been sent yet. Prevents double-sends if the
     * lifecycle worker runs more than once in a short window.
     */
    @Column(name = "last_onboarding_reminder_at")
    private LocalDateTime lastOnboardingReminderAt;

    @Column(name = "last_login")
    private Instant lastLogin;

    @Column(name = "current_session_id")
    private String currentSessionId;

    @Lob // Tells DB this is a Large Object
    @Type(type = "org.hibernate.type.BinaryType") // Critical for PostgreSQL to save as bytea, not OID
    @Column(name = "profile_image")
    private byte[] profileImage;

    @Column(name = "transaction_pin")
    private String transactionPin; // Stores the BCrypt Hash (e.g. $2a$10$...)

    // Helper method for the UI (so we don't send the actual hash)
    public boolean hasTransactionPin() {
        return this.transactionPin != null && !this.transactionPin.isEmpty();
    }

    public boolean isDeleted() { return isDeleted; }
    public void setDeleted(boolean deleted) { isDeleted = deleted; }

    public LocalDateTime getClosureRequestedAt() { return closureRequestedAt; }
    public void setClosureRequestedAt(LocalDateTime v) { this.closureRequestedAt = v; }

    // 2. ENSURE this exists (You already have it) ðŸ‘‡
    @Column(name = "profile_image_url", columnDefinition = "TEXT")
    private String profileImageUrl;

    @Column(name = "profile_image_blob_name", columnDefinition = "TEXT")
    private String profileImageBlobName;

    @Column(name = "referral_source", length = 50)
    private String referralSource;

    @Column(name = "referral_code", length = 16, unique = true)
    private String referralCode;

    @Column(name = "referred_by_user_id")
    private Long referredByUserId;

    // For JwtUtil compatibility (pass email as token subject)
    public String getUsername() {
        return email;
    }

    // Extract name from profile_data
    public String getName() {
        if (profileData != null && profileData.containsKey("name")) {
            return (String) profileData.get("name");
        }
        return null;
    }

    public ZoneId getZoneId() {
        return ZoneId.of(this.timezone);
    }
}
