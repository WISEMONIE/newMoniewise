package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.EngagementSpecialOccasion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface EngagementSpecialOccasionRepository extends JpaRepository<EngagementSpecialOccasion, Long> {

    List<EngagementSpecialOccasion> findByOccasionDateAndActiveTrue(LocalDate occasionDate);
}
