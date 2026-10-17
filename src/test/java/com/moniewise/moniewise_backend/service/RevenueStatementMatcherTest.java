package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Classified;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Direction;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.ExpectedMovement;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Line;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Lookup;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Outcome;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Purchase;
import com.moniewise.moniewise_backend.service.RevenueStatementMatcher.Report;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevenueStatementMatcherTest {

    private static final LocalDate CUTOFF = LocalDate.of(2026, 10, 18);
    private static final BigDecimal FEE = new BigDecimal("70.00");
    private static final Purchase SALE = new Purchase(
            "VAS-AT-1", 9L,
            new BigDecimal("1000.00"),
            new BigDecimal("975.00"),
            new BigDecimal("25.00"),
            false);

    @Test
    void bookedAirtimeCreditMatchesWithoutAHeal() {
        Purchase booked = new Purchase(SALE.reference(), SALE.userId(), SALE.selling(), SALE.cost(), SALE.margin(), true);
        Report report = classify(credit("REV-VAS-AT-1", "1000.00", "2026-10-19"),
                lookup(booked, false));

        Classified row = report.lines().get(0);
        assertEquals(Outcome.MATCHED_VAS, row.outcome());
        assertFalse(row.healable());
    }

    @Test
    void unbookedAirtimeCreditAfterTheCutoffIsHealable() {
        Classified row = classify(credit("REV-VAS-AT-1", "1000.00", "2026-10-19"), lookup(SALE, false))
                .lines().get(0);

        assertEquals(Outcome.MISSING_VAS_BOOKING, row.outcome());
        assertTrue(row.healable());
        assertEquals(0, row.purchase().margin().compareTo(new BigDecimal("25.00")));
    }

    @Test
    void unbookedAirtimeCreditBeforeTheCutoffIsReportedAndNotHealable() {
        Classified row = classify(credit("REV-VAS-AT-1", "1000.00", "2026-10-17"), lookup(SALE, false))
                .lines().get(0);

        assertEquals(Outcome.MISSING_VAS_BOOKING, row.outcome());
        assertFalse(row.healable());
    }

    @Test
    void marginSizedCreditIsNotBookedAsTheSale() {
        Classified row = classify(credit("REV-VAS-AT-1", "25.00", "2026-10-19"), lookup(SALE, false))
                .lines().get(0);

        assertEquals(Outcome.VAS_AMOUNT_DIFFERS, row.outcome());
        assertFalse(row.healable());
    }

    @Test
    void creditWithNoKnownReferenceStaysUnmatched() {
        Classified row = classify(credit("NIP-SESSION-1", "1000.00", "2026-10-19"), lookup(SALE, false))
                .lines().get(0);

        assertEquals(Outcome.UNMATCHED, row.outcome());
        assertFalse(row.healable());
    }

    @Test
    void narrationStillYieldsTheCollectionReference() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("drCr", "CR");
        raw.put("amount", "1000.00");
        raw.put("transactionDate", "2026-10-19");
        raw.put("responseCode", "00");
        raw.put("narration", "transfer REV-VAS-AT-1 settled");
        Line line = RevenueStatementMatcher.parse(raw);

        assertEquals(Direction.CREDIT, line.direction());
        assertTrue(line.references().contains("REV-VAS-AT-1"));
    }

    @Test
    void namedOpeningFeeOfSeventyAfterTheCutoffIsHealable() {
        Classified row = classify(debit("WALLET-OPEN-9", "70.00", "2026-10-19"), lookup(SALE, false))
                .lines().get(0);

        assertEquals(Outcome.MISSING_OPENING_FEE, row.outcome());
        assertEquals(9L, row.openingFeeUserId());
        assertTrue(row.healable());
    }

    @Test
    void eighteenNairaOpeningFeeIsNotHealedAsSeventy() {
        Classified row = classify(debit("WALLET-OPEN-9", "18.00", "2026-10-19"), lookup(SALE, false))
                .lines().get(0);

        assertEquals(Outcome.OPENING_FEE_AMOUNT_DIFFERS, row.outcome());
        assertFalse(row.healable());
    }

    @Test
    void bareSeventyDebitIsNotAssignedToAUser() {
        Classified row = classify(debit("BANK-DEBIT-1", "70.00", "2026-10-19"), lookup(SALE, false))
                .lines().get(0);

        assertEquals(Outcome.OPENING_FEE_UNATTRIBUTED, row.outcome());
        assertFalse(row.healable());
    }

    @Test
    void recordedOpeningFeeMatches() {
        Classified row = classify(debit("WALLET-OPEN-9", "70.00", "2026-10-19"), lookup(SALE, true))
                .lines().get(0);

        assertEquals(Outcome.MATCHED_OPENING_FEE, row.outcome());
        assertFalse(row.healable());
    }

    @Test
    void pendingLineIsSkipped() {
        Map<String, Object> raw = credit("REV-VAS-AT-1", "1000.00", "2026-10-19");
        raw.put("responseCode", "09");
        Classified row = classify(raw, lookup(SALE, false)).lines().get(0);

        assertEquals(Outcome.SKIPPED_NOT_FINAL, row.outcome());
        assertFalse(row.healable());
    }

    @Test
    void completedBookRowMissingFromTheStatementIsReported() {
        Lookup lookup = lookup(SALE, false, List.of(new ExpectedMovement("REV-BUD-4")), List.of());
        Report report = classify(credit("REV-VAS-AT-1", "1000.00", "2026-10-19"), lookup);

        assertEquals(List.of("REV-BUD-4"), report.missingOnRubies());
    }

    @Test
    void cursorStopsOnTheEarliestHealableLine() {
        List<Classified> lines = List.of(
                classify(credit("REV-VAS-AT-1", "1000.00", "2026-10-20"), lookup(SALE, false)).lines().get(0),
                classify(debit("WALLET-OPEN-9", "70.00", "2026-10-19"), lookup(SALE, false)).lines().get(0));

        assertEquals(LocalDate.of(2026, 10, 19),
                RevenueStatementMatcher.nextCursor(LocalDate.of(2026, 10, 20), lines));
    }

    @Test
    void cursorAdvancesWhenNothingIsHealable() {
        Classified historical = classify(credit("REV-VAS-AT-1", "1000.00", "2026-10-17"), lookup(SALE, false))
                .lines().get(0);

        assertEquals(LocalDate.of(2026, 10, 20),
                RevenueStatementMatcher.nextCursor(LocalDate.of(2026, 10, 20), List.of(historical)));
    }

    @Test
    void cursorStaysWhenAHealableLineHasNoDate() {
        Map<String, Object> raw = credit("REV-VAS-AT-1", "1000.00", "2026-10-19");
        raw.remove("transactionDate");
        Classified row = classify(raw, lookup(SALE, false)).lines().get(0);

        assertTrue(row.healable() == false);
        assertEquals(Outcome.MISSING_VAS_BOOKING, row.outcome());
        assertNull(RevenueStatementMatcher.nextCursor(LocalDate.of(2026, 10, 20), List.of(
                new Classified(row.line(), row.outcome(), row.keyReference(), true, row.purchase(), null))));
    }

    private static Report classify(Map<String, Object> raw, Lookup lookup) {
        return RevenueStatementMatcher.classify(
                List.of(RevenueStatementMatcher.parse(raw)), lookup, FEE, CUTOFF);
    }

    private static Map<String, Object> credit(String reference, String amount, String date) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("paymentReference", reference);
        raw.put("drCr", "CR");
        raw.put("amount", amount);
        raw.put("transactionDate", date);
        raw.put("responseCode", "00");
        return raw;
    }

    private static Map<String, Object> debit(String reference, String amount, String date) {
        Map<String, Object> raw = credit(reference, amount, date);
        raw.put("drCr", "DR");
        return raw;
    }

    private static Lookup lookup(Purchase purchase, boolean openingFeeRecorded) {
        return lookup(purchase, openingFeeRecorded, List.of(), List.of());
    }

    private static Lookup lookup(Purchase purchase, boolean openingFeeRecorded,
                                 List<ExpectedMovement> completed, List<ExpectedMovement> pending) {
        return new Lookup() {
            @Override
            public boolean transactionReferenceExists(String reference) {
                return false;
            }

            @Override
            public boolean revenueMentionExists(String reference) {
                return false;
            }

            @Override
            public Optional<Purchase> successfulPurchase(String purchaseReference) {
                if (purchase != null && purchase.reference().equals(purchaseReference)) {
                    return Optional.of(purchase);
                }
                return Optional.empty();
            }

            @Override
            public boolean openingFeeRecorded(long userId) {
                return openingFeeRecorded;
            }

            @Override
            public List<ExpectedMovement> completedExpected() {
                return completed;
            }

            @Override
            public List<ExpectedMovement> pendingExpected() {
                return pending;
            }
        };
    }
}
