package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.config.BudgetLifeCycleManager;
import com.moniewise.moniewise_backend.config.GenericNotificationEvent;
import com.moniewise.moniewise_backend.entity.AdminBudgetCancellation;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.AdminBudgetCancellationRepository;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lets a website admin cancel a customer's active budget, found by email.
 *
 * <p>The cancel itself is the app's own {@link BudgetService#deleteBudgetAsAdmin}:
 * unspent envelope money goes back to the customer's wallet, the fee reserve
 * is released, and the budget is kept as CANCELLED for audit. On top of that
 * this class:
 * <ul>
 *   <li>refuses while money is still moving (held funds or a pending transfer),
 *       which could otherwise settle back into an envelope already emptied;</li>
 *   <li>records who cancelled it, why, and the amount refunded, once per budget;</li>
 *   <li>tells the customer what happened.</li>
 * </ul>
 * The controller adds the second emailed code before any cancel.
 */
@Service
public class AdminBudgetService {

    private static final Logger logger = LoggerFactory.getLogger(AdminBudgetService.class);
    public static final String STEP_UP_ACTION = "budget-cancel";

    private final BudgetRepository budgetRepository;
    private final EnvelopeRepository envelopeRepository;
    private final BudgetService budgetService;
    private final BudgetLifeCycleManager lifeCycle;
    private final AdminBudgetCancellationRepository cancellations;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;

    public AdminBudgetService(BudgetRepository budgetRepository,
                              EnvelopeRepository envelopeRepository,
                              BudgetService budgetService,
                              BudgetLifeCycleManager lifeCycle,
                              AdminBudgetCancellationRepository cancellations,
                              ApplicationEventPublisher events,
                              JdbcTemplate jdbc) {
        this.budgetRepository = budgetRepository;
        this.envelopeRepository = envelopeRepository;
        this.budgetService = budgetService;
        this.lifeCycle = lifeCycle;
        this.cancellations = cancellations;
        this.events = events;
        this.jdbc = jdbc;
    }

    public record CustomerRef(Long id, String email, String name) {
    }

    /** One active budget, with what a cancel would return and anything stopping it. */
    public record BudgetRow(Long id, String name, BigDecimal totalAmount, BigDecimal refundable,
                            int envelopes, LocalDate startDate, LocalDate endDate,
                            LocalDateTime createdAt, List<String> blockers) {
    }

    public record Lookup(CustomerRef customer, List<BudgetRow> budgets,
                         List<AdminBudgetCancellation> recentCancellations) {
    }

    public record Result(Long budgetId, String budgetName, BigDecimal refunded, String customerEmail) {
    }

    /** Thrown when the budget cannot be cancelled now. The message is safe to show. */
    public static class NotCancellableException extends RuntimeException {
        public NotCancellableException(String message) {
            super(message);
        }
    }

    /** The customer with this email and their ACTIVE budgets. */
    @Transactional(readOnly = true)
    public Lookup findByEmail(String rawEmail) {
        CustomerRef customer = customer(rawEmail);
        List<BudgetRow> rows = new ArrayList<>();
        for (Budget budget : budgetRepository.findByUserIdAndStatus(customer.id(), BudgetStatus.ACTIVE)) {
            List<Envelope> envelopes = envelopeRepository.findByBudgetId(budget.getId());
            BigDecimal refundable = BigDecimal.ZERO;
            for (Envelope envelope : envelopes) {
                refundable = refundable.add(lifeCycle.calculateSafeRefundableAmount(envelope));
            }
            rows.add(new BudgetRow(budget.getId(), budget.getName(), budget.getTotalAmount(), refundable,
                    envelopes.size(), budget.getStartDate(), budget.getEndDate(), budget.getCreatedAt(),
                    blockers(budget.getId())));
        }
        return new Lookup(customer, rows, cancellations.findTop20ByUserIdOrderByCreatedAtDesc(customer.id()));
    }

    /**
     * Checks the budget can be cancelled now, without changing anything.
     * Returns a short description for the approval email.
     */
    @Transactional(readOnly = true)
    public String describeForApproval(Long budgetId) {
        Budget budget = activeBudget(budgetId);
        List<String> blockers = blockers(budgetId);
        if (!blockers.isEmpty()) {
            throw new NotCancellableException(String.join(" ", blockers));
        }
        return "cancel the budget \"" + budget.getName() + "\" (#" + budget.getId() + ") for "
                + budget.getUser().getEmail();
    }

    /**
     * Cancels the budget. The budget row is locked first, and the checks run
     * again under that lock, so two admins cannot cancel it twice and money
     * cannot start moving in between.
     */
    @Transactional
    public Result cancel(Long budgetId, String adminEmail, String rawReason) {
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < 5) {
            throw new IllegalArgumentException("Give a short reason (at least 5 characters).");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("Keep the reason under 500 characters.");
        }
        Budget budget = budgetRepository.findByIdForUpdate(budgetId)
                .orElseThrow(() -> new NotCancellableException("That budget no longer exists."));
        if (budget.getStatus() != BudgetStatus.ACTIVE) {
            throw new NotCancellableException("This budget is " + budget.getStatus().name().toLowerCase(Locale.ROOT)
                    + ", not active, so there is nothing to cancel.");
        }
        List<String> blockers = blockers(budgetId);
        if (!blockers.isEmpty()) {
            throw new NotCancellableException(String.join(" ", blockers));
        }
        User owner = budget.getUser();

        BigDecimal refunded = budgetService.deleteBudgetAsAdmin(budgetId);

        AdminBudgetCancellation audit = new AdminBudgetCancellation();
        audit.setBudgetId(budgetId);
        audit.setUserId(owner.getId());
        audit.setBudgetName(budget.getName());
        audit.setAdminEmail(adminEmail);
        audit.setReason(reason);
        audit.setRefundedAmount(refunded);
        audit.setCreatedAt(LocalDateTime.now());
        cancellations.saveAndFlush(audit);

        Map<String, Object> params = new HashMap<>();
        params.put("budgetName", budget.getName());
        params.put("refunded", refunded);
        params.put("__message", refunded.compareTo(BigDecimal.ZERO) > 0
                ? String.format("Your budget '%s' was cancelled by Wisemonie support. ₦%,.2f is back in your wallet.",
                        budget.getName(), refunded)
                : String.format("Your budget '%s' was cancelled by Wisemonie support.", budget.getName()));
        events.publishEvent(new GenericNotificationEvent(this, owner.getId().toString(),
                NotificationType.BUDGET_UPDATED, params, budgetId, null, "/budgets"));

        logger.warn("[ADMIN-BUDGET] admin={} cancelled budget={} user={} refunded={} reason={}",
                adminEmail, budgetId, owner.getId(), refunded, reason);
        return new Result(budgetId, budget.getName(), refunded, owner.getEmail());
    }

    /** Plain reasons the budget can't be cancelled right now; empty when it can. */
    List<String> blockers(Long budgetId) {
        List<String> reasons = new ArrayList<>();
        Integer held = jdbc.queryForObject(
                "SELECT count(*) FROM envelopes WHERE budget_id = ? AND COALESCE(held_amount, 0) > 0",
                Integer.class, budgetId);
        if (held != null && held > 0) {
            reasons.add(held == 1
                    ? "1 envelope has money on hold (for example an airtime purchase). Wait for it to finish."
                    : held + " envelopes have money on hold. Wait for them to finish.");
        }
        Integer moving = jdbc.queryForObject(
                "SELECT count(*) FROM transaction_logs WHERE status IN ('PENDING', 'PROCESSING') "
                        + "AND source_envelope_id IN (SELECT id FROM envelopes WHERE budget_id = ?)",
                Integer.class, budgetId);
        if (moving != null && moving > 0) {
            reasons.add(moving == 1
                    ? "1 payment from this budget is still processing. Wait for it to finish."
                    : moving + " payments from this budget are still processing. Wait for them to finish.");
        }
        return reasons;
    }

    private Budget activeBudget(Long budgetId) {
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new NotCancellableException("That budget no longer exists."));
        if (budget.getStatus() != BudgetStatus.ACTIVE) {
            throw new NotCancellableException("This budget is " + budget.getStatus().name().toLowerCase(Locale.ROOT)
                    + ", not active, so there is nothing to cancel.");
        }
        return budget;
    }

    private CustomerRef customer(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Enter a valid email.");
        }
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT id, email, profile_data->>'name' AS name FROM users "
                        + "WHERE lower(email) = ? AND COALESCE(is_deleted, false) = false",
                email);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("No customer has that email.");
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException("More than one account uses that email. Fix this in the database first.");
        }
        Map<String, Object> row = found.get(0);
        return new CustomerRef(((Number) row.get("id")).longValue(), String.valueOf(row.get("email")),
                row.get("name") == null ? null : String.valueOf(row.get("name")));
    }
}
