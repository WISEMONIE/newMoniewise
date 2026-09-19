package com.moniewise.moniewise_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Calculates the Wisemonie markup fee charged to a user on every external transfer.
 *
 * <h3>Fee model</h3>
 * <p>A single <strong>flat fee</strong> applies to every transfer regardless of amount,
 * read from {@code system_config} key {@code transfer.markup.flat_fee} (default ₦2.25).
 *
 * <p>Users on a premium plan with {@code UNLIMITED_TRANSFERS} pay zero markup.
 * The PSP cost (Rubies' own NIP fee) is still borne by Wisemonie in that case.
 *
 * <p>The pre-confirmation screen should call {@link #buildBreakdown} and display
 * the result to the user before they confirm the transfer.
 */
@Service
public class MarkupCalculatorService {

    private static final Logger logger = LoggerFactory.getLogger(MarkupCalculatorService.class);

    private final SystemConfigService systemConfig;
    private final PremiumFeatureService premiumFeatureService;

    public MarkupCalculatorService(SystemConfigService systemConfig,
                                   PremiumFeatureService premiumFeatureService) {
        this.systemConfig           = systemConfig;
        this.premiumFeatureService  = premiumFeatureService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the markup fee for a transfer, respecting premium waivers.
     *
     * @param amount  the transfer amount in NGN
     * @param userId  the ID of the user (null = no premium check)
     * @return markup fee in NGN, or ZERO if user has UNLIMITED_TRANSFERS
     */
    public BigDecimal calculateMarkup(BigDecimal amount, Long userId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        if (userId != null && premiumFeatureService.hasFeature(userId, "UNLIMITED_TRANSFERS")) {
            logger.debug("[Markup] User {} has UNLIMITED_TRANSFERS — markup waived", userId);
            return BigDecimal.ZERO;
        }

        return tierFee(amount);
    }

    /**
     * Calculates markup without user context (e.g. pre-authentication preview).
     */
    public BigDecimal calculateMarkup(BigDecimal amount) {
        return calculateMarkup(amount, null);
    }

    /**
     * Builds the full fee breakdown for the pre-confirmation screen.
     *
     * <p>Includes the NIBSS NIP bank charge (automatically deducted by Rubies)
     * AND the Moniewise markup fee so the user sees the full cost up-front.
     *
     * <p>Frontend should display: {@code breakdown.displayText()}
     * e.g. "Send ₦5,000.00 · Tranx fee ₦13.00 · Total ₦5,013.00"
     *
     * <p><strong>Revenue rule:</strong> only {@code markupFee} enters the Moniewise
     * revenue wallet. {@code bankCharge} goes to Rubies/NIBSS automatically — we
     * never collect it.
     */
    public FeeBreakdown buildBreakdown(BigDecimal transferAmount, Long userId) {
        BigDecimal markup    = calculateMarkup(transferAmount, userId);
        BigDecimal nipFee    = calculateNipFee(transferAmount);
        BigDecimal stampDuty = calculateStampDuty(transferAmount);

        if (stampDuty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal minCombined = stampDuty;
            BigDecimal combined = nipFee.add(markup);
            if (combined.compareTo(minCombined) < 0) {
                markup = minCombined.subtract(nipFee);
            }
        }

        BigDecimal total     = transferAmount.add(nipFee).add(markup).add(stampDuty);
        return new FeeBreakdown(transferAmount, nipFee, markup, stampDuty, total);
    }

    /** Flat charge applied to an account-closure withdrawal, whatever the amount. */
    private static final BigDecimal CLOSURE_FLAT_FEE = new BigDecimal("100");

    /**
     * Account-closure fee: a single flat ₦100 charge regardless of amount. The
     * NIBSS NIP fee for the amount being sent is paid <em>out of</em> that flat
     * charge and whatever remains is Moniewise revenue — so the user's total
     * debit is exactly {@code amount + 100} and the wallet can land on zero.
     *
     * <p>Deliberately separate from {@link #buildBreakdown}: every ordinary
     * transfer keeps the tiered-NIP + flat-markup pricing, untouched.
     */
    public FeeBreakdown buildClosureBreakdown(BigDecimal sendAmount) {
        BigDecimal nipFee = calculateNipFee(sendAmount);
        BigDecimal stampDuty = calculateStampDuty(sendAmount);
        // If NIP ever exceeded the flat charge, Moniewise takes nothing rather
        // than letting the markup go negative.
        BigDecimal markup = CLOSURE_FLAT_FEE.subtract(nipFee).max(BigDecimal.ZERO);
        return new FeeBreakdown(sendAmount, nipFee, markup, stampDuty,
                sendAmount.add(nipFee).add(markup).add(stampDuty));
    }

    /**
     * Calculates the NIBSS NIP interbank fee for this transfer amount.
     *
     * <p>This fee is charged by Rubies at the BaaS level when a transfer is
     * initiated — it is NOT Moniewise revenue. Defaults follow the NIBSS
     * published schedule; all thresholds and amounts are configurable via
     * {@code system_config}.
     *
     * <ul>
     *   <li>≤ ₦5,000   → ₦10.75</li>
     *   <li>≤ ₦50,000  → ₦26.88</li>
     *   <li>> ₦50,000  → ₦53.75</li>
     * </ul>
     */
    public BigDecimal calculateNipFee(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        BigDecimal tier1Max = systemConfig.getBigDecimal(SystemConfigService.NIP_TIER1_MAX, new BigDecimal("5000"));
        BigDecimal tier2Max = systemConfig.getBigDecimal(SystemConfigService.NIP_TIER2_MAX, new BigDecimal("50000"));
        BigDecimal tier1Fee = systemConfig.getBigDecimal(SystemConfigService.NIP_TIER1_FEE, new BigDecimal("10.75"));
        BigDecimal tier2Fee = systemConfig.getBigDecimal(SystemConfigService.NIP_TIER2_FEE, new BigDecimal("26.88"));
        BigDecimal tier3Fee = systemConfig.getBigDecimal(SystemConfigService.NIP_TIER3_FEE, new BigDecimal("53.75"));

        if (amount.compareTo(tier1Max) <= 0) return tier1Fee;
        if (amount.compareTo(tier2Max) <= 0) return tier2Fee;
        return tier3Fee;
    }

    /**
     * Nigerian stamp duty: ₦50 flat charge on electronic transfers above ₦10,000.
     * Both threshold and amount are configurable via {@code system_config}.
     */
    public BigDecimal calculateStampDuty(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        BigDecimal threshold = systemConfig.getBigDecimal(
                SystemConfigService.STAMP_DUTY_THRESHOLD, new BigDecimal("10000"));
        BigDecimal duty = systemConfig.getBigDecimal(
                SystemConfigService.STAMP_DUTY_AMOUNT, new BigDecimal("50"));

        return amount.compareTo(threshold) > 0 ? duty : BigDecimal.ZERO;
    }

    // ── Fee resolution ────────────────────────────────────────────────────────

    /** Returns the flat markup fee — same value for every transfer amount. */
    private BigDecimal tierFee(BigDecimal amount) {
        return systemConfig.getBigDecimal(SystemConfigService.MARKUP_FLAT_FEE, new BigDecimal("2.25"));
    }

    // ── Value object ──────────────────────────────────────────────────────────

    /**
     * Immutable fee breakdown returned to controllers and sent to the frontend
     * before the user confirms a transfer.
     *
     * <ul>
     *   <li>{@code bankCharge}  — NIBSS NIP fee charged by Rubies. Goes to the bank.
     *       Moniewise does NOT collect this.</li>
     *   <li>{@code markupFee}   — Moniewise revenue fee. Transferred to the Moniewise
     *       Rubies revenue wallet after the transfer succeeds.</li>
     *   <li>{@code totalFromEnvelope} — {@code transferAmount + bankCharge + markupFee}</li>
     * </ul>
     */
    public record FeeBreakdown(
            BigDecimal transferAmount,
            BigDecimal bankCharge,
            BigDecimal markupFee,
            BigDecimal stampDuty,
            BigDecimal totalFromEnvelope
    ) {
        /** Human-readable summary for the pre-confirmation screen. */
        public String displayText() {
            BigDecimal totalFee = bankCharge.add(markupFee).add(stampDuty);
            if (totalFee.compareTo(BigDecimal.ZERO) > 0) {
                String stampNote = stampDuty.compareTo(BigDecimal.ZERO) > 0
                        ? String.format(" (incl. ₦%,.0f stamp duty)", stampDuty)
                        : "";
                return String.format(
                        "Send ₦%,.2f · Tranx fee ₦%,.2f%s · Total ₦%,.2f",
                        transferAmount, totalFee, stampNote, totalFromEnvelope);
            }
            return String.format("Send ₦%,.2f · No fee · Total ₦%,.2f",
                    transferAmount, totalFromEnvelope);
        }
    }
}
