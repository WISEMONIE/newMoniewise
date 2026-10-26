package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.*;
import com.moniewise.moniewise_backend.dto.response.*;
import com.moniewise.moniewise_backend.entity.TrustedDevice;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.exception.WalletProvisioningException;
import com.moniewise.moniewise_backend.repository.TrustedDeviceRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.security.JwtUtil;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.HowToUseWisemonieNudgeService;
import com.moniewise.moniewise_backend.service.OtpService;
import com.moniewise.moniewise_backend.service.ReferralService;
import com.moniewise.moniewise_backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ReferralService referralService;
    private final OtpService otpService;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final JwtUtil jwtUtil;
    private final AuthSessionService authSessionService;
    private final AbuseProtectionService abuseProtectionService;
    private final com.moniewise.moniewise_backend.service.NotificationService notificationService;
    private final TrustedDeviceRepository trustedDeviceRepository;
    private final HowToUseWisemonieNudgeService howToUseWisemonieNudgeService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findFirstByEmailOrderByCreatedAtAsc(email).orElseThrow(() -> new RuntimeException("User not found"));
        Wallet wallet = walletRepository.findByUser(user).orElse(null);
        return ResponseEntity.ok(new UserResponse(user, wallet));
    }

    @PostMapping("/image")
    public ResponseEntity<?> uploadProfileImage(@RequestParam("file") MultipartFile file, Authentication authentication) {
        String email = authentication.getName();
        User user = userService.findByEmail(email);
        String imageUrl = userService.uploadProfileImage(user.getId(), file);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Profile image updated successfully", "imageUrl", imageUrl));
    }

    @GetMapping("/image")
    public ResponseEntity<?> getProfileImage(Authentication authentication) {
        String email = authentication.getName();
        User user = userService.findByEmail(email);
        String imageUrl = user.getProfileImageUrl();
        if (imageUrl == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "No profile image set"));
        }
        return ResponseEntity.ok(Map.of("imageUrl", imageUrl));
    }

    @DeleteMapping("/image")
    public ResponseEntity<?> deleteProfileImage(Authentication authentication) {
        String email = authentication.getName();
        User user = userService.findByEmail(email);
        userService.deleteProfileImage(user.getId());
        return ResponseEntity.ok(Map.of("status", "success", "message", "Profile image removed"));
    }

    @PostMapping("/otp/generate")
    public ResponseEntity<OtpResponse> generateOtp(@Valid @RequestBody OtpGenerateRequest request, HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(request.getEmailOrPhone(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.OTP_GENERATE, throttleKey);
        Optional<User> userOpt = userRepository.findFirstByEmailOrderByCreatedAtAsc(request.getEmailOrPhone()).or(() -> userRepository.findByPhone(request.getEmailOrPhone()));
        if (userOpt.isPresent()) {
            // Login/device-2FA OTP — does NOT flip isVerified (that's signup state).
            otpService.generateLoginOtp(userOpt.get().getId());
        }
        abuseProtectionService.recordSuccess(AbuseProtectionService.OTP_GENERATE, throttleKey);
        return ResponseEntity.ok(new OtpResponse("If the account exists, a verification code has been sent."));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody OtpVerifyRequest request, HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(request.getEmailOrPhone(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.OTP_VERIFY, throttleKey);
        Optional<User> userOpt = userRepository.findFirstByEmailOrderByCreatedAtAsc(request.getEmailOrPhone()).or(() -> userRepository.findByPhone(request.getEmailOrPhone()));
        if (userOpt.isEmpty() || !otpService.verifyOtp(userOpt.get().getId(), request.getOtpCode())) {
            abuseProtectionService.recordFailure(AbuseProtectionService.OTP_VERIFY, throttleKey);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", HttpStatus.BAD_REQUEST.value(),
                    "error", "Invalid OTP",
                    "message", "Invalid OTP"
            ));
        }
        User user = userOpt.get();
        user.setVerified(true);
        userRepository.save(user);
        otpService.clearOtp(user.getId());

        // Trust this device so subsequent logins from it skip the OTP step
        // (first-login-per-device 2FA — see AuthController#login).
        String deviceId = httpRequest.getHeader("X-Device-Id");
        if (deviceId != null && !deviceId.isBlank()) {
            final String did = deviceId.trim();
            trustedDeviceRepository.findByUserIdAndDeviceId(user.getId(), did)
                    .ifPresentOrElse(td -> {
                        td.setLastUsedAt(LocalDateTime.now());
                        trustedDeviceRepository.save(td);
                    }, () -> {
                        TrustedDevice td = new TrustedDevice();
                        td.setUserId(user.getId());
                        td.setDeviceId(did);
                        td.setCreatedAt(LocalDateTime.now());
                        td.setLastUsedAt(LocalDateTime.now());
                        trustedDeviceRepository.save(td);
                    });
        }

        // Generate a JWT so the client can proceed directly — no second /auth/login needed.
        UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
        String sessionId = authSessionService.createSession(user);
        String token = jwtUtil.generateToken(userDetails, sessionId);
        howToUseWisemonieNudgeService.sendImmediateGuideAfterAuth(user);

        abuseProtectionService.recordSuccess(AbuseProtectionService.OTP_VERIFY, throttleKey);

        // A brand-new device just passed the OTP gate — tell the account owner.
        // Best-effort and async: the alert must never delay or fail the login.
        try {
            String email = user.getEmail();
            if (email != null && !email.isBlank()) {
                String deviceName = httpRequest.getHeader("X-Device-Name");
                if (deviceName == null || deviceName.isBlank()) {
                    deviceName = "Unrecognised device";
                }
                java.time.ZonedDateTime now =
                        java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Lagos"));
                notificationService.sendLoginAlertEmail(
                        email, greetingName(user), deviceName, clientIp(httpRequest),
                        now.format(LOGIN_ALERT_TIME), now.format(LOGIN_ALERT_DATE));
            }
        } catch (Exception ignored) {
            // Formatting/header issues must never block a successful login.
        }

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("message", "OTP verified successfully");
        response.put("expiresAt", jwtUtil.extractExpiration(token).getTime());
        response.put("needsProfileUpdate", needsProfileUpdate(user));
        return ResponseEntity.ok(response);
    }

    private static final java.time.format.DateTimeFormatter LOGIN_ALERT_TIME =
            java.time.format.DateTimeFormatter.ofPattern("h:mm:ss a '(WAT)'", java.util.Locale.ENGLISH);
    private static final java.time.format.DateTimeFormatter LOGIN_ALERT_DATE =
            java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", java.util.Locale.ENGLISH);

    /** First name for a friendly greeting, falling back to the email prefix. */
    private static String greetingName(User user) {
        String name = user.getName();
        if (name != null && !name.isBlank()) return name.trim().split("\\s+")[0];
        String email = user.getEmail();
        if (email != null && email.contains("@")) {
            String prefix = email.substring(0, email.indexOf('@'));
            if (!prefix.isEmpty()) {
                return Character.toUpperCase(prefix.charAt(0)) + prefix.substring(1);
            }
        }
        return "there";
    }

    /** Real client IP — first X-Forwarded-For hop when behind the Render proxy. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping("/referral")
    public ResponseEntity<?> referral(Authentication authentication) {
        User user = userService.findByEmail(authentication.getName());
        return ResponseEntity.ok(referralService.summary(user));
    }

    @PostMapping("/referral")
    public ResponseEntity<?> attachReferral(@RequestBody Map<String, String> body, Authentication authentication) {
        User user = userService.findByEmail(authentication.getName());
        String code = body == null ? null : body.get("code");
        boolean attached = referralService.attach(user, code);
        return ResponseEntity.ok(Map.of("attached", attached));
    }

    @PostMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody ProfileRequest request, Authentication authentication) {
        try {
            String email = authentication.getName();
            userService.updateProfile(email, request);
            return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
        } catch (WalletProvisioningException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of(
                    "status", e.getStatus().value(),
                    "code", e.getCode(),
                    "error", e.getUserMessage(),
                    "message", e.getUserMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/tnc")
    public ResponseEntity<?> acceptTnc(Authentication authentication, @RequestBody TncRequest request) {
        userService.acceptTnc(authentication.getName(), request.isAccepted());
        return ResponseEntity.ok(Map.of("message", "TnC " + (request.isAccepted() ? "accepted" : "rejected")));
    }

    @GetMapping("/search")
    public ResponseEntity<List<UserSummaryResponse>> searchUsers(
            @RequestParam String query,
            @RequestParam(required = false) String provider,
            @AuthenticationPrincipal String email,
            HttpServletRequest httpRequest) {
        if (email == null) {
            email = SecurityContextHolder.getContext().getAuthentication().getName();
        }
        String throttleKey = abuseProtectionService.buildKey(email, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.P2P_USER_SEARCH, throttleKey);
        List<UserSummaryResponse> users = userService.searchUsers(query, email, provider);
        abuseProtectionService.recordRequest(AbuseProtectionService.P2P_USER_SEARCH, throttleKey);
        return ResponseEntity.ok(users);
    }

    @PostMapping("/fcm-token")
    public ResponseEntity<?> updateFcmToken(@RequestBody Map<String, String> payload,
                                            Authentication authentication,
                                            @RequestHeader("Authorization") String authHeader,
                                            @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId) {
        try {
            String token = payload.get("token");
            if (token == null || token.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Token is required"));
            }
            String email = authentication.getName();
            String sessionId = extractSessionId(authHeader);
            String platform = payload.get("platform");
            if ((platform == null || platform.isBlank())) {
                platform = payload.get("devicePlatform");
            }
            String deviceId = payload.get("deviceId");
            if (deviceId == null || deviceId.isBlank()) {
                deviceId = headerDeviceId;
            }
            authSessionService.attachFcmToken(email, sessionId, token, platform, deviceId);
            userService.updateFcmToken(email, token);

            // This device just became reachable — catch up on anything that was
            // saved to the inbox but never pushed because no token was active
            // at the time (e.g. an auto-logout that happened to land on a
            // scheduled disbursement).
            try {
                userRepository.findByEmail(email)
                        .ifPresent(user -> notificationService.redeliverMissedPushes(user.getId()));
            } catch (Exception e) {
                // Best-effort — never block the token-registration response on this.
            }

            return ResponseEntity.ok(Map.of("message", "FCM token updated successfully"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/fcm-token")
    public ResponseEntity<?> deleteFcmToken(@RequestBody(required = false) Map<String, String> payload, Authentication authentication, @RequestHeader("Authorization") String authHeader) {
        try {
            String email = authentication.getName();
            String sessionId = extractSessionId(authHeader);
            String token = payload != null ? payload.get("token") : null;
            String removedToken = authSessionService.clearSessionFcmTokenByValue(email, sessionId, token);
            userService.clearFcmTokenIfMatches(email, removedToken);
            return ResponseEntity.ok(Map.of("message", "FCM token removed successfully"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/device-token")
    public ResponseEntity<?> deleteDeviceToken(@RequestBody(required = false) Map<String, String> payload, Authentication authentication, @RequestHeader("Authorization") String authHeader) {
        return deleteFcmToken(payload, authentication, authHeader);
    }

    @GetMapping("/budgets/recent")
    public ResponseEntity<?> getRecentBudgets(Authentication authentication) {
        String email = authentication.getName();
        Map<String, Object> result = userService.getMostRecentBudgets(email);
        return ResponseEntity.ok(result);
    }

    // Image upload/delete endpoints are in UserController lines 53–78 (Firebase Storage)

    private boolean needsProfileUpdate(User user) {
        Map<String, Object> profileData = user.getProfileData();
        String firstName = profileData != null ? String.valueOf(profileData.getOrDefault("firstName", "")) : "";
        String lastName = profileData != null ? String.valueOf(profileData.getOrDefault("lastName", "")) : "";
        return isBlank(user.getPhone()) || isBlank(user.getBvn()) || isBlank(firstName) || isBlank(lastName);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String extractSessionId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Invalid or missing token");
        }
        String token = authHeader.substring(7);
        String sessionId = jwtUtil.extractSessionId(token);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Invalid session");
        }
        return sessionId;
    }
}
