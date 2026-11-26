package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.TransactionDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionDecisionRepository extends JpaRepository<TransactionDecision, Long> {

    Optional<TransactionDecision> findByTransactionRequestId(Long transactionRequestId);
}