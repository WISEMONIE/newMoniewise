package com.moniewise.moniewise_backend.exception;

/**
 * The BVN provider could not give a real answer: it was down, or it replied
 * with a failure that says nothing about the BVN (SecureWave has answered
 * HTTP 400 with "Data Fetched Successfully" and status "failed").
 *
 * <p>KycService treats this as "try the fallback". If nothing else can check
 * the BVN, the customer sees {@link #CLIENT_MESSAGE}, never the provider's
 * own wording. {@link #getMessage()} keeps the provider's details for logs.
 */
public class BvnProviderUnavailableException extends RuntimeException {

    public static final String CODE = "BVN_CHECK_UNAVAILABLE";
    public static final String CLIENT_MESSAGE =
            "We could not check your BVN right now. Please try again in a few minutes.";

    public BvnProviderUnavailableException(String providerDetail) {
        super("BVN provider could not complete the check: " + providerDetail);
    }

    public BvnProviderUnavailableException(String providerDetail, Throwable cause) {
        super("BVN provider could not complete the check: " + providerDetail, cause);
    }
}
