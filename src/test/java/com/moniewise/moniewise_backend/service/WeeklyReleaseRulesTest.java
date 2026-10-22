package com.moniewise.moniewise_backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeeklyReleaseRulesTest {

    private static final LocalDate BUDGET_END = LocalDate.of(2026, 12, 31);

    @Test
    void aThursdayReleaseResetsOnTheComingMonday() {
        LocalDateTime next = WeeklyReleaseRules.nextReset(
                LocalDate.of(2026, 10, 22),
                LocalDateTime.of(2026, 10, 22, 15, 0),
                LocalDate.of(2026, 10, 1),
                BUDGET_END
        );

        assertEquals(LocalDateTime.of(2026, 10, 26, 0, 0), next);
    }

    @Test
    void aMondayReleaseWaitsUntilTheFollowingMonday() {
        LocalDateTime next = WeeklyReleaseRules.nextReset(
                LocalDate.of(2026, 10, 19),
                LocalDateTime.of(2026, 10, 19, 0, 5),
                LocalDate.of(2026, 10, 1),
                BUDGET_END
        );

        assertEquals(LocalDateTime.of(2026, 10, 26, 0, 0), next);
    }

    @Test
    void aSundayReleaseResetsTheNextMorning() {
        LocalDateTime next = WeeklyReleaseRules.nextReset(
                LocalDate.of(2026, 10, 25),
                LocalDateTime.of(2026, 10, 25, 18, 0),
                LocalDate.of(2026, 10, 1),
                BUDGET_END
        );

        assertEquals(LocalDateTime.of(2026, 10, 26, 0, 0), next);
    }

    @Test
    void aMondayThatHasAlreadyStartedWaitsAnotherWeek() {
        LocalDateTime next = WeeklyReleaseRules.nextReset(
                LocalDate.of(2026, 10, 22),
                LocalDateTime.of(2026, 10, 26, 9, 0),
                LocalDate.of(2026, 10, 1),
                BUDGET_END
        );

        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 0), next);
    }

    @Test
    void aBudgetThatStartsAfterTheComingMondayUsesTheFirstMondayInsideIt() {
        LocalDateTime next = WeeklyReleaseRules.nextReset(
                LocalDate.of(2026, 10, 22),
                LocalDateTime.of(2026, 10, 22, 15, 0),
                LocalDate.of(2026, 10, 28),
                BUDGET_END
        );

        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 0), next);
    }

    @Test
    void noResetIsScheduledAfterTheBudgetHasEnded() {
        assertNull(WeeklyReleaseRules.nextReset(
                LocalDate.of(2026, 10, 22),
                LocalDateTime.of(2026, 10, 22, 15, 0),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 25)
        ));
    }
}
