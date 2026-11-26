package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {
    Optional<SubscriptionPlan> findByPlanNameIgnoreCase(String planName);
    List<SubscriptionPlan> findByActiveTrue();
}
