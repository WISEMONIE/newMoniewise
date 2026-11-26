package com.moniewise.moniewise_backend.dto.response;

import java.util.List;

public record AdminBroadcastResponse(
        boolean status,
        String type,
        int recipients,
        int fcmQueued,
        int fcmDeliveryAttempts,
        int emailsQueued,
        boolean deliverFcmNow,
        List<String> skippedTargets,
        String message
) {
}
