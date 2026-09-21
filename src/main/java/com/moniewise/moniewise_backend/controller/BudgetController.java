package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.BudgetRequest;
import com.moniewise.moniewise_backend.dto.request.LockRequest;
import com.moniewise.moniewise_backend.dto.response.BudgetCompletionAnalyticsResponse;
import com.moniewise.moniewise_backend.dto.response.BudgetResponse;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.entity.ScheduledTask;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.exception.InsufficientFundsException;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.ScheduledTaskRepository;
import com.moniewise.moniewise_backend.service.BudgetCompletionAnalyticsService;
import com.moniewise.moniewise_backend.service.BudgetService;
import com.moniewise.moniewise_backend.service.EnvelopeService;
import com.moniewise.moniewise_backend.service.UserService;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/budgets")
public class BudgetController {

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private UserService userService;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private EnvelopeService envelopeService;

    @Autowired
    private ScheduledTaskRepository scheduledTaskRepository;

    @Autowired
    private BudgetCompletionAnalyticsService budgetCompletionAnalyticsService;

    private static final Logger logger = LoggerFactory.getLogger(BudgetController.class);

    @PostMapping("/funding-preview")
    public ResponseEntity<?> previewBudgetFunding(@Valid @RequestBody BudgetRequest request,
                                                  Authentication authentication) {
        String email = authentication.getName();
        try {
            return ResponseEntity.ok(budgetService.previewBudgetFunding(request, email));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error previewing budget funding for user {}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to preview budget funding",
                            "code", "BUDGET_FUNDING_PREVIEW_FAILED"
                    ));
        }
    }

    @PostMapping
    public ResponseEntity<?> createBudget(@Valid @RequestBody BudgetRequest request, Authentication authentication) {
        String email = authentication.getName();
        logger.debug("Creating budget for user {} with request: {}", email, request);

        User user = userService.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not found"));
        }

        // Check if user accepted T&C
        if (!Boolean.TRUE.equals(user.getTncAccepted())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Please accept the Terms and Conditions to continue"));
        }

        // Proceed to create the budget
        try {
            BudgetResponse response = budgetService.createBudget(request, email);
            return new ResponseEntity<>(response, HttpStatus.CREATED);
        } catch (InsufficientFundsException e) {
            logger.warn("Budget creation blocked for user {} due to insufficient funds: {}", email, e.getMessage());
            Map<String, Object> body = insufficientBudgetCreationFundsBody(e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error creating budget for user {}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to create budget",
                            "code", "BUDGET_CREATION_FAILED"
                    ));
        }
    }

    private Map<String, Object> insufficientBudgetCreationFundsBody(InsufficientFundsException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Insufficient Funds");
        body.put("code", "INSUFFICIENT_BUDGET_CREATION_FUNDS");
        body.put("message", e.getMessage());
        body.putAll(e.getDetails());
        return body;
    }

    // New: List all Budgets for the user
    @GetMapping
    public ResponseEntity<List<BudgetResponse>> getBudgets(Authentication authentication) {
        String email = authentication.getName();
        List<BudgetResponse> budgets = budgetService.getBudgets(email);
        return ResponseEntity.ok(budgets);
    }

    @GetMapping("/{budgetId}")
    public ResponseEntity<?> getBudgetById(@PathVariable Long budgetId, Authentication authentication) {
        try {
            String email = authentication.getName();
            BudgetResponse budget = budgetService.getBudgetById(budgetId, email);
            return ResponseEntity.ok(budget);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error fetching budget: " + e.getMessage());
        }
    }

    @GetMapping("/{budgetId}/envelopes")
    public ResponseEntity<?> getEnvelopesByBudget(@PathVariable Long budgetId, Authentication authentication) {
        try {
            String email = authentication.getName();
            List<EnvelopeResponse> envelopes = budgetService.getEnvelopesByBudget(budgetId, email);
            return ResponseEntity.ok(envelopes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error fetching envelopes: " + e.getMessage());
        }
    }

    @GetMapping("/{budgetId}/completion-analytics")
    public ResponseEntity<?> getBudgetCompletionAnalytics(@PathVariable Long budgetId, Authentication authentication) {
        try {
            User user = userService.findByEmail(authentication.getName());
            BudgetCompletionAnalyticsResponse response =
                    budgetCompletionAnalyticsService.getCompletionAnalytics(user.getId(), budgetId);
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error fetching completion analytics for budget {}", budgetId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch budget completion analytics"));
        }
    }

    // New: PATCH /budgets/{budgetId}/activate
    @PatchMapping("/{budgetId}/activate")
    public ResponseEntity<?> activateBudget(@PathVariable Long budgetId, Authentication authentication) {
        try {
            String email = authentication.getName();
            BudgetResponse budget = budgetService.activateBudget(budgetId, email);
            return ResponseEntity.ok(budget);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error activating budget: " + e.getMessage());
        }
    }

    @PostMapping("/{budgetId}/cancel-scheduled")
    public ResponseEntity<?> cancelScheduledBudget(@PathVariable Long budgetId, Authentication authentication) {
        try {
            String email = authentication.getName();
            BudgetResponse budget = budgetService.cancelScheduledBudget(budgetId, email);
            return ResponseEntity.ok(Map.of(
                    "message", "Scheduled budget cancelled successfully",
                    "budget", budget
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error cancelling scheduled budget: " + e.getMessage()));
        }
    }

    // New: DELETE /budgets/{budgetId}
    // Admin/support only — there is deliberately NO user-facing way to dissolve
    // a budget outside the account-closure flow: free deletion would gut the
    // discipline layer (create budget → delete → spend). The service resolves
    // the budget's owner so the unspent-balance refund lands in THEIR wallet.
    @DeleteMapping("/{budgetId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteBudget(@PathVariable Long budgetId) {
        try {
            budgetService.deleteBudgetAsAdmin(budgetId);
            return ResponseEntity.ok("Budget deleted successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error deleting budget: " + e.getMessage());
        }
    }

    @Data
    public static class TransferExternalRequest {
        private ExternalAccount externalAccount;
        private Double amount;
        private String withdrawalReason;
        private String narration;
        private String transactionPin;
    }

    @Data
    public static class ExternalAccount {
        private String accountNumber;
        private String bankCode; // e.g., "058" for GTB
        private String bankName; // "GTBank"
        private String recipientName; // "Emeka..."
    }

    // 12/04/2025 --->// New: Top-up Budget
    @PostMapping("/{id}/topup")
    public ResponseEntity<?> topUpBudget(@PathVariable Long id, @RequestBody Map<String, Object> requestBody, Authentication authentication) {
        try {
            String email = authentication.getName();
            Double amount = Double.valueOf(requestBody.get("amount").toString());
            budgetService.topUpBudget(id, amount, email);
            return ResponseEntity.ok("Budget topped up successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (com.moniewise.moniewise_backend.exception.InsufficientFundsException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error topping up budget: " + e.getMessage());
        }
    }

    // New: Extend Budget// 13/04/2025 --->
    @PostMapping("/{id}/extend")
    public ResponseEntity<?> extendBudget(@PathVariable Long id, @RequestBody Map<String, Object> requestBody, Authentication authentication) {
        try {
            String email = authentication.getName();
            String newName = (String) requestBody.get("new_name");
            String newEndDateStr = (String) requestBody.get("new_end_date");
            LocalDate newEndDate = LocalDate.parse(newEndDateStr);
            budgetService.extendBudget(id, newName, newEndDate, email);
            return ResponseEntity.ok("Budget extended successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error extending budget: " + e.getMessage());
        }
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboard(Authentication authentication) {
        try {
            Map<String, Object> dashboard = budgetService.getDashboard(authentication.getName());
            return ResponseEntity.ok(dashboard);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/envelopes/lock")
    public ResponseEntity<?> lockEnvelope(@RequestBody LockRequest request, Authentication authentication) {
        try {
            String email = authentication.getName();
            EnvelopeResponse response = budgetService.lockEnvelope(
                    request.getEnvelopeId(),
                    request.getLockType(),
                    request.getDurationDays(),
                    request.getInterestRate(),
                    email
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Map<String, Object>>> getUserBudgets(@PathVariable Long userId, Authentication authentication) {
        try {
            String email = authentication.getName();
            User user = userService.findByEmail(email);
            if (!user.getId().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(List.of(Map.of("error", "Unauthorized access")));
            }
            List<BudgetResponse> budgets = budgetService.getBudgets(email);
            List<Map<String, Object>> response = budgets.stream().map(budget -> {
                Map<String, Object> budgetData = new HashMap<>();
                budgetData.put("id", budget.getId());
                budgetData.put("name", budget.getName());
                budgetData.put("totalAmount", budget.getTotalAmount());
                budgetData.put("endDate", budget.getEndDate());
                budgetData.put("envelopes", getEnvelopesForBudget(budget.getId(), email));
                return budgetData;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching budgets for user {}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(List.of(Map.of("error", "Failed to fetch budgets: " + e.getMessage())));
        }
    }

    private List<Map<String, Object>> getEnvelopesForBudget(Long budgetId, String email) {
        List<EnvelopeResponse> envelopes = budgetService.getEnvelopesByBudget(budgetId, email);
        return envelopes.stream().map(envelope -> {
            Map<String, Object> envelopeData = new HashMap<>();
            envelopeData.put("id", envelope.getId());
            envelopeData.put("budgetId", envelope.getBudgetId());
            envelopeData.put("name", envelope.getName());
            envelopeData.put("amount", envelope.getAmount());
            envelopeData.put("remainingAmount", envelope.getRemainingAmount());
            envelopeData.put("createdAt", envelope.getCreatedAt());
            envelopeData.put("lastDisbursedAt", envelope.getLastDisbursedAt());

            Map<String, Object> conditions = envelope.getConditions();
            if (conditions != null) {
                envelopeData.put("type", conditions.get("type"));
                if (conditions.containsKey("limit")) {
                    envelopeData.put("limit", conditions.get("limit"));
                }
                if (conditions.containsKey("days")) {
                    envelopeData.put("days", conditions.get("days"));
                }
                if (conditions.containsKey("disbursementTime")) {
                    envelopeData.put("disbursementTime", conditions.get("disbursementTime"));
                }
                if (conditions.containsKey("lockStartDate")) {
                    envelopeData.put("lockStartDate", conditions.get("lockStartDate"));
                }
                if (conditions.containsKey("lockDurationDays")) {
                    envelopeData.put("lockDurationDays", conditions.get("lockDurationDays"));
                }
                if (conditions.containsKey("used")) {
                    envelopeData.put("used", conditions.get("used"));
                }

                String type = (String) conditions.get("type");
                if (type != null) {
                    switch (type) {
                        case "daily":
                        case "weekly":
                        case "dynamic":
                            if (conditions.containsKey("limit")) {
                                envelopeData.put("nextDisbursementAmount", conditions.get("limit"));
                            }
                            break;
                        case "safe_lock":
                        case "strict_lock":
                            LocalDateTime nextDisbursement = calculateNextDisbursement(envelope);
                            if (nextDisbursement != null) {
                                envelopeData.put("nextDisbursementAmount", envelope.getRemainingAmount());
                            }
                            break;
                        case "emergency":
                            if (conditions.containsKey("used") && !((Boolean) conditions.get("used"))) {
                                envelopeData.put("nextDisbursementAmount", envelope.getRemainingAmount());
                            }
                            break;
                    }
                }
            }

            LocalDateTime nextDisbursement = calculateNextDisbursement(envelope);
            if (nextDisbursement != null) {
                envelopeData.put("nextDisbursement", nextDisbursement);
                long secondsUntilDisbursement = ChronoUnit.SECONDS.between(LocalDateTime.now(), nextDisbursement);
                envelopeData.put("secondsUntilDisbursement", secondsUntilDisbursement);
            }
            return envelopeData;
        }).collect(Collectors.toList());
    }

    private LocalDateTime calculateNextDisbursement(EnvelopeResponse envelope) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime lastDisbursedAt = envelope.getLastDisbursedAt() != null
                    ? envelope.getLastDisbursedAt()
                    : LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
            Map<String, Object> conditions = envelope.getConditions();
            if (conditions == null || !conditions.containsKey("type")) {
                logger.warn("Invalid conditions for envelope {}: conditions map is null or missing 'type'", envelope.getId());
                return null;
            }
            String type = (String) conditions.get("type");
            if (type == null) {
                logger.warn("Invalid conditions for envelope {}: type is null", envelope.getId());
                return null;
            }

            switch (type) {
                case "daily":
                    LocalDateTime nextDaily = lastDisbursedAt.toLocalDate().plusDays(1).atStartOfDay();
                    return nextDaily.isAfter(now) ? nextDaily : nextDaily.plusDays(1);
                case "weekly":
                    LocalDate weekStart = lastDisbursedAt.toLocalDate()
                            .minusDays(lastDisbursedAt.getDayOfWeek().getValue() - 1);
                    LocalDateTime nextWeekly = weekStart.plusWeeks(1).atStartOfDay();
                    return nextWeekly.isAfter(now) ? nextWeekly : nextWeekly.plusWeeks(1);
                case "dynamic":
                    if (!conditions.containsKey("days") || !conditions.containsKey("disbursementTime")) {
                        logger.warn("Invalid conditions for dynamic envelope {}: missing days or disbursementTime", envelope.getId());
                        // Fallback to scheduled_tasks
                        List<ScheduledTask> tasks = scheduledTaskRepository.findByEnvelopeId(envelope.getId());
                        return tasks.stream()
                                .map(ScheduledTask::getTriggerTime)
                                .filter(time -> time.isAfter(now))
                                .min(LocalDateTime::compareTo)
                                .orElse(null);
                    }
                    @SuppressWarnings("unchecked")
                    List<String> days = (List<String>) conditions.get("days");
                    String disbursementTime = (String) conditions.get("disbursementTime");
                    if (days == null || days.isEmpty() || disbursementTime == null) {
                        logger.warn("Invalid conditions for dynamic envelope {}: empty days or null disbursementTime", envelope.getId());
                        return null;
                    }
                    LocalTime time;
                    try {
                        time = LocalTime.parse(disbursementTime);
                    } catch (DateTimeParseException e) {
                        logger.error("Invalid disbursementTime format for envelope {}: {}", envelope.getId(), disbursementTime);
                        return null;
                    }
                    LocalDateTime next = now;
                    for (int i = 0; i < 8; i++) {
                        next = next.plusDays(1);
                        String dayOfWeek = next.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
                        if (days.contains(dayOfWeek)) {
                            LocalDateTime nextDisbursement = next.toLocalDate().atTime(time);
                            if (nextDisbursement.isAfter(now)) {
                                return nextDisbursement;
                            }
                        }
                    }
                    logger.warn("No valid next disbursement found for dynamic envelope {}", envelope.getId());
                    return null;
                case "safe_lock":
                case "strict_lock":
                    Object lockStartObj = conditions.get("lockStartDate");
                    Object lockDurationObj = conditions.get("lockDurationDays");
                    if (!(lockStartObj instanceof String) || !(lockDurationObj instanceof String)) {
                        logger.warn("Invalid conditions for envelope {}: lockStartDate or lockDurationDays is not a string", envelope.getId());
                        return null;
                    }
                    try {
                        LocalDate lockStart = LocalDate.parse((String) lockStartObj);
                        int lockDays = Integer.parseInt((String) lockDurationObj);
                        LocalDate unlockDate = lockStart.plusDays(lockDays);
                        LocalDateTime unlockDateTime = unlockDate.atStartOfDay();
                        return unlockDateTime.isAfter(now) ? unlockDateTime : null;
                    } catch (DateTimeParseException | NumberFormatException e) {
                        logger.error("Failed to parse lock conditions for envelope {}: {}", envelope.getId(), e.getMessage());
                        return null;
                    }
                case "emergency":
                    if (conditions.containsKey("used") && !((Boolean) conditions.get("used"))) {
                        return now; // Emergency funds are available immediately if not used
                    }
                    return null;
                default:
                    logger.warn("Unsupported envelope type for envelope {}: {}", envelope.getId(), type);
                    return null;
            }
        } catch (Exception e) {
            logger.error("Error calculating next disbursement for envelope {}: {}", envelope.getId(), e.getMessage(), e);
            return null;
        }
    }

}
