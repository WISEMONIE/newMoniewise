package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.ChangeTransactionPinRequest;
import com.moniewise.moniewise_backend.dto.request.PinRequest;
import com.moniewise.moniewise_backend.dto.request.ResetTransactionPinRequest;
import com.moniewise.moniewise_backend.dto.response.PinStatusResponse;
import com.moniewise.moniewise_backend.entity.TransactionPinResetToken;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.NotificationService;
import com.moniewise.moniewise_backend.service.TransactionPinResetService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/transactions/pin")
public class UserPinController {

    private final UserService userService;
    private final TransactionPinResetService transactionPinResetService;
    private final NotificationService notificationService;
    private final AbuseProtectionService abuseProtectionService;

    public UserPinController(UserService userService,
                             TransactionPinResetService transactionPinResetService,
                             NotificationService notificationService,
                             AbuseProtectionService abuseProtectionService) {
        this.userService = userService;
        this.transactionPinResetService = transactionPinResetService;
        this.notificationService = notificationService;
        this.abuseProtectionService = abuseProtectionService;
    }

    /**
     * GET /transactions/pin/status
     * Frontend calls this on Transfer Screen load.
     * If true → show "Enter PIN". If false → navigate to "Create PIN".
     */
    @GetMapping("/status")
    public ResponseEntity<PinStatusResponse> getPinStatus(Authentication authentication) {
        User user = userService.findByEmail(authentication.getName());
        return ResponseEntity.ok(new PinStatusResponse(userService.hasPin(user)));
    }

    /**
     * POST /transactions/pin/create
     * Called when user creates their first transaction PIN.
     */
    @PostMapping("/create")
    public ResponseEntity<?> createPin(Authentication authentication,
                                       @RequestBody PinRequest request) {
        User user = userService.findByEmail(authentication.getName());
        try {
            userService.createTransactionPin(user, request.getPin(), request.getConfirmPin());
            return ResponseEntity.ok(Map.of("message", "Transaction PIN created successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /transactions/pin/verify
     * Rate-limited — brute-forcing a 4-digit PIN is the primary attack vector.
     * 5 wrong attempts → 30-minute lockout.
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verifyPin(Authentication authentication,
                                       @RequestBody PinRequest request,
                                       HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(authentication.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.PIN_VERIFY, throttleKey);
        try {
            User user = userService.findByEmail(authentication.getName());
            boolean isValid = userService.verifyTransactionPin(user, request.getPin());
            if (isValid) {
                abuseProtectionService.recordSuccess(AbuseProtectionService.PIN_VERIFY, throttleKey);
                return ResponseEntity.ok(Map.of("valid", true));
            } else {
                abuseProtectionService.recordFailure(AbuseProtectionService.PIN_VERIFY, throttleKey);
                return ResponseEntity.badRequest().body(Map.of("valid", false, "error", "Incorrect PIN"));
            }
        } catch (IllegalStateException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.PIN_VERIFY, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /transactions/pin/change
     * Rate-limited — prevents rapid current-PIN brute-forcing via change attempts.
     */
    @PostMapping("/change")
    public ResponseEntity<?> changePin(Authentication authentication,
                                       @RequestBody ChangeTransactionPinRequest request,
                                       HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(authentication.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.PIN_CHANGE, throttleKey);
        User user = userService.findByEmail(authentication.getName());
        try {
            userService.changeTransactionPin(
                    user,
                    request.getCurrentPin(),
                    request.getNewPin()
            );
            abuseProtectionService.recordSuccess(AbuseProtectionService.PIN_CHANGE, throttleKey);

            // Fire-and-forget security alert — async, never blocks the response
            String userName = user.getName() != null ? user.getName() : "there";
            String changedAt = ZonedDateTime.now(ZoneId.of("Africa/Lagos"))
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'WAT'"));
            notificationService.sendTransactionPinChangedAlert(user.getEmail(), userName, changedAt);

            return ResponseEntity.ok(Map.of("message", "Transaction PIN changed successfully"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.PIN_CHANGE, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /transactions/pin/forgot/request
     * Rate-limited — prevents OTP flooding to the user's email.
     */
    @PostMapping("/forgot/request")
    public ResponseEntity<?> requestPinReset(Authentication authentication,
                                             HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(authentication.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.PIN_FORGOT_REQUEST, throttleKey);
        User user = userService.findByEmail(authentication.getName());
        TransactionPinResetToken token = transactionPinResetService.createResetToken(user.getEmail());
        String userName = user.getName() != null ? user.getName() : "User";
        notificationService.sendTransactionPinResetOtp(user.getEmail(), userName, token.getToken());
        abuseProtectionService.recordSuccess(AbuseProtectionService.PIN_FORGOT_REQUEST, throttleKey);
        return ResponseEntity.ok(Map.of("message", "A PIN reset code has been sent to your email"));
    }

    /**
     * POST /transactions/pin/forgot/verify
     * Rate-limited — prevents OTP brute-forcing.
     */
    @PostMapping("/forgot/verify")
    public ResponseEntity<?> verifyPinResetOtp(Authentication authentication,
                                               @RequestBody Map<String, String> body,
                                               HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(authentication.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.PIN_FORGOT_VERIFY, throttleKey);
        User user = userService.findByEmail(authentication.getName());
        String otp = body.get("otp");
        boolean valid = transactionPinResetService.isValidToken(user.getEmail(), otp);
        if (!valid) {
            abuseProtectionService.recordFailure(AbuseProtectionService.PIN_FORGOT_VERIFY, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired OTP"));
        }
        abuseProtectionService.recordSuccess(AbuseProtectionService.PIN_FORGOT_VERIFY, throttleKey);
        return ResponseEntity.ok(Map.of("message", "OTP verified"));
    }

    /**
     * POST /transactions/pin/forgot/reset
     * Rate-limited — final step; prevents race-condition brute-force after OTP verify.
     */
    @PostMapping("/forgot/reset")
    public ResponseEntity<?> resetForgottenPin(Authentication authentication,
                                               @RequestBody ResetTransactionPinRequest request,
                                               HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(authentication.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.PIN_FORGOT_RESET, throttleKey);
        User user = userService.findByEmail(authentication.getName());
        try {
            if (!transactionPinResetService.isValidToken(user.getEmail(), request.getOtp())) {
                abuseProtectionService.recordFailure(AbuseProtectionService.PIN_FORGOT_RESET, throttleKey);
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired OTP"));
            }
            userService.resetTransactionPin(user, request.getNewPin(), request.getConfirmNewPin());
            transactionPinResetService.markTokenAsUsed(user.getEmail(), request.getOtp());
            abuseProtectionService.recordSuccess(AbuseProtectionService.PIN_FORGOT_RESET, throttleKey);
            return ResponseEntity.ok(Map.of("message", "Transaction PIN reset successfully"));
        } catch (IllegalArgumentException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.PIN_FORGOT_RESET, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
