package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.enums.BadgeAwardSourceType;

import java.time.Instant;

public record UserBadgeResponse(
        Long userBadgeId,
        Long badgeId,
        String badgeCode,
        String badgeName,
        String badgeDescription,
        String badgeCategory,
        String iconUrl,
        BadgeAwardSourceType sourceType,
        Long sourceId,
        String title,
        String message,
        String shareTitle,
        String shareMessage,
        String shareImagePath,
        String deepLink,
        Instant earnedAt,
        Instant seenAt,
        boolean seen,
        boolean shareable
) {
}
