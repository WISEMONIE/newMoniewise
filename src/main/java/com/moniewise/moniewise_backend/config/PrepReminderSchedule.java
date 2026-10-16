package com.moniewise.moniewise_backend.config;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Along-the-way reminders repeat so a person can keep saving for an event.
 * Far events use a two-week gap. Inside 30 days the gap is one week.
 * The day before and the morning are separate messages, so this one stops
 * once a single day remains. A missed run sends one reminder with today's
 * real day count. It does not send a stack of old ones.
 */
public final class PrepReminderSchedule {

    static final int FAR_DAYS = 30;
    static final int SOON_DAYS = 14;
    static final int FAR_INTERVAL = 14;
    static final int NEAR_INTERVAL = 7;

    private PrepReminderSchedule() {}

    public static int intervalDays(long daysUntil) {
        return daysUntil > FAR_DAYS ? FAR_INTERVAL : NEAR_INTERVAL;
    }

    public static boolean due(long daysUntil, LocalDate today, LocalDate startDate, LocalDate lastSent) {
        if (daysUntil <= 1 || today == null) {
            return false;
        }
        if (lastSent == null) {
            LocalDate start = startDate != null ? startDate : today;
            long sinceStart = ChronoUnit.DAYS.between(start, today);
            if (sinceStart < 1) {
                return false;
            }
            return sinceStart >= intervalDays(daysUntil) || daysUntil <= SOON_DAYS;
        }
        long since = ChronoUnit.DAYS.between(lastSent, today);
        if (since < 1) {
            return false;
        }
        // Keep the gap that was in force when the last reminder went out.
        // Crossing into the final month must not turn a two-week wait into a week.
        long daysUntilWhenSent = daysUntil + since;
        return since >= intervalDays(daysUntilWhenSent);
    }
}
