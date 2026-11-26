package com.moniewise.moniewise_backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalReleaseRulesTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 16);
    private static final LocalTime EIGHT_AM = LocalTime.of(8, 0);

    @Test
    void hidesTheRuleWhenTheBudgetIsUnderFourteenDays() {
        assertFalse(IntervalReleaseRules.isOffered(13));
        assertTrue(IntervalReleaseRules.isOffered(14));
        assertEquals(
                "Every few days needs a budget of at least 14 days. This budget is 13 day(s). Switch this envelope to Daily or Weekly.",
                IntervalReleaseRules.rejection(13, 2)
        );
        assertNull(IntervalReleaseRules.rejection(14, 2));
    }

    @Test
    void refusesZeroAndAGapThatCannotFitTwoReleases() {
        assertEquals("Choose at least 2 days between releases.", IntervalReleaseRules.rejection(30, 0));
        assertEquals("Choose at least 2 days between releases.", IntervalReleaseRules.rejection(30, 1));
        assertEquals("Choose a whole number of days between releases.", IntervalReleaseRules.rejection(30, -1));
        assertEquals(
                "This budget can go up to every 14 days, so two releases still fit.",
                IntervalReleaseRules.rejection(15, 15)
        );
        assertEquals(
                "This budget can go up to every 29 days, so two releases still fit.",
                IntervalReleaseRules.rejection(30, 30)
        );
        assertNull(IntervalReleaseRules.rejection(15, 14));
        assertNull(IntervalReleaseRules.rejection(30, 29));
        assertThrows(IllegalArgumentException.class, () -> IntervalReleaseRules.validate(15, 0));
    }

    @Test
    void countsTheQuietDaysInsideTheSlices() {
        assertEquals(8, IntervalReleaseRules.releaseCount(15, 2));
        assertEquals(15, IntervalReleaseRules.releaseCount(30, 2));
        assertEquals(2, IntervalReleaseRules.releaseCount(15, 14));
        assertEquals(7, IntervalReleaseRules.releaseCount(14, 2));
        assertEquals(4, IntervalReleaseRules.releaseCount(START, START.plusDays(14), 4));

        assertEquals(new BigDecimal("2000.00"), IntervalReleaseRules.portion(new BigDecimal("16000.00"), 8));
        assertEquals(new BigDecimal("4000.00"), IntervalReleaseRules.portion(new BigDecimal("60000.00"), 15));
        assertEquals(new BigDecimal("1250.13"), IntervalReleaseRules.portion(new BigDecimal("10001.00"), 8));
        assertEquals(new BigDecimal("10001.00"), IntervalReleaseRules.portion(new BigDecimal("10001.00"), 1));
    }

    @Test
    void readsOnlyWholeDayGaps() {
        assertEquals(2, IntervalReleaseRules.readIntervalDays(Map.of("intervalDays", 2)));
        assertEquals(2, IntervalReleaseRules.readIntervalDays(Map.of("intervalDays", 2.0d)));
        assertEquals(4, IntervalReleaseRules.readIntervalDays(Map.of("intervalDays", "4")));
        assertEquals(-1, IntervalReleaseRules.readIntervalDays(Map.of("intervalDays", 2.5d)));
        assertEquals(-1, IntervalReleaseRules.readIntervalDays(Map.of("intervalDays", "2.5")));
        assertEquals(-1, IntervalReleaseRules.readIntervalDays(Map.of()));
    }

    @Test
    void waitsForTheFirstClockAndSkipsAMissedMorning() {
        LocalDate end = START.plusDays(29);
        LocalDateTime beforeClock = LocalDateTime.of(2026, 10, 16, 7, 0);
        assertEquals(
                LocalDateTime.of(2026, 10, 16, 8, 0),
                IntervalReleaseRules.nextDisbursement(START, end, null, beforeClock, EIGHT_AM, 2)
        );

        LocalDateTime afterClock = LocalDateTime.of(2026, 10, 16, 9, 0);
        assertEquals(
                LocalDateTime.of(2026, 10, 18, 8, 0),
                IntervalReleaseRules.nextDisbursement(START, end, null, afterClock, EIGHT_AM, 2)
        );

        LocalDateTime last = LocalDateTime.of(2026, 10, 16, 8, 0);
        assertTrue(IntervalReleaseRules.alreadyReleasedThisWindow(last.toLocalDate(), LocalDate.of(2026, 10, 17), 2));
        assertFalse(IntervalReleaseRules.alreadyReleasedThisWindow(last.toLocalDate(), LocalDate.of(2026, 10, 18), 2));

        LocalDateTime caughtUp = LocalDateTime.of(2026, 10, 19, 9, 0);
        assertEquals(
                LocalDateTime.of(2026, 10, 20, 8, 0),
                IntervalReleaseRules.nextDisbursement(START, end, caughtUp, caughtUp, EIGHT_AM, 2)
        );
        assertTrue(IntervalReleaseRules.alreadyReleasedThisWindow(
                caughtUp.toLocalDate(), caughtUp.toLocalDate(), 2, START));
        assertFalse(IntervalReleaseRules.alreadyReleasedThisWindow(
                caughtUp.toLocalDate(), LocalDate.of(2026, 10, 20), 2, START));
    }

    @Test
    void anEditedGapCountsForwardFromTheLastRelease() {
        LocalDate end = START.plusDays(29);
        LocalDateTime last = LocalDateTime.of(2026, 10, 18, 8, 0);
        LocalDateTime now = LocalDateTime.of(2026, 10, 18, 8, 5);
        assertEquals(
                LocalDateTime.of(2026, 10, 20, 8, 0),
                IntervalReleaseRules.nextDisbursement(START, end, last, now, EIGHT_AM, 4)
        );
        assertEquals(
                LocalDateTime.of(2026, 10, 22, 8, 0),
                IntervalReleaseRules.nextDisbursement(
                        START, end, last, now, EIGHT_AM, 4, LocalDate.of(2026, 10, 18))
        );
        assertEquals(LocalDate.of(2026, 10, 18), IntervalReleaseRules.readAnchorDate(
                Map.of("intervalAnchorDate", "2026-10-18")));
        assertNull(IntervalReleaseRules.readAnchorDate(Map.of()));
        assertEquals(3, IntervalReleaseRules.releaseCountOnGrid(
                START, LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 30), 4));
    }

    @Test
    void theFinalMorningIsInsideTheBudget() {
        LocalDate end = LocalDate.of(2026, 10, 30);
        assertFalse(IntervalReleaseRules.isLastRelease(LocalDate.of(2026, 10, 28), end, 2));
        assertTrue(IntervalReleaseRules.isLastRelease(LocalDate.of(2026, 10, 30), end, 2));
        assertNull(IntervalReleaseRules.nextDisbursement(
                START,
                end,
                LocalDateTime.of(2026, 10, 30, 8, 0),
                LocalDateTime.of(2026, 10, 30, 8, 1),
                EIGHT_AM,
                2
        ));
    }

    @Test
    void writesTheReleaseMessage() {
        assertEquals(
                "\u20A62,000.00 has been unlocked in your 'Food' envelope. The next \u20A62,000.00 arrives in 2 days.",
                IntervalReleaseRules.successMessage("2,000.00", "Food", false, "2,000.00", 2)
        );
        assertEquals(
                "\u20A62,000.00 has been unlocked in your 'Food' envelope. This is the last release for this budget.",
                IntervalReleaseRules.successMessage("2,000.00", "Food", true, "2,000.00", 2)
        );
    }
}
