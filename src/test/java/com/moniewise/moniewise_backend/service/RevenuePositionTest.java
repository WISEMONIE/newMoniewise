package com.moniewise.moniewise_backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevenuePositionTest {

    @Test
    void screenshotGapClosesWhenCostAndUncreditedMarkupAreCounted() {
        RevenuePosition.Result result = RevenuePosition.explain(
                new BigDecimal("7701.36"),
                new BigDecimal("990.00"),
                new BigDecimal("94.00"),
                new BigDecimal("70.00"),
                1,
                new BigDecimal("8715.36"),
                new BigDecimal("1.00"));

        assertEquals(0, result.expectedRubies().compareTo(new BigDecimal("8715.36")));
        assertEquals(0, result.rawGap().compareTo(new BigDecimal("1014.00")));
        assertEquals(0, result.unexplained().compareTo(new BigDecimal("0.00")));
        assertEquals(RevenuePosition.STATUS_OK, result.status());
        assertEquals(0, result.payeelordCostOutstanding().compareTo(new BigDecimal("990.00")));
        assertTrue(result.message().contains("available to send"));
    }

    @Test
    void amountAvailableToSendIsTheCostOnly() {
        RevenuePosition.Result result = RevenuePosition.explain(
                new BigDecimal("100.00"),
                new BigDecimal("975.00"),
                new BigDecimal("25.00"),
                BigDecimal.ZERO,
                0,
                new BigDecimal("1100.00"),
                RevenuePosition.DEFAULT_TOLERANCE);

        assertEquals(0, result.payeelordCostOutstanding().compareTo(new BigDecimal("975.00")));
        assertEquals(0, new BigDecimal(result.toMap().get("amountAvailableToSendToPayeelord").toString())
                .compareTo(new BigDecimal("975.00")));
        assertEquals(RevenuePosition.STATUS_OK, result.status());
    }

    @Test
    void oneNairaIsInsideToleranceAndOneKoboPastItIsNot() {
        RevenuePosition.Result inside = RevenuePosition.explain(
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                new BigDecimal("101.00"), new BigDecimal("1.00"));
        RevenuePosition.Result outside = RevenuePosition.explain(
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                new BigDecimal("101.01"), new BigDecimal("1.00"));

        assertEquals(RevenuePosition.STATUS_OK, inside.status());
        assertEquals(RevenuePosition.STATUS_MISMATCH, outside.status());
    }

    @Test
    void missingBankBalanceStaysClassified() {
        RevenuePosition.Result result = RevenuePosition.explain(
                new BigDecimal("7701.36"),
                new BigDecimal("500.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                null,
                RevenuePosition.DEFAULT_TOLERANCE);

        assertEquals(RevenuePosition.STATUS_CLASSIFIED, result.status());
        assertNull(result.actualRubies());
        assertNull(result.unexplained());
        assertEquals(0, result.expectedRubies().compareTo(new BigDecimal("8201.36")));
    }

    @Test
    void missingWalletFeesLowerTheExpectedBankBalance() {
        RevenuePosition.Result result = RevenuePosition.explain(
                new BigDecimal("1000.00"),
                new BigDecimal("200.00"),
                BigDecimal.ZERO,
                new BigDecimal("140.00"),
                2,
                new BigDecimal("1060.00"),
                RevenuePosition.DEFAULT_TOLERANCE);

        assertEquals(0, result.expectedRubies().compareTo(new BigDecimal("1060.00")));
        assertEquals(RevenuePosition.STATUS_OK, result.status());
        assertEquals(2, result.missingWalletOpenFeeCount());
    }
}
