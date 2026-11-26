package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.ReferralReward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferralRewardRepository extends JpaRepository<ReferralReward, Long> {

    boolean existsByReferredUserId(Long referredUserId);

    boolean existsByReferrerUserIdAndStatus(Long referrerUserId, String status);

    long countByReferrerUserIdAndStatus(Long referrerUserId, String status);

    Optional<ReferralReward> findFirstByReferrerUserIdAndStatusOrderByIdAsc(
            Long referrerUserId, String status);

    List<ReferralReward> findByReferrerUserIdAndStatus(Long referrerUserId, String status);
}
