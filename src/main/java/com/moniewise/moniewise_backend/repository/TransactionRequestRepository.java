package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.TransactionRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionRequestRepository extends JpaRepository<TransactionRequest, Long> {

    Optional<TransactionRequest> findByClientReference(String clientReference);
}