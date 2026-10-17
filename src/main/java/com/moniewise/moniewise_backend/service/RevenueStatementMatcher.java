package com.moniewise.moniewise_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies one revenue-account statement against the books.
 * This class does not read Rubies and does not change a balance.
 */
public final class RevenueStatementMatcher {

    static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.01");
    private static final int MAX_REFS = 8;
    private static final Pattern OUR_REF = Pattern.compile(
            "(?i)\\b(REV-[A-Z0-9][A-Z0-9_-]{0,60}|WALLET-OPEN-\\d{1,12}|KYC-BVN-\\d{1,12})\\b");
    private static final Pattern WALLET_OPEN = Pattern.compile("(?i)^WALLET-OPEN-(\\d{1,12})$");
    private static final Pattern KYC_BVN = Pattern.compile("(?i)^KYC-BVN-(\\d{1,12})$");
    private static final DateTimeFormatter SPACE_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY_MONTH_YEAR =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter SLASH_DAY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SLASH_YEAR =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private RevenueStatementMatcher() {
    }

    public enum Outcome {
        MATCHED_VAS,
        MATCHED_FEE,
        MATCHED_OPENING_FEE,
        MATCHED_OTHER,
        MISSING_VAS_BOOKING,
        MISSING_OPENING_FEE,
        VAS_AMOUNT_DIFFERS,
        OPENING_FEE_AMOUNT_DIFFERS,
        OPENING_FEE_UNATTRIBUTED,
        UNMATCHED,
        SKIPPED_NOT_FINAL,
        UNPARSEABLE
    }

    public enum Direction {
        CREDIT, DEBIT, UNKNOWN
    }

    public record Purchase(
            String reference,
            Long userId,
            BigDecimal selling,
            BigDecimal cost,
            BigDecimal margin,
            boolean marginCredited
    ) {
    }

    public record ExpectedMovement(String reference) {
    }

    public interface Lookup {
        boolean transactionReferenceExists(String reference);

        boolean revenueMentionExists(String reference);

        Optional<Purchase> successfulPurchase(String purchaseReference);

        boolean openingFeeRecorded(long userId);

        List<ExpectedMovement> completedExpected();

        List<ExpectedMovement> pendingExpected();
    }

    public record Line(
            List<String> references,
            BigDecimal amount,
            Direction direction,
            LocalDate occurredOn,
            boolean posted
    ) {
    }

    public record Classified(
            Line line,
            Outcome outcome,
            String keyReference,
            boolean healable,
            Purchase purchase,
            Long openingFeeUserId
    ) {
    }

    public record Report(
            List<Classified> lines,
            List<String> missingOnRubies,
            List<String> pendingNotOnStatement
    ) {
    }

    public static Line parse(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return new Line(List.of(), null, Direction.UNKNOWN, null, false);
        }
        String code = firstString(raw, "responseCode", "response_code");
        boolean posted = code == null || code.isBlank() || "00".equals(code.trim());
        BigDecimal amount = firstAmount(raw, "amount", "transactionAmount", "creditAmount", "paymentAmount", "debitAmount");
        Direction direction = direction(firstString(raw, "drCr", "dr_cr", "debitCredit", "creditDebitIndicator"));
        LocalDate occurredOn = parseDate(firstString(raw,
                "transactionDate", "transactionDateTime", "createdAt", "created_at", "date", "tranDate"));
        List<String> references = references(raw);
        return new Line(references, amount, direction, occurredOn, posted);
    }

    public static Report classify(List<Line> lines, Lookup lookup, BigDecimal openingFee, LocalDate healAfter) {
        List<Line> safeLines = lines == null ? List.of() : lines;
        Lookup safeLookup = lookup == null ? emptyLookup() : lookup;
        BigDecimal fee = money(openingFee);
        List<Classified> classified = new ArrayList<>();
        for (Line line : safeLines) {
            classified.add(classifyOne(line, safeLookup, fee, healAfter));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Classified row : classified) {
            if (row.line() == null || !row.line().posted()) {
                continue;
            }
            for (String reference : row.line().references()) {
                seen.add(reference.toUpperCase(Locale.ROOT));
            }
        }
        return new Report(
                List.copyOf(classified),
                missing(safeLookup.completedExpected(), seen),
                missing(safeLookup.pendingExpected(), seen));
    }

    /**
     * @return the date to store as the cursor, or null when the cursor must stay
     *         where it is because a healable line has no date
     */
    public static LocalDate nextCursor(LocalDate windowEnd, List<Classified> lines) {
        LocalDate block = null;
        for (Classified row : lines) {
            if (!row.healable()) {
                continue;
            }
            LocalDate occurredOn = row.line().occurredOn();
            if (occurredOn == null) {
                return null;
            }
            if (block == null || occurredOn.isBefore(block)) {
                block = occurredOn;
            }
        }
        return block == null ? windowEnd : block;
    }

    private static Classified classifyOne(Line line, Lookup lookup, BigDecimal openingFee, LocalDate healAfter) {
        if (line == null) {
            return new Classified(new Line(List.of(), null, Direction.UNKNOWN, null, false),
                    Outcome.UNPARSEABLE, null, false, null, null);
        }
        if (!line.posted()) {
            return done(line, Outcome.SKIPPED_NOT_FINAL, key(line), false, null, null);
        }
        if (line.amount() == null || line.direction() == Direction.UNKNOWN) {
            return done(line, Outcome.UNPARSEABLE, key(line), false, null, null);
        }
        if (line.direction() == Direction.CREDIT) {
            return classifyCredit(line, lookup, healAfter);
        }
        return classifyDebit(line, lookup, openingFee, healAfter);
    }

    private static Classified classifyCredit(Line line, Lookup lookup, LocalDate healAfter) {
        Purchase purchase = findPurchase(line, lookup);
        if (purchase != null) {
            if (!sameMoney(line.amount(), purchase.selling())) {
                return done(line, Outcome.VAS_AMOUNT_DIFFERS, purchase.reference(), false, purchase, null);
            }
            if (purchase.marginCredited() || lookup.revenueMentionExists(purchase.reference())) {
                return done(line, Outcome.MATCHED_VAS, purchase.reference(), false, purchase, null);
            }
            return done(line, Outcome.MISSING_VAS_BOOKING, purchase.reference(),
                    healable(line, healAfter), purchase, null);
        }
        if (anyTransaction(line, lookup) || anyRevenueMention(line, lookup)) {
            return done(line, Outcome.MATCHED_FEE, key(line), false, null, null);
        }
        return done(line, Outcome.UNMATCHED, key(line), false, null, null);
    }

    private static Classified classifyDebit(Line line, Lookup lookup, BigDecimal openingFee, LocalDate healAfter) {
        Long userId = openingFeeUser(line);
        if (userId != null) {
            if (lookup.openingFeeRecorded(userId) || anyTransaction(line, lookup)) {
                return done(line, Outcome.MATCHED_OPENING_FEE, "WALLET-OPEN-" + userId, false, null, userId);
            }
            if (sameMoney(line.amount(), openingFee)) {
                return done(line, Outcome.MISSING_OPENING_FEE, "WALLET-OPEN-" + userId,
                        healable(line, healAfter), null, userId);
            }
            return done(line, Outcome.OPENING_FEE_AMOUNT_DIFFERS, "WALLET-OPEN-" + userId, false, null, userId);
        }
        if (sameMoney(line.amount(), openingFee) && openingFee.signum() > 0) {
            return done(line, Outcome.OPENING_FEE_UNATTRIBUTED, key(line), false, null, null);
        }
        if (anyTransaction(line, lookup)) {
            return done(line, Outcome.MATCHED_OTHER, key(line), false, null, null);
        }
        return done(line, Outcome.UNMATCHED, key(line), false, null, null);
    }

    private static boolean healable(Line line, LocalDate healAfter) {
        return line.occurredOn() != null
                && healAfter != null
                && !line.occurredOn().isBefore(healAfter);
    }

    private static Purchase findPurchase(Line line, Lookup lookup) {
        for (String reference : line.references()) {
            if (!isPurchaseCandidate(reference)) {
                continue;
            }
            Optional<Purchase> found = lookup.successfulPurchase(stripRev(reference));
            if (found != null && found.isPresent()) {
                return found.get();
            }
        }
        return null;
    }

    private static boolean isPurchaseCandidate(String reference) {
        return reference.regionMatches(true, 0, "REV-", 0, 4)
                || reference.regionMatches(true, 0, "VAS-", 0, 4);
    }

    static String stripRev(String reference) {
        if (reference != null && reference.regionMatches(true, 0, "REV-", 0, 4)) {
            return reference.substring(4);
        }
        return reference;
    }

    private static Long openingFeeUser(Line line) {
        for (String reference : line.references()) {
            Matcher open = WALLET_OPEN.matcher(reference);
            if (open.matches()) {
                return Long.valueOf(open.group(1));
            }
            Matcher bvn = KYC_BVN.matcher(reference);
            if (bvn.matches()) {
                return Long.valueOf(bvn.group(1));
            }
        }
        return null;
    }

    private static boolean anyTransaction(Line line, Lookup lookup) {
        for (String reference : line.references()) {
            if (lookup.transactionReferenceExists(reference)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyRevenueMention(Line line, Lookup lookup) {
        for (String reference : line.references()) {
            if (reference.regionMatches(true, 0, "REV-", 0, 4)
                    || reference.regionMatches(true, 0, "VAS-", 0, 4)) {
                if (lookup.revenueMentionExists(stripRev(reference))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> missing(List<ExpectedMovement> expected, Set<String> seen) {
        if (expected == null || expected.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (ExpectedMovement movement : expected) {
            if (movement == null || movement.reference() == null || movement.reference().isBlank()) {
                continue;
            }
            if (!seen.contains(movement.reference().toUpperCase(Locale.ROOT))) {
                missing.add(movement.reference());
            }
        }
        return List.copyOf(missing);
    }

    private static Classified done(Line line, Outcome outcome, String key, boolean healable,
                                   Purchase purchase, Long userId) {
        return new Classified(line, outcome, key, healable, purchase, userId);
    }

    private static String key(Line line) {
        return line.references().isEmpty() ? null : line.references().get(0);
    }

    private static Lookup emptyLookup() {
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
                return Optional.empty();
            }

            @Override
            public boolean openingFeeRecorded(long userId) {
                return false;
            }

            @Override
            public List<ExpectedMovement> completedExpected() {
                return List.of();
            }

            @Override
            public List<ExpectedMovement> pendingExpected() {
                return List.of();
            }
        };
    }

    static List<String> references(Map<String, Object> raw) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        addExact(refs, firstString(raw, "paymentReference", "transactionReference", "reference", "sessionId"));
        String narration = firstString(raw, "narration", "remark", "description", "paymentDescription");
        harvest(refs, narration);
        for (String field : List.of("paymentReference", "transactionReference", "reference", "sessionId")) {
            harvest(refs, firstString(raw, field));
        }
        if (refs.size() <= MAX_REFS) {
            return List.copyOf(refs);
        }
        return List.copyOf(new ArrayList<>(refs).subList(0, MAX_REFS));
    }

    private static void addExact(Set<String> refs, String value) {
        if (value == null || value.isBlank() || value.length() > 80 || value.indexOf(' ') >= 0) {
            return;
        }
        refs.add(value.trim());
    }

    private static void harvest(Set<String> refs, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = OUR_REF.matcher(text);
        while (matcher.find() && refs.size() < MAX_REFS) {
            refs.add(matcher.group(1).toUpperCase(Locale.ROOT));
        }
    }

    private static Direction direction(String raw) {
        if (raw == null || raw.isBlank()) {
            return Direction.UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("CR".equals(normalized) || "CREDIT".equals(normalized) || normalized.contains("CREDIT")) {
            return Direction.CREDIT;
        }
        if ("DR".equals(normalized) || "DEBIT".equals(normalized) || normalized.contains("DEBIT")) {
            return Direction.DEBIT;
        }
        return Direction.UNKNOWN;
    }

    private static String firstString(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static BigDecimal firstAmount(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            BigDecimal parsed = parseAmount(raw.get(key));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static BigDecimal parseAmount(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String cleaned = value.toString()
                    .replace(",", "")
                    .replace("NGN", "")
                    .replace("\u20A6", "")
                    .trim();
            if (cleaned.isBlank()) {
                return null;
            }
            return new BigDecimal(cleaned).abs().setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).toLocalDate();
        } catch (DateTimeParseException ignored) {
            // Try the next shape.
        }
        try {
            return LocalDateTime.parse(text).toLocalDate();
        } catch (DateTimeParseException ignored) {
            // Try the next shape.
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ignored) {
            // Try the next shape.
        }
        for (DateTimeFormatter formatter : List.of(SPACE_DATE_TIME, DAY_MONTH_YEAR, SLASH_DAY, SLASH_YEAR)) {
            try {
                if (formatter == SPACE_DATE_TIME) {
                    return LocalDateTime.parse(text, formatter).toLocalDate();
                }
                return LocalDate.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next shape.
            }
        }
        return null;
    }

    private static boolean sameMoney(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return false;
        }
        return left.subtract(right).abs().compareTo(MONEY_TOLERANCE) <= 0;
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
