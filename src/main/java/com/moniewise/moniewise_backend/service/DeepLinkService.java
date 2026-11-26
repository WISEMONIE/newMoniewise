package com.moniewise.moniewise_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeepLinkService {

    @Value("${moniewise.deep-links.base-url:${app.base-url:http://localhost:9000}}")
    private String baseUrl;

    @Value("${moniewise.deep-links.open-path-prefix:/open}")
    private String openPathPrefix;

    @Value("${moniewise.deep-links.android.package-name:com.wisemonie}")
    private String androidPackageName;

    @Value("${moniewise.deep-links.android.sha256-cert-fingerprints:}")
    private String androidSha256CertFingerprints;

    @Value("${moniewise.deep-links.android.play-store-url:https://play.google.com/store/apps/details?id=com.wisemonie}")
    private String androidPlayStoreUrl;

    @Value("${moniewise.deep-links.ios.team-id:}")
    private String iosTeamId;

    @Value("${moniewise.deep-links.ios.bundle-id:}")
    private String iosBundleId;

    @Value("${moniewise.deep-links.ios.app-store-url:}")
    private String iosAppStoreUrl;

    @Value("${moniewise.deep-links.web-fallback-url:${app.base-url:http://localhost:9000}}")
    private String webFallbackUrl;

    public String toDeepLink(String appRoute) {
        String normalizedRoute = normalizeAppRoute(appRoute);
        int queryIndex = normalizedRoute.indexOf('?');
        String routePath = queryIndex >= 0 ? normalizedRoute.substring(0, queryIndex) : normalizedRoute;
        String query = queryIndex >= 0 ? normalizedRoute.substring(queryIndex) : "";

        String prefix = normalizePrefix(openPathPrefix);
        String path = "/".equals(routePath) ? prefix : prefix + routePath;
        return trimTrailingSlash(baseUrl) + path + query;
    }

    public String normalizeAppRoute(String value) {
        if (value == null || value.isBlank()) {
            return "/";
        }

        String route = value.trim();
        try {
            URI uri = URI.create(route);
            if (uri.isAbsolute()) {
                String path = uri.getRawPath();
                String query = uri.getRawQuery();
                route = (path == null || path.isBlank()) ? "/" : path;
                if (query != null && !query.isBlank()) {
                    route = route + "?" + query;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Keep the original text and normalize it below.
        }

        if (route.startsWith(normalizePrefix(openPathPrefix) + "/")) {
            route = route.substring(normalizePrefix(openPathPrefix).length());
        } else if (route.equals(normalizePrefix(openPathPrefix))) {
            route = "/";
        }

        return route.startsWith("/") ? route : "/" + route;
    }

    public List<Map<String, Object>> androidAssetLinks() {
        List<String> fingerprints = splitCsv(androidSha256CertFingerprints);
        if (isBlank(androidPackageName) || fingerprints.isEmpty()) {
            return List.of();
        }

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("namespace", "android_app");
        target.put("package_name", androidPackageName.trim());
        target.put("sha256_cert_fingerprints", fingerprints);

        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("relation", List.of("delegate_permission/common.handle_all_urls"));
        statement.put("target", target);
        return List.of(statement);
    }

    public Map<String, Object> appleAppSiteAssociation() {
        Map<String, Object> applinks = new LinkedHashMap<>();
        applinks.put("apps", List.of());

        String appId = appleAppId();
        if (appId == null) {
            applinks.put("details", List.of());
            return Map.of("applinks", applinks);
        }

        String prefix = normalizePrefix(openPathPrefix);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("appID", appId);
        detail.put("paths", List.of(prefix, prefix + "/*"));
        applinks.put("details", List.of(detail));

        return Map.of("applinks", applinks);
    }

    public String fallbackHtml(String requestedRoute, String userAgent) {
        String appRoute = normalizeAppRoute(requestedRoute);
        String deepLink = toDeepLink(appRoute);
        String androidUrl = androidInstallUrl(appRoute);
        String iosUrl = iosAppStoreUrl();
        String webUrl = webFallbackUrl(appRoute);

        String primaryStoreUrl = preferredStoreUrl(userAgent, androidUrl, iosUrl);

        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Open Wisemonie</title>
                  <style>
                    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif; background: #F2F4F7; color: #101828; }
                    main { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
                    .panel { width: 100%; max-width: 480px; background: #fff; border: 1px solid #E4E7EC; border-radius: 12px; padding: 28px; text-align: center; }
                    img { height: 44px; width: auto; margin-bottom: 24px; }
                    h1 { font-size: 24px; line-height: 1.25; margin: 0 0 10px; color: #0F4C4C; }
                    p { font-size: 15px; line-height: 1.65; margin: 0 0 22px; color: #475467; }
                    a { display: block; border-radius: 8px; padding: 14px 18px; margin-top: 10px; text-decoration: none; font-weight: 700; }
                    .primary { background: #0F4C4C; color: #fff; }
                    .secondary { background: #F9FAFB; color: #0F4C4C; border: 1px solid #E4E7EC; }
                    .small { margin-top: 18px; font-size: 12px; color: #98A2B3; }
                  </style>
                </head>
                <body>
                  <main>
                    <section class="panel">
                      <img src="/images/wisemonie-logo.png" alt="Wisemonie">
                      <h1>Open this in Wisemonie</h1>
                      <p>If the app is installed, this link opens the right screen. If not, install Wisemonie and come back to continue.</p>
                      <a class="primary" href="%s">Open Wisemonie</a>
                      <a class="secondary" href="%s">Install Wisemonie</a>
                      %s
                      <a class="secondary" href="%s">Continue on web</a>
                      <div class="small">Destination: %s</div>
                    </section>
                  </main>
                </body>
                </html>
                """.formatted(
                escapeHtml(deepLink),
                escapeHtml(primaryStoreUrl),
                iosUrl == null ? "" : "<a class=\"secondary\" href=\"" + escapeHtml(iosUrl) + "\">Get the iPhone app</a>",
                escapeHtml(webUrl),
                escapeHtml(appRoute)
        );
    }

    public String androidPlayStoreUrl() {
        return isBlank(androidPlayStoreUrl) ? "https://play.google.com/store/apps/details?id=com.wisemonie" : androidPlayStoreUrl.trim();
    }

    public String iosAppStoreUrl() {
        return isBlank(iosAppStoreUrl) ? null : iosAppStoreUrl.trim();
    }

    private String androidInstallUrl(String appRoute) {
        String deepLink = toDeepLink(appRoute);
        String referrer = "deep_link=" + deepLink;
        return appendQueryParam(androidPlayStoreUrl(), "referrer", referrer);
    }

    private String webFallbackUrl(String appRoute) {
        String fallback = trimTrailingSlash(webFallbackUrl);
        String normalizedRoute = normalizeAppRoute(appRoute);
        return "/".equals(normalizedRoute) ? fallback : fallback + normalizedRoute;
    }

    private String preferredStoreUrl(String userAgent, String androidUrl, String iosUrl) {
        String ua = userAgent == null ? "" : userAgent.toLowerCase();
        if ((ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) && iosUrl != null) {
            return iosUrl;
        }
        return androidUrl;
    }

    private String appleAppId() {
        if (isBlank(iosTeamId) || isBlank(iosBundleId)) {
            return null;
        }
        return iosTeamId.trim() + "." + iosBundleId.trim();
    }

    private String appendQueryParam(String url, String key, String value) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private List<String> splitCsv(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .forEach(result::add);
        return result;
    }

    private String normalizePrefix(String value) {
        if (isBlank(value)) {
            return "/open";
        }
        String prefix = value.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.length() > 1 && prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    private String trimTrailingSlash(String value) {
        String trimmed = isBlank(value) ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
