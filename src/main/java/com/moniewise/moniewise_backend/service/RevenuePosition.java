package com.moniewise.moniewise_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rubies cash = revenue still in the database
 * + PayeeLord cost still in the account
 * + markup that arrived and was never added to the revenue balance
 * − wallet-opening fees Rubies took that the books missed.
 *
 * The unexplained remainder is what is left after that sum. This class does
 * not change a balance.
 */
public final class RevenuePosition {

    public static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("1.00");

    public static final String STATUS_OK = "OK";
    public static final String STATUS_MISMATCH = "MISMATCH";
    public static final String STATUS_CLASSIFIED = "CLASSIFIED";

    private RevenuePosition() {
    }

    public static Result explain(BigDecimal dbRevenue,
                                 BigDecimal unsweptCost,
                                 BigDecimal marginNotInWallet,
                                 BigDecimal missingWalletFees,
                                 long missingWalletCount,
                                 BigDecimal actualRubies,
                                 BigDecimal tolerance) {
        BigDecimal revenue = money(dbRevenue);
        BigDecimal cost = money(unsweptCost);
        BigDecimal margin = money(marginNotInWallet);
        BigDecimal missing = money(missingWalletFees);
        BigDecimal allowed = money(tolerance == null ? DEFAULT_TOLERANCE : tolerance);
        BigDecimal expected = revenue.add(cost).add(margin).subtract(missing);
        BigDecimal actual = actualRubies == null ? null : money(actualRubies);
        BigDecimal unexplained = actual == null ? null : actual.subtract(expected);
        BigDecimal rawGap = actual == null ? null : actual.subtract(revenue);

        String status;
        if (actual == null) {
            status = STATUS_CLASSIFIED;
        } else if (unexplained.abs().compareTo(allowed) <= 0) {
            status = STATUS_OK;
        } else {
            status = STATUS_MISMATCH;
        }

        String message = buildMessage(revenue, cost, margin, missing, missingWalletCount,
                expected, actual, unexplained, rawGap, status);
        return new Result(revenue, cost, margin, missingWalletCount, missing, expected,
                actual, unexplained, rawGap, status, message);
    }

    private static String buildMessage(BigDecimal revenue,
                                       BigDecimal cost,
                                       BigDecimal margin,
                                       BigDecimal missing,
                                       long missingCount,
                                       BigDecimal expected,
                                       BigDecimal actual,
                                       BigDecimal unexplained,
                                       BigDecimal rawGap,
                                       String status) {
        String books = String.format(
                "Revenue in the database ₦%s. PayeeLord cost still in the account ₦%s (available to send). "
                        + "Markup in the account and not yet in the revenue balance ₦%s. "
                        + "Wallet-opening fees with no book entry ₦%s across %d wallet(s). "
                        + "Expected Rubies ₦%s.",
                revenue.toPlainString(), cost.toPlainString(), margin.toPlainString(),
                missing.toPlainString(), missingCount, expected.toPlainString());
        if (actual == null) {
            return books + " The Rubies balance was not read on this pass.";
        }
        return String.format("%s Rubies balance ₦%s. Raw gap ₦%s. Unexplained ₦%s. Status %s.",
                books, actual.toPlainString(), rawGap.toPlainString(),
                unexplained.toPlainString(), status);
    }

    static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record Result(
            BigDecimal dbRevenue,
            BigDecimal payeelordCostOutstanding,
            BigDecimal marginNotInRevenueBalance,
            long missingWalletOpenFeeCount,
            BigDecimal missingWalletOpenFees,
            BigDecimal expectedRubies,
            BigDecimal actualRubies,
            BigDecimal unexplained,
            BigDecimal rawGap,
            String status,
            String message
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("dbBalance", dbRevenue);
            map.put("dbRevenue", dbRevenue);
            map.put("payeelordCostOutstanding", payeelordCostOutstanding);
            map.put("amountAvailableToSendToPayeelord", payeelordCostOutstanding);
            map.put("marginNotInRevenueBalance", marginNotInRevenueBalance);
            map.put("missingWalletOpenFeeCount", missingWalletOpenFeeCount);
            map.put("missingWalletOpenFees", missingWalletOpenFees);
            map.put("expectedRubies", expectedRubies);
            map.put("baasBalance", actualRubies);
            map.put("actualRubies", actualRubies);
            map.put("rawGap", rawGap);
            map.put("unexplained", unexplained);
            map.put("status", status);
            map.put("message", message);
            map.put("note", "The revenue balance is fees and markup. "
                    + "PayeeLord cost is cash still in the Rubies account. "
                    + "Missing wallet-opening fees count every Rubies wallet with no fee row, "
                    + "including wallets opened before the fee was recorded. "
                    + "This check does not change a balance and does not send money.");
            return map;
        }
    }
}
