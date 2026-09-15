package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.AuthRequest;
import com.moniewise.moniewise_backend.dto.request.BvnPreVerifyRequest;
import com.moniewise.moniewise_backend.dto.request.ChangePasswordRequest;
import com.moniewise.moniewise_backend.dto.response.BvnVerificationResultDto;
import com.moniewise.moniewise_backend.service.KycService;
import com.moniewise.moniewise_backend.dto.response.AuthResponse;
import com.moniewise.moniewise_backend.dto.response.LogoutResponse;
import com.moniewise.moniewise_backend.entity.PasswordResetToken;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.TrustedDeviceRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.security.JwtUtil;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.EmailService;
import com.moniewise.moniewise_backend.service.HowToUseWisemonieNudgeService;
import com.moniewise.moniewise_backend.service.NotificationService;
import com.moniewise.moniewise_backend.service.PasswordResetService;
import com.moniewise.moniewise_backend.service.UserService;
import com.moniewise.moniewise_backend.service.AccountDeletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired private UserService userService;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordResetService resetService;
    @Autowired private EmailService emailService;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private AuthSessionService authSessionService;
    @Autowired private AbuseProtectionService abuseProtectionService;
    @Autowired private KycService kycService;
    @Autowired private TrustedDeviceRepository trustedDeviceRepository;
    @Autowired private AccountDeletionService accountDeletionService;
    @Autowired private HowToUseWisemonieNudgeService howToUseWisemonieNudgeService;


    @Value("${SESSION_IDLE_TIMEOUT_SECONDS:${session.idle-timeout-seconds:240}}")
    private String idleTimeoutSecondsRaw;

    @Value("${SESSION_WARNING_LEAD_SECONDS:${session.warning-lead-seconds:60}}")
    private String warningLeadSecondsRaw;

    /**
     * Stage-1: initiate signup — stores credentials in Redis and sends an OTP email.
     * No database write occurs here.
     */
    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody AuthRequest request,
                                                      HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(request.getEmail(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.SIGNUP, throttleKey);
        try {
            userService.signup(request.getEmail(), request.getPhone(), request.getPassword());
            abuseProtectionService.recordSuccess(AbuseProtectionService.SIGNUP, throttleKey);
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "A verification code has been sent to " + request.getEmail() + ". Please check your inbox.");
            response.put("email", request.getEmail());
            return ResponseEntity.status(201).body(response);
        } catch (Exception e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.SIGNUP, throttleKey);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Stage-2: verify the signup OTP.
     *
     * <p>On success the user record is created in PostgreSQL, the Redis key is deleted,
     * and a JWT is returned so the client can proceed directly to the app.
     *
     * <p>Request body: {@code {"email": "...", "otp": "123456"}}
     */
    /**
     * Resend a signup OTP for a pending (Redis-only) registration.
     * Safe to call multiple times — each call generates a fresh OTP and resets the TTL.
     * Rate-limited via the SIGNUP bucket to prevent OTP flooding.
     */
    @PostMapping("/resend-signup-otp")
    public ResponseEntity<?> resendSignupOtp(@RequestBody Map<String, String> body,
                                             HttpServletRequest httpRequest) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        }
        String throttleKey = abuseProtectionService.buildKey(email, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.SIGNUP, throttleKey);
        try {
            userService.resendSignupOtp(email);
            abuseProtectionService.recordSuccess(AbuseProtectionService.SIGNUP, throttleKey);
            return ResponseEntity.ok(Map.of("message", "A new verification code has been sent to " + email + ". Please check your inbox."));
        } catch (IllegalArgumentException e) {
            // No pending registration — session expired, user needs to sign up again
            abuseProtectionService.recordFailure(AbuseProtectionService.SIGNUP, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.SIGNUP, throttleKey);
            logger.error("resend-signup-otp failed for {}: {}", email, e.getMessage());
            // Non-leaking fallback — same message regardless of whether the email exists
            return ResponseEntity.ok(Map.of("message", "If a pending registration exists, a new code has been sent."));
        }
    }

    @PostMapping("/verify-signup-otp")
    public ResponseEntity<?> verifySignupOtp(@RequestBody Map<String, String> body,
                                             HttpServletRequest httpRequest) {
        String email = body.get("email");
        String otpCode = body.get("otp");

        if (email == null || email.isBlank() || otpCode == null || otpCode.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email and otp are required"));
        }

        String throttleKey = abuseProtectionService.buildKey(email, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.SIGNUP_VERIFY, throttleKey);

        try {
            // Verify OTP + persist user to DB
            User user = userService.createUserFromPendingRegistration(email, otpCode);

            // Log the user in immediately — generate a JWT session
            UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
            String sessionId = authSessionService.createSession(user);
            String token = jwtUtil.generateToken(userDetails, sessionId);
            howToUseWisemonieNudgeService.sendImmediateGuideAfterAuth(user);

            abuseProtectionService.recordSuccess(AbuseProtectionService.SIGNUP_VERIFY, throttleKey);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Email verified. Welcome to Moniewise!");
            response.put("token", token);
            response.put("expiresAt", jwtUtil.extractExpiration(token).getTime());
            response.put("idleTimeoutSeconds", getIdleTimeoutSeconds());
            response.put("warningLeadSeconds", getWarningLeadSeconds());
            response.put("needsProfileUpdate", true); // New users always need profile setup
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.SIGNUP_VERIFY, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            logger.error("verify-signup-otp failed for {}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Verification failed. Please try again."));
        }
    }

    /**
     * Stage-1.5: BVN pre-verification — called AFTER /auth/signup but BEFORE
     * /auth/verify-signup-otp, while the user is still unauthenticated.
     *
     * <p>The caller submits their phone (used to locate the in-progress pending
     * registration in Redis) and their 11-digit BVN.  The backend:
     * <ol>
     *   <li>Confirms a pending registration exists for the phone.</li>
     *   <li>Calls SecureWave's BVN verification API using the email + phone on record.</li>
     *   <li>Stores the verified BVN data inside the Redis pending record so it is
     *       automatically persisted to PostgreSQL when the OTP is verified.</li>
     * </ol>
     *
     * <p>POST /auth/bvn/pre-verify
     *
     * <p>Request body: {@code {"phone": "08012345678", "bvn": "22435553718"}}
     */
    @PostMapping("/bvn/pre-verify")
    public ResponseEntity<?> bvnPreVerify(
            @Valid @RequestBody BvnPreVerifyRequest request,
            HttpServletRequest httpRequest) {

        String phone = request.getPhone().trim();
        String throttleKey = abuseProtectionService.buildKey(phone, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.BVN_PRE_VERIFY, throttleKey);

        try {
            BvnVerificationResultDto result = kycService.preVerifyBvn(
                    phone,
                    request.getBvn().trim(),
                    request.getFirstName(),
                    request.getLastName(),
                    request.getDob());

            abuseProtectionService.recordSuccess(AbuseProtectionService.BVN_PRE_VERIFY, throttleKey);
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            // No pending registration found — user hasn't called /auth/signup yet
            abuseProtectionService.recordFailure(AbuseProtectionService.BVN_PRE_VERIFY, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            // SecureWave rejected or returned an error
            abuseProtectionService.recordFailure(AbuseProtectionService.BVN_PRE_VERIFY, throttleKey);
            logger.error("BVN pre-verify failed for phone {}: {}", phone, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(request.getEmailOrPhone(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.LOGIN, throttleKey);
        try {
            User user = userService.login(request.getEmailOrPhone(), request.getPassword());

            // First-login-per-device 2FA: the password is correct, but if this
            // device hasn't been verified before, require the email OTP step
            // (the client sends/collects the OTP, then UserController#verifyOtp
            // records the device as trusted before the client retries login).
            String deviceId = httpRequest.getHeader("X-Device-Id");
            boolean deviceTrusted = deviceId != null && !deviceId.isBlank()
                    && trustedDeviceRepository.existsByUserIdAndDeviceId(user.getId(), deviceId.trim());
            // The seeded app-review account skips the per-device OTP (a store
            // reviewer can't receive it). Password was already verified above;
            // this only affects the single row flagged test_account = true.
            if (!deviceTrusted && !user.isTestAccount()) {
                // Valid credentials — reset the failure counter, then ask for OTP.
                abuseProtectionService.recordSuccess(AbuseProtectionService.LOGIN, throttleKey);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "OTP verification required"));
            }

            UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
            String newSessionId = authSessionService.createSession(user);
            String token = jwtUtil.generateToken(userDetails, newSessionId);
            howToUseWisemonieNudgeService.sendImmediateGuideAfterAuth(user);
            abuseProtectionService.recordSuccess(AbuseProtectionService.LOGIN, throttleKey);
            boolean needsProfileUpdate = needsProfileUpdate(user);
            return ResponseEntity.ok(buildAuthPayload(token, needsProfileUpdate));
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.LOGIN, throttleKey);
            if ("OTP verification required".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "OTP verification required"));
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid credentials"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = extractBearerToken(authHeader);
            String sessionId = jwtUtil.extractSessionId(token);
            if (sessionId == null || sessionId.isBlank()) {
                throw new RuntimeException("Invalid session");
            }
            authSessionService.revokeSession(sessionId);
            return ResponseEntity.ok(new LogoutResponse("Logged out successfully. Please discard your token."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Unable to logout with the provided token"));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestHeader("Authorization") String authHeader) {
        try {
            String oldToken = extractBearerToken(authHeader);
            if (jwtUtil.isTokenExpired(oldToken)) {
                throw new RuntimeException("Token expired");
            }
            String email = jwtUtil.extractEmail(oldToken);
            String oldSessionId = jwtUtil.extractSessionId(oldToken);
            if (!authSessionService.isSessionActive(email, oldSessionId)) {
                throw new RuntimeException("Session expired");
            }
            UserDetails userDetails = userService.loadUserByUsername(email);
            String newToken = jwtUtil.generateToken(userDetails, oldSessionId);
            return ResponseEntity.ok(new AuthResponse(
                    newToken,
                    jwtUtil.extractExpiration(newToken).getTime(),
                    getIdleTimeoutSeconds(),
                    getWarningLeadSeconds()
            ));
        } catch (Exception e) {
            logger.warn("Token refresh failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in again"));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        String email = body.get("email");
        String throttleKey = abuseProtectionService.buildKey(email, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.FORGOT_PASSWORD, throttleKey);
        try {
            Optional<User> userOpt = userRepository.findFirstByEmailOrderByCreatedAtAsc(email);
            if (userOpt.isPresent() && !userOpt.get().isDeleted()) {
                User user = userOpt.get();
                PasswordResetToken token = resetService.createResetToken(email);
                String name = user.getName() != null ? user.getName() : "User";
                notificationService.sendPasswordResetOtp(email, name, token.getToken());
            }
            abuseProtectionService.recordSuccess(AbuseProtectionService.FORGOT_PASSWORD, throttleKey);
            return ResponseEntity.ok(Map.of("message", "If an account exists, a reset code has been sent."));
        } catch (Exception e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.FORGOT_PASSWORD, throttleKey);
            logger.error("Forgot password flow failed for {}", email, e);
            return ResponseEntity.ok(Map.of("message", "If an account exists, a reset code has been sent."));
        }
    }

    @PostMapping("/verify-reset-otp")
    public ResponseEntity<?> verifyResetOtp(@RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        String email = body.get("email");
        String otp = body.get("otp");
        String throttleKey = abuseProtectionService.buildKey(email, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.RESET_VERIFY, throttleKey);
        boolean isValid = resetService.isValidToken(email, otp);
        if (isValid) {
            abuseProtectionService.recordSuccess(AbuseProtectionService.RESET_VERIFY, throttleKey);
            // Return the OTP as "token" so the Flutter client can pass it directly to
            // /auth/reset-password without needing a fragile null-fallback.
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "OTP verified",
                    "email", email,
                    "token", otp
            ));
        }
        abuseProtectionService.recordFailure(AbuseProtectionService.RESET_VERIFY, throttleKey);
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired OTP"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body,
                                           HttpServletRequest httpRequest) {
        String email = body.get("email");
        String throttleKey = abuseProtectionService.buildKey(email, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.RESET_PASSWORD, throttleKey);
        String token = body.get("token");
        String newPassword = body.get("password");
        if (!resetService.isValidToken(email, token)) {
            abuseProtectionService.recordFailure(AbuseProtectionService.RESET_PASSWORD, throttleKey);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid or expired reset session"));
        }
        try {
            resetService.updateUserPassword(email, token, newPassword);
            resetService.markTokenAsUsed(email, token);
            abuseProtectionService.recordSuccess(AbuseProtectionService.RESET_PASSWORD, throttleKey);
            return ResponseEntity.ok(Map.of("message", "Password reset successful"));
        } catch (Exception e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.RESET_PASSWORD, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest
    ) {
        String throttleKey = abuseProtectionService.buildKey(
                userDetails.getUsername(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.CHANGE_PASSWORD, throttleKey);
        try {
            userService.changePassword(
                    userDetails.getUsername(),
                    request.getCurrentPassword(),
                    request.getNewPassword(),
                    request.getConfirmNewPassword()
            );
            abuseProtectionService.recordSuccess(AbuseProtectionService.CHANGE_PASSWORD, throttleKey);
            return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
        } catch (IllegalArgumentException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.CHANGE_PASSWORD, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }


    private ResponseEntity<?> generateAuthResponse(User user) {
        try {
            UserDetails userDetails = userService.loadUserByUsername(user.getEmail());
            String newSessionId = authSessionService.createSession(user);
            String token = jwtUtil.generateToken(userDetails, newSessionId);
            howToUseWisemonieNudgeService.sendImmediateGuideAfterAuth(user);
            boolean needsProfileUpdate = needsProfileUpdate(user);
            return ResponseEntity.ok(buildAuthPayload(token, needsProfileUpdate));
        } catch (Exception e) {
            logger.error("Failed to generate auth response for {}", user.getEmail(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Login generation failed"));
        }
    }

    private Map<String, Object> buildAuthPayload(String token, boolean needsProfileUpdate) {
        Date expiration = jwtUtil.extractExpiration(token);
        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("needsProfileUpdate", needsProfileUpdate);
        response.put("expiresAt", expiration.getTime());
        response.put("idleTimeoutSeconds", getIdleTimeoutSeconds());
        response.put("warningLeadSeconds", getWarningLeadSeconds());
        return response;
    }

    private long getIdleTimeoutSeconds() {
        return parseLongOrDefault(idleTimeoutSecondsRaw, 240L);
    }

    private long getWarningLeadSeconds() {
        return parseLongOrDefault(warningLeadSecondsRaw, 60L);
    }

    private long parseLongOrDefault(String value, long fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            logger.warn("Invalid numeric session config '{}', falling back to {}", value, fallback);
            return fallback;
        }
    }

    private boolean needsProfileUpdate(User user) {
        Map<String, Object> profileData = user.getProfileData();
        String firstName = readProfileValue(profileData, "firstName");
        String lastName = readProfileValue(profileData, "lastName");
        return isBlank(user.getPhone()) || isBlank(user.getBvn()) || isBlank(firstName) || isBlank(lastName);
    }

    private String readProfileValue(Map<String, Object> profileData, String key) {
        if (profileData == null) return null;
        Object value = profileData.get(key);
        return value != null ? value.toString() : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String extractBearerToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Invalid or missing token");
        }
        return authHeader.substring(7);
    }

    @DeleteMapping("/delete")
    public ResponseEntity<?> deleteMyAccount(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) Map<String, String> body) {
        String email = userDetails.getUsername();
        String reason = body != null ? body.get("reason") : null;
        String transactionPin = body != null ? body.get("transactionPin") : null;

        AccountDeletionService.Result result =
                accountDeletionService.deleteAccount(email, reason, transactionPin);

        if (result.outcome() == AccountDeletionService.Outcome.WITHDRAWAL_REQUIRED) {
            return ResponseEntity.ok(Map.of(
                    "status", "withdrawal_required",
                    "walletBalance", result.walletBalance(),
                    // The closure is now recorded as pending, so it completes on
                    // its own once the wallet clears — say so, or the user is left
                    // believing an unfinished job is theirs to remember.
                    "message", String.format(
                            "We've moved your budget and savings funds to your wallet. Withdraw ₦%,.2f to your bank and your account will close automatically once it clears.",
                            result.walletBalance())
            ));
        }

        // Farewell email is sent inside the service's finalizeClosure, so the
        // job-driven and grace-period closures send exactly the same thing.
        return ResponseEntity.ok(Map.of(
                "status", "deleted",
                "message", "Your account has been permanently closed."
        ));
    }

    /**
     * Completes a closure the instant the client confirms the closure
     * withdrawal, so the app can log the user out with the account already
     * closed rather than waiting on the hourly finalizer. The account must be
     * mid-closure (the PIN was already verified in {@code DELETE /delete}), and
     * the call is idempotent.
     */
    @PostMapping("/delete/finalize")
    public ResponseEntity<?> finalizeAccountDeletion(@AuthenticationPrincipal UserDetails userDetails) {
        accountDeletionService.finalizeDeletion(userDetails.getUsername());
        return ResponseEntity.ok(Map.of(
                "status", "deleted",
                "message", "Your account has been permanently closed."
        ));
    }

}
