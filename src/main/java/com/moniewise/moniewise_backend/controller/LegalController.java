package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.AcceptLegalDocumentRequest;
import com.moniewise.moniewise_backend.dto.request.UpdateLegalDocumentRequest;
import com.moniewise.moniewise_backend.dto.response.AcceptLegalDocumentResponse;
import com.moniewise.moniewise_backend.dto.response.LegalAcceptanceStatusResponse;
import com.moniewise.moniewise_backend.dto.response.LegalDocumentResponse;
import com.moniewise.moniewise_backend.enums.LegalDocumentType;
import com.moniewise.moniewise_backend.service.LegalDocumentService;
import com.moniewise.moniewise_backend.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/legal")
public class LegalController {

    private static final Logger logger = LoggerFactory.getLogger(LegalController.class);

    private final LegalDocumentService service;
    private final UserService userService;

    public LegalController(
            LegalDocumentService service,
            UserService userService
    ) {
        this.service = service;
        this.userService = userService;
    }

    @GetMapping("/privacy-policy")
    public ResponseEntity<LegalDocumentResponse> getPrivacyPolicy() {
        return ResponseEntity.ok(
                service.getActiveDocument(LegalDocumentType.PRIVACY_POLICY)
        );
    }

    @GetMapping("/terms")
    public ResponseEntity<LegalDocumentResponse> getTerms() {
        return ResponseEntity.ok(
                service.getActiveDocument(LegalDocumentType.TERMS_AND_CONDITIONS)
        );
    }

    @GetMapping("/acceptance-status")
    public ResponseEntity<LegalAcceptanceStatusResponse> getAcceptanceStatus(
            Authentication authentication,
            @RequestParam LegalDocumentType docType
    ) {
        Long userId = getAuthenticatedUserId(authentication);

        return ResponseEntity.ok(
                service.getAcceptanceStatus(userId, docType)
        );
    }

    @PostMapping("/accept")
    public ResponseEntity<AcceptLegalDocumentResponse> acceptDocument(
            Authentication authentication,
            @Valid @RequestBody AcceptLegalDocumentRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = getAuthenticatedUserId(authentication);

        String ipAddress = extractClientIp(httpRequest);
        String deviceInfo = httpRequest.getHeader("User-Agent");

        return ResponseEntity.ok(
                service.acceptDocument(
                        userId,
                        request.legalDocumentId(),
                        request.docType(),
                        ipAddress,
                        deviceInfo
                )
        );
    }

    @PutMapping("/privacy-policy")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<LegalDocumentResponse> updatePrivacyPolicy(
            @Valid @RequestBody UpdateLegalDocumentRequest request
    ) {
        LegalDocumentResponse response = service.publishNewVersion(
                LegalDocumentType.PRIVACY_POLICY,
                "WiseMonie Privacy Policy",
                request.content(),
                request.version()
        );

        logger.info("Privacy Policy updated to version {}", request.version());

        return ResponseEntity.ok(response);
    }

    @PutMapping("/terms")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<LegalDocumentResponse> updateTerms(
            @Valid @RequestBody UpdateLegalDocumentRequest request
    ) {
        LegalDocumentResponse response = service.publishNewVersion(
                LegalDocumentType.TERMS_AND_CONDITIONS,
                "WiseMonie Terms and Conditions",
                request.content(),
                request.version()
        );

        logger.info("Terms and Conditions updated to version {}", request.version());

        return ResponseEntity.ok(response);
    }

    private Long getAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new RuntimeException("Authenticated user not found");
        }

        String email = authentication.getName();
        return userService.getRequiredUserIdByEmail(email);
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }
}