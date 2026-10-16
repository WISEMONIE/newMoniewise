package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.VasPayable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface VasPayableRepository extends JpaRepository<VasPayable, Long> {

    Optional<VasPayable> findByPurchaseReference(String purchaseReference);

    /**
     * Wholesale cost still sitting in the revenue account: every completed
     * Rubies collection whose cost has not been marked sent to PayeeLord.
     * Collections that pre-date this table are included, because they have
     * no swept row.
     */
    @Query(value = """
            SELECT COALESCE(SUM(v.cost_amount), 0)
            FROM payeelord_vas_transactions v
            JOIN transaction_logs t
              ON t.reference = 'REV-' || v.reference
            WHERE t.transaction_type = 'vas_payment_collection'
              AND t.status = 'COMPLETED'
              AND NOT EXISTS (
                    SELECT 1 FROM vas_payables p
                    WHERE p.purchase_reference = v.reference
                      AND p.swept = TRUE
              )
            """, nativeQuery = true)
    BigDecimal sumOutstandingCost();

    /**
     * Markup whose cash is in the Rubies account and whose naira has not been
     * added to the revenue wallet. A {@code vas_markup} log written before the
     * collection completed does not count as credited.
     */
    @Query(value = """
            SELECT COALESCE(SUM(v.margin_amount), 0)
            FROM payeelord_vas_transactions v
            JOIN transaction_logs t
              ON t.reference = 'REV-' || v.reference
            WHERE t.transaction_type = 'vas_payment_collection'
              AND t.status = 'COMPLETED'
              AND v.margin_amount > 0
              AND NOT EXISTS (
                    SELECT 1 FROM vas_payables p
                    WHERE p.purchase_reference = v.reference
                      AND p.margin_credited = TRUE
              )
            """, nativeQuery = true)
    BigDecimal sumMarginNotCredited();
}
