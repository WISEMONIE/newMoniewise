package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.ReconciliationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, Long> {

    List<ReconciliationItem> findByStatus(String status);

    List<ReconciliationItem> findByReconciliationRunIdAndStatusIn(Long reconciliationRunId, List<String> statuses);

    @Query("select item from ReconciliationItem item where item.status <> 'RESOLVED'")
    List<ReconciliationItem> findByResolvedFalse();
}
