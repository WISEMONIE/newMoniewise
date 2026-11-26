package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.UserSummaryResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.BeneficiaryService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/beneficiaries")
public class BeneficiaryController {

    @Autowired private BeneficiaryService beneficiaryService;
    @Autowired private UserService userService;
    @Autowired private AbuseProtectionService abuseProtectionService;

    /**
     * POST /beneficiaries
     * Rate-limited — prevents bulk beneficiary harvesting/spam.
     * 10 additions per hour per user+IP.
     */
    @PostMapping
    public ResponseEntity<?> addBeneficiary(
            Authentication authentication,
            @RequestBody Map<String, String> payload,
            HttpServletRequest httpRequest) {

        String email = authentication.getName();
        String throttleKey = abuseProtectionService.buildKey(email, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.BENEFICIARY_ADD, throttleKey);

        try {
            User user = userService.findByEmail(email);
            beneficiaryService.addBeneficiary(user.getId(), payload.get("email"), payload.get("alias"));
            abuseProtectionService.recordSuccess(AbuseProtectionService.BENEFICIARY_ADD, throttleKey);
            return ResponseEntity.ok("Beneficiary saved successfully");
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.BENEFICIARY_ADD, throttleKey);
            throw e;
        }
    }

    @GetMapping
    public ResponseEntity<List<UserSummaryResponse>> getMyBeneficiaries(Authentication authentication) {
        String email = authentication.getName();
        User user = userService.findByEmail(email);
        return ResponseEntity.ok(beneficiaryService.getMyBeneficiaries(user.getId()));
    }
}
