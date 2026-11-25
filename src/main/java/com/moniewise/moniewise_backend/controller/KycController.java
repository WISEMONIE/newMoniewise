package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.KycProfileRequestDto;
import com.moniewise.moniewise_backend.dto.KycProfileResponseDto;
import com.moniewise.moniewise_backend.dto.request.BvnVerifyRequest;
import com.moniewise.moniewise_backend.dto.response.BvnVerificationResultDto;
import com.moniewise.moniewise_backend.service.KycService;
import com.moniewise.moniewise_backend.service.UserService;
import com.moniewise.moniewise_backend.service.WalletOpeningService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/kyc")
public class KycController {

    private final KycService kycService;
    private final UserService userService;
    private final WalletOpeningService walletOpeningService;

    public KycController(KycService kycService,
                         UserService userService,
                         WalletOpeningService walletOpeningService) {
        this.kycService = kycService;
        this.userService = userService;
        this.walletOpeningService = walletOpeningService;
    }

    /**
     * Returns whether the authenticated user's KYC is fully verified.
     *
     * <p>GET /api/kyc/status
     */
    @GetMapping("/status")
    public ResponseEntity<Boolean> getKycStatus(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userService.getRequiredUserIdByEmail(userDetails.getUsername());
        return ResponseEntity.ok(kycService.isUserVerified(userId));
    }

    /**
     * Creates or updates the user's KYC profile (source of funds, source of
     * wealth, BVN stored locally).  Does NOT call SecureWave.
     *
     * <p>POST /api/kyc/profile
     */
    @PostMapping("/profile")
    public ResponseEntity<KycProfileResponseDto> createOrUpdateProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody KycProfileRequestDto request) {
        Long userId = userService.getRequiredUserIdByEmail(userDetails.getUsername());
        KycProfileResponseDto response = kycService.createOrUpdateProfile(userId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Verifies the user's BVN in real-time via SecureWave, persists all
     * returned identity data, and marks the profile as VERIFIED.
     *
     * <p>POST /api/kyc/bvn/verify
     *
     * <p>Request body:
     * <pre>
     * {
     *   "bvn": "22435553718"
     * }
     * </pre>
     *
     * <p>Response: full {@link BvnVerificationResultDto} containing personal
     * info, residential info, enrolment details, and watchlist status.
     */
    @PostMapping("/bvn/verify")
    public ResponseEntity<BvnVerificationResultDto> verifyBvn(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody BvnVerifyRequest request) {
        Long userId = userService.getRequiredUserIdByEmail(userDetails.getUsername());
        // email + phone come from the authenticated user's record. The client
        // sends the BVN, and the name fields only when the fallback needs them.
        BvnVerificationResultDto result = kycService.verifyBvnWithProvider(
                userId,
                request.getBvn(),
                request.getFirstName(),
                request.getLastName(),
                request.getDateOfBirth());
        // A verified BVN opens the account in this same request, so the app
        // closing cannot leave the user without one. Saving the profile never
        // opens an account. A bank failure does not fail the check: the
        // "wallet" field tells the Open Wallet screen what happened, with a
        // customer-safe reason and whether "Try again" can help.
        result.setWallet(walletOpeningService.openAfterBvnCheck(userId).toResponse());
        return ResponseEntity.ok(result);
    }
}
