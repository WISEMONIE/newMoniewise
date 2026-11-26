package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.KycProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KycProfileRepository extends JpaRepository<KycProfile, Long> {

    // Safe: returns oldest row — never throws NonUniqueResultException even if
    // a duplicate KYC profile slipped in (which the service now prevents, but
    // this is a safety net for any row already in the DB).
    Optional<KycProfile> findFirstByUserIdOrderByCreatedAtAsc(Long userId);

    // Cross-user BVN uniqueness: does ANY *other* user already own this BVN?
    boolean existsByBvnAndUserIdNot(String bvn, Long userId);

    // Convenience — kept for any call site that checks own-user BVN ownership
    boolean existsByBvn(String bvn);
}