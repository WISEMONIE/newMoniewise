package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.EngagementNudgeNotification;
import com.moniewise.moniewise_backend.enums.EngagementNudgeCampaign;
import com.moniewise.moniewise_backend.enums.EngagementNudgeChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface EngagementNudgeNotificationRepository extends JpaRepository<EngagementNudgeNotification, Long> {

    int countByUserIdAndSentDate(Long userId, LocalDate sentDate);

    int countByUserIdAndChannelAndSentAtBetween(
            Long userId,
            EngagementNudgeChannel channel,
            LocalDateTime startInclusive,
            LocalDateTime endExclusive);

    boolean existsByUserIdAndCampaignAndOccasionKeyAndChannelAndSentDate(
            Long userId,
            EngagementNudgeCampaign campaign,
            String occasionKey,
            EngagementNudgeChannel channel,
            LocalDate sentDate);

    boolean existsByUserIdAndCampaignAndOccasionKey(
            Long userId,
            EngagementNudgeCampaign campaign,
            String occasionKey);

    int countByUserIdAndCampaignAndSentAtAfter(
            Long userId,
            EngagementNudgeCampaign campaign,
            LocalDateTime sentAfter);

    @Query("""
            SELECT COUNT(DISTINCT n.sentDate)
            FROM EngagementNudgeNotification n
            WHERE n.userId = :userId
              AND n.campaign = :campaign
            """)
    long countDistinctSendDaysByUserIdAndCampaign(
            @Param("userId") Long userId,
            @Param("campaign") EngagementNudgeCampaign campaign);

    @Query("""
            SELECT n.copyKey
            FROM EngagementNudgeNotification n
            WHERE n.userId = :userId
              AND n.sentAt >= :since
            """)
    List<String> findCopyKeysSentSince(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since);
}
