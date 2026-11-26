package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.AdminBudgetService;
import com.moniewise.moniewise_backend.service.AdminStepUpService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The two steps every admin budget cancel goes through, shared by the
 * website endpoints and the older DELETE /budgets/{id}:
 * <ol>
 *   <li>{@link #sendCode}: check the budget can be cancelled, then email the
 *       admin a code that approves only that budget;</li>
 *   <li>{@link #confirm}: check the code (used up on success) and a reason,
 *       then cancel with the safety checks repeated under the budget lock.</li>
 * </ol>
 * Nothing changes until the code is right.
 */
@Component
public class AdminBudgetCancelFlow {

    private final AdminBudgetService budgets;
    private final AdminStepUpService stepUp;
    private final AbuseProtectionService abuse;

    public AdminBudgetCancelFlow(AdminBudgetService budgets, AdminStepUpService stepUp, AbuseProtectionService abuse) {
        this.budgets = budgets;
        this.stepUp = stepUp;
        this.abuse = abuse;
    }

    public ResponseEntity<?> sendCode(String admin, Long budgetId, AdminBudgetService.Scope scope) {
        String throttle = abuse.buildKey(admin, AdminBudgetService.STEP_UP_ACTION);
        abuse.checkAllowed(AbuseProtectionService.OTP_GENERATE, throttle);
        try {
            String what = budgets.describeForApproval(budgetId, scope);
            stepUp.sendCode(admin, AdminBudgetService.STEP_UP_ACTION, budgetId, what);
            abuse.recordFailure(AbuseProtectionService.OTP_GENERATE, throttle); // counts sends, so codes can't be spammed
            return ResponseEntity.ok(Map.of("message", "We emailed a code to " + admin + ". It ends in 10 minutes."));
        } catch (AdminBudgetService.NotCancellableException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (AdminStepUpService.StepUpException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    public ResponseEntity<?> confirm(String admin, Long budgetId, Map<String, String> body,
                                     AdminBudgetService.Scope scope) {
        String throttle = abuse.buildKey(admin, AdminBudgetService.STEP_UP_ACTION);
        abuse.checkAllowed(AbuseProtectionService.OTP_VERIFY, throttle);
        String reason = body == null ? null : body.get("reason");
        if (reason == null || reason.trim().length() < 5) {
            return error(HttpStatus.BAD_REQUEST, "Give a short reason (at least 5 characters).");
        }
        try {
            budgets.describeForApproval(budgetId, scope);
            stepUp.verifyAndUse(admin, AdminBudgetService.STEP_UP_ACTION, budgetId, body.get("code"));
        } catch (AdminBudgetService.NotCancellableException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (AdminStepUpService.StepUpException e) {
            abuse.recordFailure(AbuseProtectionService.OTP_VERIFY, throttle);
            return error(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
        abuse.recordSuccess(AbuseProtectionService.OTP_VERIFY, throttle);
        try {
            return ResponseEntity.ok(budgets.cancel(budgetId, admin, reason, scope));
        } catch (AdminBudgetService.NotCancellableException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return error(HttpStatus.CONFLICT, "This budget was already cancelled.");
        }
    }

    static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", status.value(), "error", message));
    }
}
