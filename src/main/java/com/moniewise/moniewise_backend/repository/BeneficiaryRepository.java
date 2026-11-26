package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {
    List<Beneficiary> findByUserId(Long userId);
    boolean existsByUserIdAndBeneficiaryUserId(Long userId, Long beneficiaryUserId);

    // ✅ OPTIMIZED: Loads Beneficiary + User Data in ONE query
    @Query("SELECT b FROM Beneficiary b JOIN FETCH b.beneficiaryUser WHERE b.user.id = :userId")
    List<Beneficiary> findByUserIdJoined(@Param("userId") Long userId);
}