package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.response.AppUpdateStatusResponse;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AppUpdateService {

    private static final String DEFAULT_REQUIRED_TITLE = "Update required";
    private static final String DEFAULT_REQUIRED_MESSAGE =
            "Please update Wisemonie to continue. This version includes important fixes for notifications and account safety.";
    private static final String DEFAULT_OPTIONAL_TITLE = "Update available";
    private static final String DEFAULT_OPTIONAL_MESSAGE =
            "A newer Wisemonie version is available with improvements and fixes.";

    private final SystemConfigService systemConfig;

    public AppUpdateService(SystemConfigService systemConfig) {
        this.systemConfig = systemConfig;
    }

    public AppUpdateStatusResponse checkUpdate(String platform, String version, String build) {
        String normalizedPlatform = normalizePlatform(platform);
        PlatformConfig config = configFor(normalizedPlatform);
        String currentVersion = clean(version);
        Integer currentBuild = parsePositiveInt(build);
        boolean enabled = systemConfig.getBoolean(SystemConfigService.APP_UPDATE_ENABLED, true);

        boolean updateRequired = enabled && isBehind(
                currentVersion,
                currentBuild,
                config.minimumVersion(),
                config.minimumBuild());

        boolean updateAvailable = updateRequired || (enabled && isBehind(
                currentVersion,
                currentBuild,
                config.latestVersion(),
                config.latestBuild()));

        String updateType = updateRequired ? "IMMEDIATE" : (updateAvailable ? "FLEXIBLE" : "NONE");
        String title = updateRequired
                ? systemConfig.getString(SystemConfigService.APP_UPDATE_REQUIRED_TITLE, DEFAULT_REQUIRED_TITLE)
                : updateAvailable
                    ? systemConfig.getString(SystemConfigService.APP_UPDATE_OPTIONAL_TITLE, DEFAULT_OPTIONAL_TITLE)
                    : null;
        String message = updateRequired
                ? systemConfig.getString(SystemConfigService.APP_UPDATE_REQUIRED_MESSAGE, DEFAULT_REQUIRED_MESSAGE)
                : updateAvailable
                    ? systemConfig.getString(SystemConfigService.APP_UPDATE_OPTIONAL_MESSAGE, DEFAULT_OPTIONAL_MESSAGE)
                    : null;

        return new AppUpdateStatusResponse(
                normalizedPlatform,
                currentVersion,
                currentBuild,
                clean(config.minimumVersion()),
                nullableBuild(config.minimumBuild()),
                clean(config.latestVersion()),
                nullableBuild(config.latestBuild()),
                updateRequired,
                updateAvailable,
                updateType,
                !updateRequired,
                title,
                message,
                config.storeUrl()
        );
    }

    private PlatformConfig configFor(String platform) {
        if ("IOS".equals(platform)) {
            return new PlatformConfig(
                    systemConfig.getString(SystemConfigService.APP_UPDATE_IOS_MIN_VERSION, "0.0.0"),
                    systemConfig.getInt(SystemConfigService.APP_UPDATE_IOS_MIN_BUILD, 0),
                    systemConfig.getString(SystemConfigService.APP_UPDATE_IOS_LATEST_VERSION, "0.0.0"),
                    systemConfig.getInt(SystemConfigService.APP_UPDATE_IOS_LATEST_BUILD, 0),
                    clean(systemConfig.getString(SystemConfigService.APP_UPDATE_IOS_STORE_URL, ""))
            );
        }

        return new PlatformConfig(
                systemConfig.getString(SystemConfigService.APP_UPDATE_ANDROID_MIN_VERSION, "0.0.0"),
                systemConfig.getInt(SystemConfigService.APP_UPDATE_ANDROID_MIN_BUILD, 0),
                systemConfig.getString(SystemConfigService.APP_UPDATE_ANDROID_LATEST_VERSION, "0.0.0"),
                systemConfig.getInt(SystemConfigService.APP_UPDATE_ANDROID_LATEST_BUILD, 0),
                clean(systemConfig.getString(SystemConfigService.APP_UPDATE_ANDROID_STORE_URL, ""))
        );
    }

    private boolean isBehind(String currentVersion, Integer currentBuild, String targetVersion, int targetBuild) {
        if (currentBuild != null && targetBuild > 0) {
            return currentBuild < targetBuild;
        }
        return isSemanticVersionBehind(currentVersion, targetVersion);
    }

    private boolean isSemanticVersionBehind(String currentVersion, String targetVersion) {
        if (isBlank(currentVersion) || isBlank(targetVersion) || "0.0.0".equals(clean(targetVersion))) {
            return false;
        }
        return compareSemanticVersions(currentVersion, targetVersion) < 0;
    }

    private int compareSemanticVersions(String left, String right) {
        int[] a = versionParts(left);
        int[] b = versionParts(right);
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            int leftPart = i < a.length ? a[i] : 0;
            int rightPart = i < b.length ? b[i] : 0;
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return 0;
    }

    private int[] versionParts(String version) {
        String cleaned = clean(version);
        if (isBlank(cleaned)) {
            return new int[] {0};
        }

        String[] rawParts = cleaned.split("\\.");
        int[] parts = new int[rawParts.length];
        for (int i = 0; i < rawParts.length; i++) {
            parts[i] = parseNumericPrefix(rawParts[i]);
        }
        return parts;
    }

    private int parseNumericPrefix(String raw) {
        if (raw == null) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isDigit(c)) {
                break;
            }
            digits.append(c);
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Integer parsePositiveInt(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer nullableBuild(int build) {
        return build > 0 ? build : null;
    }

    private String normalizePlatform(String platform) {
        if (isBlank(platform)) {
            return "ANDROID";
        }
        String value = platform.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "IOS", "IPHONE", "IPAD", "IPADOS" -> "IOS";
            default -> "ANDROID";
        };
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        int buildSeparator = cleaned.indexOf('+');
        if (buildSeparator >= 0) {
            cleaned = cleaned.substring(0, buildSeparator);
        }
        return cleaned;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record PlatformConfig(
            String minimumVersion,
            int minimumBuild,
            String latestVersion,
            int latestBuild,
            String storeUrl
    ) {
    }
}
