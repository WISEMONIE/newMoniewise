package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.PayeelordDataPlan;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes the cost/selling/margin split for Payeelord airtime and data
 * purchases — the VAS analogue of {@link MarkupCalculatorService}.
 *
 * <p><strong>Same philosophy as {@code MarkupCalculatorService}: never hardcode.</strong>
 * Every rate and markup is read from {@code system_config} (airtime) or the
 * {@code payeelord_data_plans} catalog (data) at call time, so admins can tune
 * margins without a redeploy.
 *
 * <h3>Airtime — wholesale-discount economics ("Reading A", confirmed by Payeelord)</h3>
 * Payeelord charges our float {@code amount × discount_rate} (they quoted ~97.5%,
 * i.e. a 2.5% wholesale discount in our favour) while we sell — per product
 * decision ("option a") — at face value. So for a ₦1,000 purchase:
 * <pre>
 *   faceAmount    = ₦1,000                         (delivered to the recipient)
 *   costAmount    = ₦1,000 × 0.975 = ₦975          (charged to our Payeelord float)
 *   sellingAmount = ₦1,000 + flatMarkup (def. ₦0)  (charged to the user's wallet)
 *   marginAmount  = sellingAmount − costAmount = ₦25 (+ flatMarkup)
 * </pre>
 * Both {@code discount_rate} and the optional flat {@code markup_amount} are
 * admin-tunable via {@code system_config} — see {@link SystemConfigService#PAYEELORD_AIRTIME_DISCOUNT_RATE}
 * and {@link SystemConfigService#PAYEELORD_AIRTIME_MARKUP_AMOUNT}.
 *
 * <h3>Data — catalog-driven economics</h3>
 * Data plans have no documented wholesale discount; the catalog's {@code cost_price}
 * IS what Payeelord charges our float (kept fresh by the periodic sync job), and
 * {@code markup_amount} is the admin-set margin on top:
 * <pre>
 *   faceAmount    = costAmount = plan.costPrice          (Payeelord's catalog price)
 *   sellingAmount = plan.costPrice + plan.markupAmount   (charged to the user's wallet)
 *   marginAmount  = plan.markupAmount
 * </pre>
 */
@Service
public class PayeelordPricingService {

    /** Default wholesale discount if not yet configured — matches Payeelord's quoted rate (2.5% off). */
    private static final BigDecimal DEFAULT_AIRTIME_DISCOUNT_RATE = new BigDecimal("0.975");

    private final SystemConfigService systemConfig;

    public PayeelordPricingService(SystemConfigService systemConfig) {
        this.systemConfig = systemConfig;
    }

    // ── Airtime ───────────────────────────────────────────────────────────────

    /**
     * Prices an airtime purchase of the given face amount.
     *
     * @param requestedAmount the airtime value the user wants delivered (₦10–₦5,000)
     */
    public VasPricing priceAirtime(BigDecimal requestedAmount) {
        if (requestedAmount == null || requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Airtime amount must be positive");
        }

        BigDecimal discountRate = systemConfig.getBigDecimal(
                SystemConfigService.PAYEELORD_AIRTIME_DISCOUNT_RATE, DEFAULT_AIRTIME_DISCOUNT_RATE);
        BigDecimal flatMarkup = systemConfig.getBigDecimal(
                SystemConfigService.PAYEELORD_AIRTIME_MARKUP_AMOUNT, BigDecimal.ZERO);

        BigDecimal faceAmount = requestedAmount.setScale(2, RoundingMode.HALF_UP);
        // Payeelord charges our float `amount × discount_rate` — round to kobo.
        BigDecimal costAmount = faceAmount.multiply(discountRate).setScale(2, RoundingMode.HALF_UP);
        // We sell at face value (product decision "option a"), plus any optional flat markup.
        BigDecimal sellingAmount = faceAmount.add(flatMarkup).setScale(2, RoundingMode.HALF_UP);
        BigDecimal marginAmount = sellingAmount.subtract(costAmount).setScale(2, RoundingMode.HALF_UP);

        return new VasPricing(faceAmount, costAmount, sellingAmount, marginAmount);
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    /**
     * Prices a data-bundle purchase from a catalog row.
     *
     * <p>{@code plan.costPrice} is owned by the periodic scraper and reflects
     * Payeelord's live price; {@code plan.markupAmount} is admin-owned and never
     * touched by the sync job — see {@link PayeelordDataPlan} column-ownership notes.
     */
    public VasPricing priceDataPlan(PayeelordDataPlan plan) {
        if (plan == null || plan.getCostPrice() == null) {
            throw new IllegalArgumentException("Data plan must have a cost price set");
        }

        BigDecimal costAmount = plan.getCostPrice().setScale(2, RoundingMode.HALF_UP);
        // Add 10% margin then round UP to the nearest ₦100.
        // e.g. ₦80→₦100, ₦750→₦900, ₦1,000→₦1,100, ₦100,000→₦110,000.
        BigDecimal hundred = new BigDecimal("100");
        BigDecimal sellingAmount = costAmount
                .multiply(new BigDecimal("1.1"))
                .divide(hundred, 0, RoundingMode.CEILING)
                .multiply(hundred)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal marginAmount = sellingAmount.subtract(costAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal faceAmount = costAmount;

        return new VasPricing(faceAmount, costAmount, sellingAmount, marginAmount);
    }

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * The cost/selling/margin breakdown for a single VAS purchase — maps 1:1 onto
     * {@code PayeelordVasTransaction}'s {@code faceAmount/costAmount/sellingAmount/marginAmount}
     * columns.
     */
    public record VasPricing(
            BigDecimal faceAmount,
            BigDecimal costAmount,
            BigDecimal sellingAmount,
            BigDecimal marginAmount
    ) {
        /** Human-readable summary for logs / admin views. */
        public String displayText() {
            return String.format(
                    "Face ₦%,.2f · Cost ₦%,.2f · Selling ₦%,.2f · Margin ₦%,.2f",
                    faceAmount, costAmount, sellingAmount, marginAmount);
        }
    }
}
