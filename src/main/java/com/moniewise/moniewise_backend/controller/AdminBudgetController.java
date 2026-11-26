package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.AdminBudgetService;
import com.moniewise.moniewise_backend.service.AdminStepUpService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Website admin: find a customer's active budgets by email and cancel one.
 *
 * <p>Cancelling takes two steps. First the admin asks for a code, which is
 * emailed to them and works only for that budget. Then they send the code
 * with a reason. A sign-in alone is never enough to cancel.
 */
@RestController
@RequestMapping("/admin/budgets")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBudgetController {

    private final AdminBudgetService budgets;
    private final AdminStepUpService stepUp;
    private final AbuseProtectionService abuse;

    public AdminBudgetController(AdminBudgetService budgets, AdminStepUpService stepUp, AbuseProtectionService abuse) {
        this.budgets = budgets;
        this.stepUp = stepUp;
        this.abuse = abuse;
    }

    /** The customer with this email, their active budgets, and earlier admin cancellations. */
    @GetMapping
    public ResponseEntity<?> find(@RequestParam String email) {
        try {
            return ResponseEntity.ok(budgets.findByEmail(email));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** Step 1: emails the signed-in admin a code that approves cancelling this one budget. */
    @PostMapping("/{budgetId}/cancel/code")
    public ResponseEntity<?> sendCode(@PathVariable Long budgetId, Authentication auth) {
        String admin = auth.getName();
        String throttle = abuse.buildKey(admin, AdminBudgetService.STEP_UP_ACTION);
        abuse.checkAllowed(AbuseProtectionService.OTP_GENERATE, throttle);
        try {
            String what = budgets.describeForApproval(budgetId);
            stepUp.sendCode(admin, AdminBudgetService.STEP_UP_ACTION, budgetId, what);
            abuse.recordFailure(AbuseProtectionService.OTP_GENERATE, throttle); // counts sends, so codes can't be spammed
            return ResponseEntity.ok(Map.of("message", "We emailed a code to " + admin + ". It ends in 10 minutes."));
        } catch (AdminBudgetService.NotCancellableException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (AdminStepUpService.StepUpException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    /** Step 2: the code and a reason. Cancels the budget and refunds its unspent money to the customer. */
    @PostMapping("/{budgetId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long budgetId, @RequestBody Map<String, String> body,
                                    Authentication auth) {
        String admin = auth.getName();
        String throttle = abuse.buildKey(admin, AdminBudgetService.STEP_UP_ACTION);
        abuse.checkAllowed(AbuseProtectionService.OTP_VERIFY, throttle);
        String reason = body == null ? null : body.get("reason");
        if (reason == null || reason.trim().length() < 5) {
            return error(HttpStatus.BAD_REQUEST, "Give a short reason (at least 5 characters).");
        }
        try {
            // Nothing changes until the code is right, and the code is then used up.
            budgets.describeForApproval(budgetId);
            stepUp.verifyAndUse(admin, AdminBudgetService.STEP_UP_ACTION, budgetId, body.get("code"));
        } catch (AdminBudgetService.NotCancellableException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (AdminStepUpService.StepUpException e) {
            abuse.recordFailure(AbuseProtectionService.OTP_VERIFY, throttle);
            return error(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
        abuse.recordSuccess(AbuseProtectionService.OTP_VERIFY, throttle);
        try {
            return ResponseEntity.ok(budgets.cancel(budgetId, admin, reason));
        } catch (AdminBudgetService.NotCancellableException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return error(HttpStatus.CONFLICT, "This budget was already cancelled.");
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", status.value(), "error", message));
    }
}
