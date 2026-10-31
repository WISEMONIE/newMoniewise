package com.moniewise.moniewise_backend.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignaturesTest {

    @Test
    void matchingDigestsCompareEqualIgnoringCaseAndWhitespace() {
        assertTrue(WebhookSignatures.hexDigestsMatch("ab12cd", "AB12CD"));
        assertTrue(WebhookSignatures.hexDigestsMatch("ab12cd", " ab12cd \n"));
    }

    @Test
    void differentOrMissingDigestsDoNotMatch() {
        assertFalse(WebhookSignatures.hexDigestsMatch("ab12cd", "ab12ce"));
        assertFalse(WebhookSignatures.hexDigestsMatch("ab12cd", "ab12"));
        assertFalse(WebhookSignatures.hexDigestsMatch("ab12cd", null));
        assertFalse(WebhookSignatures.hexDigestsMatch(null, "ab12cd"));
    }
}
