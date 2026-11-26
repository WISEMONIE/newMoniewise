package com.moniewise.moniewise_backend.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Shared helpers for verifying PSP webhook signatures.
 *
 * <p>Every webhook route is public (no JWT), so the signature is the only thing
 * standing between the internet and a wallet credit. Gateways must <b>fail closed</b>:
 * no configured secret, no signature header, or any error while computing the digest
 * means the request is rejected.
 */
public final class WebhookSignatures {

    private WebhookSignatures() {
    }

    /**
     * Timing-attack-resistant comparison of two hex digests. Case-insensitive, since
     * providers differ on upper/lower-case hex; surrounding whitespace is ignored.
     */
    public static boolean hexDigestsMatch(String expected, String received) {
        if (expected == null || received == null) {
            return false;
        }
        byte[] a = expected.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] b = received.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
