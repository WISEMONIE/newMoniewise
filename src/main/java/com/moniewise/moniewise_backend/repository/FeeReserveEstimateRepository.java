package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.FeeReserveEstimate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface FeeReserveEstimateRepository extends JpaRepository<FeeReserveEstimate, Long> {

    List<FeeReserveEstimate> findByFeeReserveIdAndActiveTrue(Long feeReserveId);

    List<FeeReserveEstimate> findByBudgetIdAndActiveTrue(Long budgetId);

    @Query("SELECT COALESCE(SUM(e.estimatedTotal), 0) FROM FeeReserveEstimate e WHERE e.budgetId = :budgetId AND e.active = true")
    BigDecimal sumEstimatedTotalByBudgetId(Long budgetId);

    @Modifying
    @Query("UPDATE FeeReserveEstimate e SET e.active = false WHERE e.budgetId = :budgetId")
    void deactivateByBudgetId(Long budgetId);
}
