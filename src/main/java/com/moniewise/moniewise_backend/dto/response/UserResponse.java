package com.moniewise.moniewise_backend.dto.response; // ✅ Correct Package

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.utils.ProfileCompletion;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class UserResponse { // Renamed to UserResponse to avoid confusion
    private Long id;
    private String email;
    private String phone;
    private Role role;
    private LocalDateTime createdAt;
    private Instant lastLogin;
    private Map<String, Object> profileData;
    private boolean isVerified;
    private String profileImageUrl;
    private String gender; // Top-level so Flutter UserProfile.fromJson reads it
    private boolean needsProfileUpdate;

    // ✅ The Contract: Always return a Wallet object, never null.
    private WalletInfo wallet;

    public UserResponse(User user, Wallet walletEntity) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.phone = user.getPhone();
        this.role = user.getRole();
        this.createdAt = user.getCreatedAt();
        this.lastLogin = user.getLastLogin();
        this.isVerified = user.isVerified();
        this.profileImageUrl = user.getProfileImageUrl();
        this.gender = user.getGender() != null ? user.getGender().name() : null;

        // Merge profileImageUrl into profileData['imageUrl'] so the Flutter
        // ProfileData model (which reads json['imageUrl'] from the profileData map)
        // always receives the image URL even if it wasn't stored in-line.
        Map<String, Object> pd = user.getProfileData();
        if (pd == null) pd = new java.util.HashMap<>();
        if (user.getProfileImageUrl() != null && !user.getProfileImageUrl().isBlank()) {
            pd.put("imageUrl", user.getProfileImageUrl());
        }

        // Also add gender to profileData so the edit form can read it
        if (user.getGender() != null) {
            pd.put("gender", user.getGender().name());
        }

        this.profileData = pd;

        this.needsProfileUpdate = ProfileCompletion.needsProfileUpdate(user);

        // 🛡️ Production Logic: Handle "Empty Shell" Users
        if (walletEntity != null) {
            this.wallet = new WalletInfo(
                walletEntity.getAccountNumber(),
                walletEntity.getBankName(),
                resolveFundingAccountName(walletEntity, user),
                // Safety check: ensure .toString() is never called on null balance
                walletEntity.getBalance() != null ? walletEntity.getBalance().toString() : "0.00"
            );
        } else {
            // ✅ Default State for Google Users
            this.wallet = new WalletInfo("PENDING_SETUP", "PENDING_SETUP", "PENDING_SETUP", "0.00");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String resolveFundingAccountName(Wallet wallet, User user) {
        if (wallet != null && !isBlank(wallet.getAccountName())) {
            return wallet.getAccountName();
        }
        if (wallet != null && wallet.getProviderMetadata() != null) {
            Object metadataAccountName = wallet.getProviderMetadata().get("accountName");
            if (metadataAccountName == null) {
                metadataAccountName = wallet.getProviderMetadata().get("account_name");
            }
            if (metadataAccountName != null && !isBlank(metadataAccountName.toString())) {
                return metadataAccountName.toString().trim();
            }
        }
        Map<String, Object> profile = user.getProfileData() != null ? user.getProfileData() : Map.of();
        String first = stringFromProfile(profile, "bvnFirstName", "bvnFirst", "firstName");
        String last = stringFromProfile(profile, "bvnLastName", "bvnLast", "lastName");
        if (!isBlank(first) && !isBlank(last)) return (first + " " + last).toUpperCase();
        if (!isBlank(last)) return last.toUpperCase();
        String email = user.getEmail();
        return email != null ? email.split("@")[0].toUpperCase() : "ACCOUNT HOLDER";
    }

    private static String stringFromProfile(Map<String, Object> profile, String... keys) {
        for (String key : keys) {
            Object value = profile.get(key);
            if (value != null && !isBlank(value.toString())) {
                return value.toString().trim();
            }
        }
        return null;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    public static class WalletInfo {
        private String accountNumber;
        private String bankName;
        private String accountName;
        private String balance;
    }
}
