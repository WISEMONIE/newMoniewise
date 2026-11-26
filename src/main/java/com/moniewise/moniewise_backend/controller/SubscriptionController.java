package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.entity.SubscriptionPlan;
import com.moniewise.moniewise_backend.entity.UserSubscription;
import com.moniewise.moniewise_backend.service.SubscriptionService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Premium subscription endpoints.
 *
 * <p>Base path: {@code /subscriptions}
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Frontend calls {@code GET /subscriptions/plans} to show upgrade options.</li>
 *   <li>User pays via Paystack (existing flow). Frontend receives a payment reference.</li>
 *   <li>Frontend calls {@code POST /subscriptions/subscribe} with the plan name + reference.</li>
 *   <li>Frontend calls {@code GET /subscriptions/status} to refresh the premium badge.</li>
 * </ol>
 */
@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final UserService userService;

    public SubscriptionController(SubscriptionService subscriptionService, UserService userService) {
        this.subscriptionService = subscriptionService;
        this.userService         = userService;
    }

    // ── Available plans ───────────────────────────────────────────────────────

    /**
     * GET /subscriptions/plans
     *
     * <p>Returns all active plans. Frontend uses this to build the "Upgrade to Premium" screen.
     *
     * <p>Response:
     * <pre>
     * {
     *   "status": true,
     *   "data": [
     *     {
     *       "planName": "FREE",
     *       "price": 0,
     *       "billingCycle": "MONTHLY",
     *       "features": [],
     *       "description": "Free tier"
     *     },
     *     {
     *       "planName": "PREMIUM",
     *       "price": 2000,
     *       "billingCycle": "MONTHLY",
     *       "features": ["UNLIMITED_TRANSFERS", "AI_INSIGHTS"],
     *       "description": "Premium"
     *     }
     *   ]
     * }
     * </pre>
     */
    @GetMapping("/plans")
    public ResponseEntity<?> getPlans() {
        List<SubscriptionPlan> plans = subscriptionService.getAvailablePlans();
        List<Map<String, Object>> data = plans.stream()
                .map(this::planToMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("status", true, "data", data));
    }

    // ── Current status ────────────────────────────────────────────────────────

    /**
     * GET /subscriptions/status
     *
     * <p>Returns the user's current subscription status. Frontend should call this
     * on app launch and after a successful subscription payment to refresh the UI.
     *
     * <p>Response (premium):
     * <pre>
     * {
     *   "status": true,
     *   "data": {
     *     "isPremium": true,
     *     "plan": "PREMIUM",
     *     "billingCycle": "MONTHLY",
     *     "features": ["UNLIMITED_TRANSFERS", "AI_INSIGHTS"],
     *     "startDate": "2026-06-01",
     *     "expiresAt": "2026-07-01",
     *     "price": 2000.00
     *   }
     * }
     * </pre>
     *
     * <p>Response (free tier):
     * <pre>
     * {
     *   "status": true,
     *   "data": {
     *     "isPremium": false,
     *     "plan": "FREE",
     *     "features": [],
     *     "expiresAt": null
     *   }
     * }
     * </pre>
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus(Authentication authentication) {
        Long userId = userService.findByEmail(authentication.getName()).getId();
        Optional<UserSubscription> sub = subscriptionService.getActiveSubscription(userId);

        Map<String, Object> data = new LinkedHashMap<>();
        if (sub.isPresent()) {
            UserSubscription s = sub.get();
            List<String> features = featureList(s.getPlan().getFeatures());
            data.put("isPremium",    true);
            data.put("plan",         s.getPlan().getPlanName());
            data.put("billingCycle", s.getPlan().getBillingCycle().name());
            data.put("features",     features);
            data.put("startDate",    s.getStartDate().toString());
            data.put("expiresAt",    s.getEndDate().toString());
            data.put("price",        s.getPlan().getPrice());
        } else {
            data.put("isPremium",  false);
            data.put("plan",       "FREE");
            data.put("features",   List.of());
            data.put("expiresAt",  null);
        }

        return ResponseEntity.ok(Map.of("status", true, "data", data));
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────

    /**
     * POST /subscriptions/subscribe
     *
     * <p>Activates (or extends) a plan after a successful payment.
     * The backend does NOT verify the payment here — that should be done
     * via a Paystack webhook or a server-side payment verification call
     * before calling this endpoint.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "planName": "PREMIUM",
     *   "paymentReference": "PST-xxxxxxxxxxxxx"
     * }
     * </pre>
     *
     * <p>Response:
     * <pre>
     * {
     *   "status": true,
     *   "message": "Subscribed to PREMIUM",
     *   "data": {
     *     "plan": "PREMIUM",
     *     "features": ["UNLIMITED_TRANSFERS", "AI_INSIGHTS"],
     *     "startDate": "2026-06-01",
     *     "expiresAt": "2026-07-01"
     *   }
     * }
     * </pre>
     */
    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(@RequestBody SubscribeRequest request,
                                       Authentication authentication) {
        try {
            Long userId = userService.findByEmail(authentication.getName()).getId();
            UserSubscription sub = subscriptionService.subscribe(
                    userId, request.getPlanName(), request.getPaymentReference());

            List<String> features = featureList(sub.getPlan().getFeatures());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("plan",             sub.getPlan().getPlanName());
            data.put("billingCycle",     sub.getPlan().getBillingCycle().name());
            data.put("features",         features);
            data.put("startDate",        sub.getStartDate().toString());
            data.put("expiresAt",        sub.getEndDate().toString());

            return ResponseEntity.ok(Map.of(
                    "status", true,
                    "message", "Subscribed to " + sub.getPlan().getPlanName(),
                    "data", data
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("status", false, "error", e.getMessage()));
        }
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /**
     * POST /subscriptions/cancel
     *
     * <p>Cancels the user's active subscription immediately. No request body needed.
     */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(Authentication authentication) {
        Long userId = userService.findByEmail(authentication.getName()).getId();
        subscriptionService.cancel(userId);
        return ResponseEntity.ok(Map.of(
                "status", true,
                "message", "Subscription cancelled. Premium features remain active until the billing period ends."
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> planToMap(SubscriptionPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planName",     plan.getPlanName());
        m.put("price",        plan.getPrice());
        m.put("billingCycle", plan.getBillingCycle().name());
        m.put("features",     featureList(plan.getFeatures()));
        m.put("description",  plan.getDescription());
        return m;
    }

    private List<String> featureList(String features) {
        if (features == null || features.isBlank()) return List.of();
        return List.of(features.split(",")).stream()
                .map(String::trim)
                .filter(f -> !f.isEmpty())
                .collect(Collectors.toList());
    }

    // ── Inner request DTO ─────────────────────────────────────────────────────

    public static class SubscribeRequest {
        @NotBlank private String planName;
        @NotNull  private String paymentReference;

        public String getPlanName()            { return planName; }
        public void setPlanName(String v)      { this.planName = v; }

        public String getPaymentReference()        { return paymentReference; }
        public void setPaymentReference(String v)  { this.paymentReference = v; }
    }
}
