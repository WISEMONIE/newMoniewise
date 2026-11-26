package com.moniewise.moniewise_backend.aml;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compares the name on the sending account with the customer's BVN name
 * (Rubies guide, guardrail 3). Order and initials do not matter, so
 * "MADU EMMANUEL C." matches Emmanuel Chinedu Madu.
 */
public final class SenderNameMatcher {

    public enum Result {
        /** First and last name both appear: probably the customer's own money. */
        MATCH,
        /** Only one of them appears: a person should look. */
        PARTIAL,
        /** Neither appears: someone else's money. */
        MISMATCH,
        /** The sender is a business, not a person. */
        COMPANY,
        /** No sender name, or no customer name to compare with. */
        UNKNOWN
    }

    private static final Set<String> TITLES = Set.of(
            "MR", "MRS", "MS", "MISS", "DR", "PROF", "CHIEF", "ALHAJI", "ALHAJA",
            "PASTOR", "ENGR", "BARR", "SIR", "MADAM", "HAJIA");

    private static final Set<String> COMPANY_WORDS = Set.of(
            "LTD", "LIMITED", "PLC", "ENTERPRISE", "ENTERPRISES", "VENTURE", "VENTURES",
            "NIG", "NIGERIA", "GLOBAL", "INTERNATIONAL", "INTL", "COMPANY", "CO", "INC",
            "SERVICES", "SERVICE", "SOLUTIONS", "TECHNOLOGIES", "TECHNOLOGY", "GROUP",
            "HOLDINGS", "CONCEPTS", "LOGISTICS", "TRADING", "INVESTMENT", "INVESTMENTS",
            "RESOURCES", "AGENCY", "STORES", "MERCHANT", "BUREAU", "BDC", "MFB", "BANK",
            "FINANCE", "CAPITAL", "PAY", "WALLET", "TECH", "HUB");

    private SenderNameMatcher() {
    }

    public static Result match(String senderName, String firstName, String middleName, String lastName) {
        List<String> sender = tokens(senderName);
        List<String> first = tokens(firstName);
        List<String> last = tokens(lastName);
        if (sender.isEmpty() || (first.isEmpty() && last.isEmpty())) {
            return Result.UNKNOWN;
        }
        for (String word : sender) {
            if (COMPANY_WORDS.contains(word)) {
                return Result.COMPANY;
            }
        }
        Set<String> senderWords = new LinkedHashSet<>(sender);
        boolean firstFound = !first.isEmpty() && senderWords.containsAll(first);
        boolean lastFound = !last.isEmpty() && senderWords.containsAll(last);
        if (firstFound && lastFound) {
            return Result.MATCH;
        }
        if (firstFound || lastFound) {
            return Result.PARTIAL;
        }
        // The middle name with the first or last still says "related", not "same".
        List<String> middle = tokens(middleName);
        if (!middle.isEmpty() && senderWords.containsAll(middle)) {
            return Result.PARTIAL;
        }
        return Result.MISMATCH;
    }

    /** Upper-case words of two letters or more, accents and punctuation removed, titles dropped. */
    static List<String> tokens(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) {
            return out;
        }
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z]+", " ")
                .trim();
        if (plain.isEmpty()) {
            return out;
        }
        for (String word : plain.split(" ")) {
            if (word.length() >= 2 && !TITLES.contains(word)) {
                out.add(word);
            }
        }
        return out;
    }
}
