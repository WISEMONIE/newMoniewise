package com.moniewise.moniewise_backend.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrepReminderScheduleTest {

    private final LocalDate today = LocalDate.of(2026, 10, 16);

    @Test
    void skipsTheDayTheEventIsCreated() {
        assertFalse(PrepReminderSchedule.due(90, today, today, null));
    }

    @Test
    void farEventWaitsTwoWeeksForTheFirstReminder() {
        assertFalse(PrepReminderSchedule.due(80, today, today.minusDays(13), null));
        assertTrue(PrepReminderSchedule.due(76, today, today.minusDays(14), null));
    }

    @Test
    void soonEventRemindsTheDayAfterItIsCreated() {
        assertTrue(PrepReminderSchedule.due(10, today, today.minusDays(1), null));
    }

    @Test
    void waitsOutTheIntervalAfterAReminder() {
        assertFalse(PrepReminderSchedule.due(40, today, today.minusDays(30), today.minusDays(13)));
        assertTrue(PrepReminderSchedule.due(40, today, today.minusDays(30), today.minusDays(14)));
    }

    @Test
    void weeklyGapInsideThirtyDays() {
        assertFalse(PrepReminderSchedule.due(20, today, today.minusDays(40), today.minusDays(6)));
        assertTrue(PrepReminderSchedule.due(20, today, today.minusDays(40), today.minusDays(7)));
    }

    @Test
    void crossingIntoTheFinalMonthKeepsTheTwoWeekGap() {
        assertFalse(PrepReminderSchedule.due(30, today, today.minusDays(50), today.minusDays(10)));
        assertTrue(PrepReminderSchedule.due(26, today, today.minusDays(50), today.minusDays(14)));
    }

    @Test
    void stopsTheDayBeforeAndOnTheDay() {
        assertFalse(PrepReminderSchedule.due(1, today, today.minusDays(20), today.minusDays(8)));
        assertFalse(PrepReminderSchedule.due(0, today, today.minusDays(20), null));
    }
}
