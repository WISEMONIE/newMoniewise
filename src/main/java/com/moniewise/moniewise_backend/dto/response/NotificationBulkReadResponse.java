package com.moniewise.moniewise_backend.dto.response;

public record NotificationBulkReadResponse(
        int updatedCount,
        long unreadCount
) {
}
