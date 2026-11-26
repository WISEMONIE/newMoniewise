package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.TierProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TierProfileRepository extends JpaRepository<TierProfile, Long> {

    Optional<TierProfile> findByTierCode(String tierCode);
}