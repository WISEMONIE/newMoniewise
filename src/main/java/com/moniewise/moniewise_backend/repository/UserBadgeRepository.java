package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.UserBadge;
import com.moniewise.moniewise_backend.enums.BadgeAwardSourceType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserBadgeRepository extends JpaRepository<UserBadge, Long> {
    List<UserBadge> findByUserId(Long userId);

    @Query("""
            SELECT ub FROM UserBadge ub
            JOIN FETCH ub.badge
            WHERE ub.user.id = :userId
            ORDER BY ub.earnedAt DESC
            """)
    List<UserBadge> findByUserIdWithBadgeOrderByEarnedAtDesc(@Param("userId") Long userId);

    @Query("""
            SELECT ub FROM UserBadge ub
            JOIN FETCH ub.badge
            WHERE ub.user.id = :userId
              AND ub.seenAt IS NULL
            ORDER BY ub.earnedAt DESC
            """)
    List<UserBadge> findUnseenByUserIdWithBadgeOrderByEarnedAtDesc(@Param("userId") Long userId);

    @Query("""
            SELECT ub FROM UserBadge ub
            JOIN FETCH ub.badge b
            WHERE ub.user.id = :userId
              AND b.code = :badgeCode
              AND ub.sourceType = :sourceType
              AND ub.sourceId = :sourceId
            """)
    Optional<UserBadge> findAward(
            @Param("userId") Long userId,
            @Param("badgeCode") String badgeCode,
            @Param("sourceType") BadgeAwardSourceType sourceType,
            @Param("sourceId") Long sourceId);

    Optional<UserBadge> findByIdAndUserId(Long id, Long userId);
}
