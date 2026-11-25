package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.security.JwtUtil;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.OtpService;
import com.moniewise.moniewise_backend.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Sign-in for the website admin (blog and payment reviews): password, then
 * an emailed code.
 *
 * <p>The second step checks the password again with the code, so a code on
 * its own never signs anyone in. Only admin accounts get through, and every
 * failure gives the same answer. The token is short-lived and scoped to
 * /admin/blog and /admin/aml. The website keeps it on its own server; the
 * browser never sees it.
 */
@RestController
@RequestMapping("/site-admin/auth")
public class SiteAdminAuthController {

    private static final Logger logger = LoggerFactory.getLogger(SiteAdminAuthController.class);
    private static final String DENIED = "Invalid credentials";

    private final UserService userService;
    private final OtpService otpService;
    private final AuthSessionService authSessionService;
    private final JwtUtil jwtUtil;
    private final AbuseProtectionService abuseProtection;

    @Value("${moniewise.site-admin.session-hours:8}")
    private long sessionHours = 8;

    public SiteAdminAuthController(UserService userService,
                                   OtpService otpService,
                                   AuthSessionService authSessionService,
                                   JwtUtil jwtUtil,
                                   AbuseProtectionService abuseProtection) {
        this.userService = userService;
        this.otpService = otpService;
        this.authSessionService = authSessionService;
        this.jwtUtil = jwtUtil;
        this.abuseProtection = abuseProtection;
    }

    /** Step 1: email and password. Emails a code to an admin. */
    @PostMapping("/code")
    public ResponseEntity<Map<String, String>> sendCode(@RequestBody Map<String, String> body,
                                                        HttpServletRequest request) {
        String email = text(body.get("email"));
        String throttleKey = abuseProtection.buildKey(email, "site-admin");
        abuseProtection.checkAllowed(AbuseProtectionService.LOGIN, throttleKey);
        User admin = admin(email, body.get("password"));
        if (admin == null) {
            abuseProtection.recordFailure(AbuseProtectionService.LOGIN, throttleKey);
            return denied();
        }
        otpService.generateLoginOtp(admin.getId());
        abuseProtection.recordSuccess(AbuseProtectionService.LOGIN, throttleKey);
        return ResponseEntity.ok(Map.of("message", "We emailed you a 6-digit code."));
    }

    /** Step 2: email, password and the code. Returns a scoped, short-lived token. */
    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String email = text(body.get("email"));
        String throttleKey = abuseProtection.buildKey(email, "site-admin");
        abuseProtection.checkAllowed(AbuseProtectionService.OTP_VERIFY, throttleKey);
        User admin = admin(email, body.get("password"));
        String code = text(body.get("code"));
        boolean codeOk = false;
        if (admin != null && !code.isEmpty()) {
            try {
                codeOk = otpService.verifyOtp(admin.getId(), code);
            } catch (RuntimeException wrongOrExpired) {
                codeOk = false;
            }
        }
        if (!codeOk) {
            abuseProtection.recordFailure(AbuseProtectionService.OTP_VERIFY, throttleKey);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", DENIED));
        }
        otpService.clearOtp(admin.getId());
        abuseProtection.recordSuccess(AbuseProtectionService.OTP_VERIFY, throttleKey);
        UserDetails details = userService.loadUserByUsername(admin.getEmail());
        long ttlMillis = Math.max(1, sessionHours) * 3_600_000L;
        String token = jwtUtil.generateSiteAdminToken(details,
                authSessionService.createSiteAdminSession(admin), ttlMillis);
        logger.warn("[ADMIN] Website admin sign-in by user={}", admin.getId());
        return ResponseEntity.ok(Map.of("token", token, "email", admin.getEmail(),
                "expiresInSeconds", ttlMillis / 1000));
    }

    /** Ends this website session only. The admin stays signed in on the phone. */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestHeader("Authorization") String header) {
        try {
            String token = header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
            String sessionId = jwtUtil.extractSessionId(token);
            if (sessionId != null && !sessionId.isBlank()) {
                authSessionService.revokeSession(sessionId);
            }
        } catch (RuntimeException ignored) {
            // Signing out must always succeed for the caller.
        }
        return ResponseEntity.ok(Map.of("message", "Signed out"));
    }

    private User admin(String email, String password) {
        if (email.isEmpty() || password == null || password.isEmpty()) {
            return null;
        }
        try {
            User user = userService.login(email, password);
            return user.getRole() == Role.ADMIN ? user : null;
        } catch (RuntimeException badCredentials) {
            return null;
        }
    }

    // Throttled by email only: every call comes from the website's server, and
    // a forwarded client address could be faked to dodge the limit.

    private static ResponseEntity<Map<String, String>> denied() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", DENIED));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
