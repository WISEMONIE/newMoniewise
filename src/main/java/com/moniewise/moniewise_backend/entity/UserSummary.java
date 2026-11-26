package com.moniewise.moniewise_backend.entity;

public interface UserSummary {
    Long getId();
    String getFirstName();
    String getLastName();
    String getEmail();
    String getUserTag();
    String getProfileImageUrl(); // If you want to show their picture
    String getWalletAccountNumber();
    String getWalletBankName();
    String getWalletStatus();
    String getWalletProviderName();

    String getBvn();
}
