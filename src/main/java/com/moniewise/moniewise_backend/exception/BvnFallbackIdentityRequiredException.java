package com.moniewise.moniewise_backend.exception;

/**
 * SecureWave could not be reached, and Rubies still needs the name on the BVN.
 * The wallet screen asks for that name and the date of birth, then retries.
 */
public class BvnFallbackIdentityRequiredException extends RuntimeException {

    public static final String CODE = "BVN_FALLBACK_IDENTITY_REQUIRED";
    public static final String CLIENT_MESSAGE =
            "Enter the name and date of birth on your BVN to finish this check.";

    public BvnFallbackIdentityRequiredException() {
        super(CLIENT_MESSAGE);
    }
}
