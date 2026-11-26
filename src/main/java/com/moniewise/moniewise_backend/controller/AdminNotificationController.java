package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.AdminBroadcastRequest;
import com.moniewise.moniewise_backend.dto.response.AdminBroadcastResponse;
import com.moniewise.moniewise_backend.service.AdminBroadcastService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/admin/notifications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationController {

    private final AdminBroadcastService adminBroadcastService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.moniewise.moniewise_backend.service.BlogService blogService;

    public AdminNotificationController(AdminBroadcastService adminBroadcastService) {
        this.adminBroadcastService = adminBroadcastService;
    }

    @PostMapping("/service-outage")
    public ResponseEntity<AdminBroadcastResponse> sendServiceOutage(
            @Valid @RequestBody AdminBroadcastRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(adminBroadcastService.sendServiceOutage(
                request,
                adminEmail(authentication)
        ));
    }

    @PostMapping("/maintenance")
    public ResponseEntity<AdminBroadcastResponse> sendMaintenance(
            @Valid @RequestBody AdminBroadcastRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(adminBroadcastService.sendMaintenance(
                request,
                adminEmail(authentication)
        ));
    }

    @PostMapping("/special")
    public ResponseEntity<AdminBroadcastResponse> sendSpecial(
            @Valid @RequestBody AdminBroadcastRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(adminBroadcastService.sendSpecial(
                request,
                adminEmail(authentication)
        ));
    }

    /** Upload a push picture (JPG or PNG, up to 1 MB). Returns its public link. */
    @PostMapping(value = "/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<java.util.Map<String, Object>> uploadImage(
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ResponseEntity.ok(blogService.uploadPushImage(file));
    }

    /** How many people "all users" reaches, and how many of them can get a push. */
    @org.springframework.web.bind.annotation.GetMapping("/audience")
    public java.util.Map<String, Object> audience(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean includeTestAccounts) {
        return adminBroadcastService.audienceSize(includeTestAccounts);
    }

    /** What reached a phone, what is waiting or failed, per recent broadcast; and whether the queue moves. */
    @org.springframework.web.bind.annotation.GetMapping("/delivery")
    public java.util.Map<String, Object> delivery() {
        return adminBroadcastService.delivery();
    }

    /** The last 100 broadcasts, newest first. */
    @org.springframework.web.bind.annotation.GetMapping("/history")
    public java.util.List<com.moniewise.moniewise_backend.entity.AdminBroadcast> history() {
        return adminBroadcastService.history();
    }

    private String adminEmail(Authentication authentication) {
        return authentication != null ? authentication.getName() : "unknown-admin";
    }
}
