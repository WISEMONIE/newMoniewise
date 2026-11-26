package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.RevenueLog;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RevenueLogRepository extends JpaRepository<RevenueLog, Long> {

    boolean existsByDescriptionContaining(String fragment);

}