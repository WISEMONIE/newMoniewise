package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.BudgetEngagementNudge;
import com.moniewise.moniewise_backend.enums.BudgetEngagementNudgeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface BudgetEngagementNudgeRepository extends JpaRepository<BudgetEngagementNudge, Long> {

    Optional<BudgetEngagementNudge> findByUserIdAndNudgeType(Long userId, BudgetEngagementNudgeType nudgeType);

    Optional<BudgetEngagementNudge> findFirstByUserIdOrderByLastSentAtDesc(Long userId);

    boolean existsByUserIdAndLastSentAtBetween(
            Long userId,
            LocalDateTime startInclusive,
            LocalDateTime endExclusive);
}
