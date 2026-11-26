package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.AdminBudgetCancellation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminBudgetCancellationRepository extends JpaRepository<AdminBudgetCancellation, Long> {

    List<AdminBudgetCancellation> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
}
