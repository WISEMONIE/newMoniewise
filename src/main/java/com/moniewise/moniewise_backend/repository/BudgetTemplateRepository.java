package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.BudgetTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetTemplateRepository extends JpaRepository<BudgetTemplate, Long> {

    List<BudgetTemplate> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<BudgetTemplate> findByIdAndUserId(Long id, Long userId);
}
