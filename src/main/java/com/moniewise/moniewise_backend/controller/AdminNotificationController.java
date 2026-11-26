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

    private String adminEmail(Authentication authentication) {
        return authentication != null ? authentication.getName() : "unknown-admin";
    }
}
