package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.FeeReserveTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeeReserveTransactionRepository extends JpaRepository<FeeReserveTransaction, Long> {

    List<FeeReserveTransaction> findByFeeReserveIdOrderByCreatedAtDesc(Long feeReserveId);
}
