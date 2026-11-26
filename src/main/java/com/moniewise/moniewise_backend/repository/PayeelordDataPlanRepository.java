package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.PayeelordDataPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayeelordDataPlanRepository extends JpaRepository<PayeelordDataPlan, Long> {

    Optional<PayeelordDataPlan> findByDataId(String dataId);

    List<PayeelordDataPlan> findByActiveTrueOrderByNetworkNameAscCostPriceAsc();

    List<PayeelordDataPlan> findByNetworkIdAndActiveTrueOrderByCostPriceAsc(String networkId);
}
