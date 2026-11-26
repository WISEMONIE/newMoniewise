package com.moniewise.moniewise_backend.exception;

import org.springframework.http.HttpStatus;

public class WalletProvisioningException extends RuntimeException {

    private final String code;
    private final String userMessage;
    private final String providerMessage;
    private final HttpStatus status;

    public WalletProvisioningException(
            String code,
            String userMessage,
            String providerMessage,
            HttpStatus status
    ) {
        super(userMessage);
        this.code = code;
        this.userMessage = userMessage;
        this.providerMessage = providerMessage;
        this.status = status;
    }

    public static final String OPENING_IN_PROGRESS = "WALLET_OPENING_IN_PROGRESS";

    public static WalletProvisioningException openingInProgress() {
        return new WalletProvisioningException(
                OPENING_IN_PROGRESS,
                "Your wallet is being opened. Check again in a moment.",
                null,
                HttpStatus.CONFLICT
        );
    }

    public static WalletProvisioningException phoneRequired() {
        return new WalletProvisioningException(
                "WALLET_PHONE_REQUIRED",
                "Add your phone number to your profile before we open your wallet.",
                null,
                HttpStatus.BAD_REQUEST
        );
    }

    public static WalletProvisioningException missingVerifiedKycData() {
        return new WalletProvisioningException(
                "WALLET_KYC_REQUIRED",
                "Please verify your BVN before we create your wallet.",
                null,
                HttpStatus.BAD_REQUEST
        );
    }

    public static WalletProvisioningException kycIdentityMismatch(String providerMessage) {
        return new WalletProvisioningException(
                "WALLET_KYC_NAME_MISMATCH",
                "Your wallet could not be created because your BVN name could not be confirmed by our banking partner. Please contact support to review your BVN details.",
                providerMessage,
                HttpStatus.BAD_REQUEST
        );
    }

    public static WalletProvisioningException providerUnavailable(String providerMessage) {
        return new WalletProvisioningException(
                "WALLET_CREATION_FAILED",
                "We could not create your wallet right now. Please try again shortly.",
                providerMessage,
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    public String getCode() {
        return code;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getProviderMessage() {
        return providerMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
