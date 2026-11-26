package com.moniewise.moniewise_backend.utils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * BVN providers return dates of birth in several shapes ("Mar 19, 1998",
 * "19-Mar-1998", "1998-03-19"). Rubies only accepts YYYY-MM-DD.
 */
public final class DateOfBirth {

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            pattern("MMM d, uuuu"),
            pattern("MMMM d, uuuu"),
            pattern("d-MMM-uuuu"),
            pattern("d MMM uuuu"),
            pattern("d MMMM uuuu"),
            pattern("dd-MM-uuuu"),
            pattern("dd/MM/uuuu"),
            pattern("uuuu/MM/dd"));

    private DateOfBirth() {
    }

    /** The date as YYYY-MM-DD, or the trimmed input when no format fits. */
    public static String toIso(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return text;
        }
        // "1998-03-19T00:00:00" and similar carry a time part.
        if (text.length() > 10 && text.charAt(4) == '-' && text.charAt(10) == 'T') {
            text = text.substring(0, 10);
        }
        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDate.parse(text, format).toString();
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return text;
    }

    private static DateTimeFormatter pattern(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }
}
