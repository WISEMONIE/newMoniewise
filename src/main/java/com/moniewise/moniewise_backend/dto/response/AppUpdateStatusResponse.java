package com.moniewise.moniewise_backend.dto.response;

public record AppUpdateStatusResponse(
        String platform,
        String currentVersion,
        Integer currentBuild,
        String minimumVersion,
        Integer minimumBuild,
        String latestVersion,
        Integer latestBuild,
        boolean updateRequired,
        boolean updateAvailable,
        String updateType,
        boolean canContinue,
        String title,
        String message,
        String storeUrl
) {
}
