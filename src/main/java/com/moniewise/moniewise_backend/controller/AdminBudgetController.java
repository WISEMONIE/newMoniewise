package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.service.AdminBudgetService;
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
 * with a reason. A sign-in alone is never enough to cancel. See
 * {@link AdminBudgetCancelFlow}.
 */
@RestController
@RequestMapping("/admin/budgets")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBudgetController {

    private final AdminBudgetService budgets;
    private final AdminBudgetCancelFlow cancelFlow;

    public AdminBudgetController(AdminBudgetService budgets, AdminBudgetCancelFlow cancelFlow) {
        this.budgets = budgets;
        this.cancelFlow = cancelFlow;
    }

    /** The customer with this email, their active budgets, and earlier admin cancellations. */
    @GetMapping
    public ResponseEntity<?> find(@RequestParam String email) {
        try {
            return ResponseEntity.ok(budgets.findByEmail(email));
        } catch (IllegalArgumentException e) {
            return AdminBudgetCancelFlow.error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** Step 1: emails the signed-in admin a code that approves cancelling this one budget. */
    @PostMapping("/{budgetId}/cancel/code")
    public ResponseEntity<?> sendCode(@PathVariable Long budgetId, Authentication auth) {
        return cancelFlow.sendCode(auth.getName(), budgetId, AdminBudgetService.Scope.ACTIVE_ONLY);
    }

    /** Step 2: the code and a reason. Cancels the budget and refunds its unspent money to the customer. */
    @PostMapping("/{budgetId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long budgetId, @RequestBody Map<String, String> body,
                                    Authentication auth) {
        return cancelFlow.confirm(auth.getName(), budgetId, body, AdminBudgetService.Scope.ACTIVE_ONLY);
    }
}
