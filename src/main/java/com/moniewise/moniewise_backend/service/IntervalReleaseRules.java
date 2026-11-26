package com.moniewise.moniewise_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * "Every few days" release rule.
 *
 * A budget shorter than 14 days does not offer this rule.
 * The gap is a whole number of days, at least 2, and small enough that the
 * first day and the last day of the budget can both still be release days.
 * Releases are counted from a start date at indexes 0, N, 2N, ... while the
 * date is still inside the budget.
 */
public final class IntervalReleaseRules {

    public static final int MINIMUM_BUDGET_DAYS = 14;
    public static final int MINIMUM_INTERVAL_DAYS = 2;
    public static final String TYPE = "interval";

    private IntervalReleaseRules() {
    }

    public static boolean isOffered(int durationDays) {
        return durationDays >= MINIMUM_BUDGET_DAYS;
    }

    public static int maximumIntervalDays(int durationDays) {
        return Math.max(MINIMUM_INTERVAL_DAYS, durationDays - 1);
    }

    /**
     * How many releases fit from {@code start} through {@code end} inclusive.
     */
    public static int releaseCount(LocalDate start, LocalDate end, int intervalDays) {
        if (start == null || end == null || end.isBefore(start) || intervalDays < 1) {
            return 0;
        }
        long span = ChronoUnit.DAYS.between(start, end);
        return (int) (span / intervalDays) + 1;
    }

    public static int releaseCount(int durationDays, int intervalDays) {
        if (durationDays < 1 || intervalDays < 1) {
            return 0;
        }
        return ((durationDays - 1) / intervalDays) + 1;
    }

    public static BigDecimal portion(BigDecimal amount, int releases) {
        if (amount == null || releases <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.divide(BigDecimal.valueOf(releases), 2, RoundingMode.HALF_UP);
    }

    /**
     * @return a user-facing reason to refuse the save, or null when the gap fits
     */
    public static String rejection(int durationDays, int intervalDays) {
        if (!isOffered(durationDays)) {
            return "Every few days needs a budget of at least 14 days. This budget is "
                    + durationDays + " day(s). Switch this envelope to Daily or Weekly.";
        }
        if (intervalDays < 0) {
            return "Choose a whole number of days between releases.";
        }
        if (intervalDays < MINIMUM_INTERVAL_DAYS) {
            return "Choose at least 2 days between releases.";
        }
        int maximum = durationDays - 1;
        if (intervalDays > maximum) {
            return "This budget can go up to every " + maximum
                    + " days, so two releases still fit.";
        }
        return null;
    }

    public static void validate(int durationDays, int intervalDays) {
        String problem = rejection(durationDays, intervalDays);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
    }

    /**
     * Whole days only. Fractions, blanks, and values that are not numbers return -1.
     */
    public static int readIntervalDays(Map<String, Object> conditions) {
        if (conditions == null) {
            return -1;
        }
        return readIntervalDays(conditions.get("intervalDays"));
    }

    public static int readIntervalDays(Object raw) {
        if (raw == null) {
            return -1;
        }
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)) {
                return -1;
            }
            if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
                return -1;
            }
            return number.intValue();
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return -1;
        }
        try {
            BigDecimal parsed = new BigDecimal(text);
            if (parsed.stripTrailingZeros().scale() > 0) {
                return -1;
            }
            return parsed.intValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            return -1;
        }
    }

    public static LocalTime readTime(Map<String, Object> conditions) {
        if (conditions == null || conditions.get("disbursementTime") == null) {
            return LocalTime.of(8, 0);
        }
        try {
            return LocalTime.parse(conditions.get("disbursementTime").toString());
        } catch (DateTimeException ex) {
            return LocalTime.of(8, 0);
        }
    }

    public static boolean alreadyReleasedThisWindow(LocalDate lastRelease, LocalDate today, int intervalDays) {
        return alreadyReleasedThisWindow(lastRelease, today, intervalDays, null);
    }

    /**
     * The open slice lasts until the next grid morning.
     * A missed run that paid off the grid does not push that morning back.
     * {@code gridOrigin} is the stored anchor, or the budget start when the gap
     * has not been edited. A null origin keeps the older "days since the last
     * release" check.
     */
    public static boolean alreadyReleasedThisWindow(
            LocalDate lastRelease,
            LocalDate today,
            int intervalDays,
            LocalDate gridOrigin
    ) {
        if (lastRelease == null || today == null || intervalDays < 1) {
            return false;
        }
        if (!today.isAfter(lastRelease)) {
            return true;
        }
        LocalDate origin = gridOrigin != null ? gridOrigin : lastRelease;
        LocalDate next = nextGridDateStrictlyAfter(origin, lastRelease, intervalDays);
        if (next == null) {
            return true;
        }
        return today.isBefore(next);
    }

    /**
     * ISO-8601 date stored when the gap is edited. Missing or unreadable values
     * mean "count from the budget start".
     */
    public static LocalDate readAnchorDate(Map<String, Object> conditions) {
        if (conditions == null || conditions.get("intervalAnchorDate") == null) {
            return null;
        }
        return readAnchorDate(conditions.get("intervalAnchorDate"));
    }

    public static LocalDate readAnchorDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate date) {
            return date;
        }
        String text = raw.toString().trim();
        if (text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (DateTimeException ex) {
            return null;
        }
    }

    /**
     * Grid mornings from the first one on or after {@code from} through {@code end}.
     */
    public static int releaseCountOnGrid(LocalDate origin, LocalDate from, LocalDate end, int intervalDays) {
        if (origin == null || from == null || end == null || intervalDays < 1 || end.isBefore(from)) {
            return 0;
        }
        LocalDate day = firstGridOnOrAfter(origin, from, intervalDays);
        if (day == null || day.isAfter(end)) {
            return 0;
        }
        long span = ChronoUnit.DAYS.between(day, end);
        return (int) (span / intervalDays) + 1;
    }

    public static boolean isOnGrid(LocalDate origin, LocalDate day, int intervalDays) {
        if (origin == null || day == null || intervalDays < 1 || day.isBefore(origin)) {
            return false;
        }
        return ChronoUnit.DAYS.between(origin, day) % intervalDays == 0;
    }

    /**
     * Next release strictly after {@code now} and strictly after the last release date.
     * The grid starts on the budget start date. A missed morning stays on that grid
     * and schedules only the next future slot. {@code anchorDate} is set when the
     * gap itself is edited, so the new rhythm starts from the last release.
     */
    public static LocalDateTime nextDisbursement(
            LocalDate budgetStart,
            LocalDate budgetEnd,
            LocalDateTime lastDisbursedAt,
            LocalDateTime now,
            LocalTime time,
            int intervalDays
    ) {
        return nextDisbursement(budgetStart, budgetEnd, lastDisbursedAt, now, time, intervalDays, null);
    }

    public static LocalDateTime nextDisbursement(
            LocalDate budgetStart,
            LocalDate budgetEnd,
            LocalDateTime lastDisbursedAt,
            LocalDateTime now,
            LocalTime time,
            int intervalDays,
            LocalDate anchorDate
    ) {
        if (budgetStart == null || budgetEnd == null || now == null || time == null || intervalDays < 1) {
            return null;
        }
        LocalDate origin = anchorDate != null ? anchorDate : budgetStart;
        if (origin.isAfter(budgetEnd)) {
            return null;
        }
        LocalDate lastDate = lastDisbursedAt == null ? null : lastDisbursedAt.toLocalDate();
        LocalDate day = origin.isBefore(budgetStart)
                ? firstGridOnOrAfter(origin, budgetStart, intervalDays)
                : origin;
        if (day == null) {
            return null;
        }
        int guard = 0;
        while (guard++ < 10000) {
            if (day.isAfter(budgetEnd)) {
                return null;
            }
            LocalDateTime candidate = day.atTime(time);
            boolean afterNow = candidate.isAfter(now);
            boolean afterLast = lastDate == null || day.isAfter(lastDate);
            if (afterNow && afterLast) {
                return candidate;
            }
            day = day.plusDays(intervalDays);
        }
        return null;
    }

    private static LocalDate firstGridOnOrAfter(LocalDate origin, LocalDate from, int intervalDays) {
        if (origin == null || from == null || intervalDays < 1) {
            return null;
        }
        if (!origin.isBefore(from)) {
            return origin;
        }
        long gap = ChronoUnit.DAYS.between(origin, from);
        long steps = (gap + intervalDays - 1) / intervalDays;
        return origin.plusDays(steps * intervalDays);
    }

    private static LocalDate nextGridDateStrictlyAfter(LocalDate origin, LocalDate after, int intervalDays) {
        if (origin == null || after == null || intervalDays < 1) {
            return null;
        }
        if (origin.isAfter(after)) {
            return origin;
        }
        long between = ChronoUnit.DAYS.between(origin, after);
        long steps = between / intervalDays + 1;
        return origin.plusDays(steps * intervalDays);
    }

    public static boolean isLastRelease(LocalDate releaseDate, LocalDate budgetEnd, int intervalDays) {
        if (releaseDate == null || budgetEnd == null || intervalDays < 1) {
            return true;
        }
        return releaseDate.plusDays(intervalDays).isAfter(budgetEnd);
    }

    public static String successMessage(
            String amount,
            String envelopeName,
            boolean lastRelease,
            String nextAmount,
            int intervalDays
    ) {
        String name = envelopeName == null || envelopeName.isBlank() ? "Envelope" : envelopeName;
        String shown = amount == null ? "0.00" : amount;
        if (lastRelease) {
            return "\u20A6" + shown + " has been unlocked in your '" + name
                    + "' envelope. This is the last release for this budget.";
        }
        String following = nextAmount == null ? shown : nextAmount;
        String unit = intervalDays == 1 ? "day" : "days";
        return "\u20A6" + shown + " has been unlocked in your '" + name
                + "' envelope. The next \u20A6" + following + " arrives in "
                + intervalDays + " " + unit + ".";
    }
}
