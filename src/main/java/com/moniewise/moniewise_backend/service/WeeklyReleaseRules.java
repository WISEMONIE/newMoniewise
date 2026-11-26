package com.moniewise.moniewise_backend.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * Weekly pocket refresh.
 *
 * A week starts on Monday at 00:00. The next refresh is the Monday after the
 * last release. A Monday that is no longer ahead of {@code now} waits one
 * more week, so the same week is not opened twice.
 */
public final class WeeklyReleaseRules {

    private WeeklyReleaseRules() {
    }

    public static LocalDateTime nextReset(
            LocalDate lastReleaseDate,
            LocalDateTime now,
            LocalDate budgetStart,
            LocalDate budgetEnd
    ) {
        if (lastReleaseDate == null || now == null || budgetEnd == null) {
            return null;
        }

        LocalDateTime target = lastReleaseDate
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .atStartOfDay();
        while (!target.isAfter(now)) {
            target = target.plusWeeks(1);
        }

        if (budgetStart != null && target.toLocalDate().isBefore(budgetStart)) {
            target = budgetStart.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            while (!target.isAfter(now)) {
                target = target.plusWeeks(1);
            }
        }

        if (target.isAfter(budgetEnd.atTime(23, 59, 59))) {
            return null;
        }
        return target;
    }
}
