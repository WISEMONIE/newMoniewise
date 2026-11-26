package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.service.DeepLinkService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
public class DeepLinkController {

    private final DeepLinkService deepLinkService;

    public DeepLinkController(DeepLinkService deepLinkService) {
        this.deepLinkService = deepLinkService;
    }

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> androidAssetLinks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(deepLinkService.androidAssetLinks());
    }

    @GetMapping(
            value = {"/.well-known/apple-app-site-association", "/apple-app-site-association"},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> appleAppSiteAssociation() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(deepLinkService.appleAppSiteAssociation());
    }

    @GetMapping(value = {"/open", "/open/", "/open/**"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> openFallback(HttpServletRequest request) {
        String route = extractRoute(request);
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            route = route + "?" + query;
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deepLinkService.fallbackHtml(route, request.getHeader("User-Agent")));
    }

    private String extractRoute(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isBlank() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        if (uri.equals("/open") || uri.equals("/open/")) {
            return "/";
        }
        if (uri.startsWith("/open/")) {
            return uri.substring("/open".length());
        }
        return "/";
    }
}
