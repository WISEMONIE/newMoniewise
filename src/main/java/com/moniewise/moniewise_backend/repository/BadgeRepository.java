package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Badge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BadgeRepository extends JpaRepository<Badge, Long> {
    List<Badge> findByActiveTrue();
    Optional<Badge> findByCode(String code);
}
