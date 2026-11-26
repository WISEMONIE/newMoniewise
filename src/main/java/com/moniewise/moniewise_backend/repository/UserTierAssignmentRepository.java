package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.UserTierAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserTierAssignmentRepository extends JpaRepository<UserTierAssignment, Long> {

    Optional<UserTierAssignment> findByUserIdAndStatus(Long userId, UserTierAssignment.Status status);
}