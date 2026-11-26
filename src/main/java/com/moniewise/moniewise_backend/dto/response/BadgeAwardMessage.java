package com.moniewise.moniewise_backend.dto.response;

public record BadgeAwardMessage(
        String type,
        UserBadgeResponse badge
) {
}
