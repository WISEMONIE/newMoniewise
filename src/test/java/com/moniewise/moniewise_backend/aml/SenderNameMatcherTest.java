package com.moniewise.moniewise_backend.aml;

import org.junit.jupiter.api.Test;

import static com.moniewise.moniewise_backend.aml.SenderNameMatcher.Result.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SenderNameMatcherTest {

    private static SenderNameMatcher.Result check(String sender) {
        return SenderNameMatcher.match(sender, "Emmanuel", "Chinedu", "Madu");
    }

    @Test
    void sameNameInAnyOrderWithInitialsMatches() {
        assertEquals(MATCH, check("EMMANUEL MADU"));
        assertEquals(MATCH, check("MADU EMMANUEL C."));
        assertEquals(MATCH, check("Madu, Emmanuel Chinedu"));
        assertEquals(MATCH, check("MR EMMANUEL MADU"));
    }

    @Test
    void oneSharedNameIsPartial() {
        assertEquals(PARTIAL, check("JOY MADU"));
        assertEquals(PARTIAL, check("EMMANUEL OKAFOR"));
        assertEquals(PARTIAL, check("CHINEDU EZE"));
    }

    @Test
    void someoneElseIsAMismatch() {
        assertEquals(MISMATCH, check("ADA OKAFOR"));
    }

    @Test
    void businessesAreFlaggedAsCompanies() {
        assertEquals(COMPANY, check("MADU VENTURES LTD"));
        assertEquals(COMPANY, check("Swift Logistics Nig"));
    }

    @Test
    void missingNamesAreUnknown() {
        assertEquals(UNKNOWN, check(null));
        assertEquals(UNKNOWN, check("  "));
        assertEquals(UNKNOWN, SenderNameMatcher.match("ADA OKAFOR", null, null, null));
    }

    @Test
    void accentsAndCaseDoNotMatter() {
        assertEquals(MATCH, SenderNameMatcher.match("adébáyọ̀ ọlá", "Adebayo", null, "Ola"));
    }
}
