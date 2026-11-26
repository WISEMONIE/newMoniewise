package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.TransferFeeConfig;
import com.moniewise.moniewise_backend.enums.TransferFeeTransferType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
@Repository
public interface TransferFeeConfigRepository extends JpaRepository<TransferFeeConfig, Long> {

    Optional<TransferFeeConfig> findFirstByProviderNameIgnoreCaseAndTransferTypeAndActiveTrue(
            String providerName,
            TransferFeeTransferType transferType
    );
}