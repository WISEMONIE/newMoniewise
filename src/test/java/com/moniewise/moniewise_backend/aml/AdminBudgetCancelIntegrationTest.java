package com.moniewise.moniewise_backend.aml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cancelling a customer's active budget from the website admin, against real
 * PostgreSQL: find by email, the emailed approval code, the refund, the audit
 * record, and the safety checks. Redis is an in-memory stand-in and the code
 * email is captured instead of sent. Skipped unless AML_IT=true.
 */
@SpringBootTest(properties = "moniewise.outbox.worker.fixed-delay-ms=3600000")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "AML_IT", matches = "true")
class AdminBudgetCancelIntegrationTest {

    private static final String ADMIN = "ops-budgets@example.test";

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private WalletRepository wallets;
    @Autowired private BudgetRepository budgets;
    @Autowired private EnvelopeRepository envelopes;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private StringRedisTemplate redis;
    @SpyBean private NotificationService notifications;

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void inMemoryRedisAndNoRealEmail() {
        for (String sql : AmlIntegrationTest.productionIndexes()) {
            jdbc.execute(sql);
        }
        store.clear();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1)))
                .when(values).set(anyString(), anyString(), anyLong(), any());
        when(values.increment(anyString())).thenAnswer(i ->
                Long.valueOf(store.merge(i.getArgument(0), "1", (a, b) -> String.valueOf(Long.parseLong(a) + 1))));
        when(redis.delete(anyString())).thenAnswer(i -> store.remove(i.<String>getArgument(0)) != null);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        doNothing().when(notifications).sendAdminBroadcastEmail(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), isNull(), isNull());
    }

    private record Fixture(User user, Wallet wallet, Budget budget, Envelope envelope) {
    }

    private Fixture customerWithActiveBudget() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User u = new User();
        u.setEmail("budget-it-" + tag + "@example.test");
        u.setPhone("0808" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999));
        u.setPassword("not-used");
        u.setRole(Role.USER);
        u.setVerified(true);
        u.setCreatedAt(LocalDateTime.now().minusDays(60));
        u.setProfileData(new HashMap<>(Map.of("name", "Ada Budget")));
        u = users.saveAndFlush(u);

        Wallet w = new Wallet();
        w.setUser(u);
        w.setBalance(new BigDecimal("1000"));
        w.setStatus(WalletStatus.ACTIVE);
        w.setAccountNumber("9" + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L));
        w.setProviderName("RUBIES");
        w.setProviderWalletRef(w.getAccountNumber());
        w.setUpdatedAt(LocalDateTime.now());
        w = wallets.saveAndFlush(w);

        Budget b = new Budget();
        b.setUser(u);
        b.setName("Rent " + tag);
        b.setOriginalAmount(new BigDecimal("5200"));
        b.setFeeAmount(new BigDecimal("200"));
        b.setTotalAmount(new BigDecimal("5000"));
        b.setAllocatedAmount(new BigDecimal("5000"));
        b.setRemainingAmount(new BigDecimal("5000"));
        b.setDurationDays(30);
        b.setStartDate(LocalDate.now().minusDays(2));
        b.setEndDate(LocalDate.now().plusDays(28));
        b.setStatus(BudgetStatus.ACTIVE);
        b = budgets.saveAndFlush(b);

        Envelope e = new Envelope();
        e.setBudget(b);
        e.setName("Rent");
        e.setAmount(new BigDecimal("5000"));
        e.setInitialAmount(new BigDecimal("5000"));
        e.setRemainingAmount(BigDecimal.ZERO);
        e.setTotalRemainingAmount(new BigDecimal("5000"));
        e.setConditions(new HashMap<>(Map.of("category", "Rent", "type", "monthly")));
        e.setCreatedAt(LocalDateTime.now());
        e = envelopes.saveAndFlush(e);
        return new Fixture(u, w, b, e);
    }

    private String codeEmailedFor(long budgetId) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications, atLeastOnce()).sendAdminBroadcastEmail(eq(ADMIN), anyString(), anyString(), anyString(),
                anyString(), body.capture(), anyString(), isNull(), isNull());
        List<String> bodies = body.getAllValues();
        for (int i = bodies.size() - 1; i >= 0; i--) {
            if (bodies.get(i).contains("(#" + budgetId + ")")) {
                Matcher m = Pattern.compile("Your code is (\\d{6})").matcher(bodies.get(i));
                assertTrue(m.find(), bodies.get(i));
                return m.group(1);
            }
        }
        throw new AssertionError("No code was emailed for budget " + budgetId);
    }

    private org.springframework.test.web.servlet.ResultActions requestCode(long budgetId) throws Exception {
        return mvc.perform(post("/admin/budgets/" + budgetId + "/cancel/code").with(user(ADMIN).roles("ADMIN")));
    }

    private org.springframework.test.web.servlet.ResultActions cancel(long budgetId, String code, String reason) throws Exception {
        return mvc.perform(post("/admin/budgets/" + budgetId + "/cancel").with(user(ADMIN).roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(Map.of("code", code, "reason", reason))));
    }

    private BudgetStatus statusOf(Budget b) {
        return budgets.findById(b.getId()).orElseThrow().getStatus();
    }

    @Test
    void findsTheCustomersActiveBudgetsByEmailWithWhatACancelWouldReturn() throws Exception {
        Fixture f = customerWithActiveBudget();
        mvc.perform(get("/admin/budgets").param("email", f.user().getEmail().toUpperCase())
                        .with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.id").value(f.user().getId()))
                .andExpect(jsonPath("$.budgets.length()").value(1))
                .andExpect(jsonPath("$.budgets[0].id").value(f.budget().getId()))
                .andExpect(jsonPath("$.budgets[0].refundable").value(5000))
                .andExpect(jsonPath("$.budgets[0].blockers.length()").value(0));

        mvc.perform(get("/admin/budgets").param("email", "nobody-" + UUID.randomUUID() + "@example.test")
                        .with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No customer has that email."));
    }

    @Test
    void withTheRightCodeTheBudgetIsCancelledRefundedRecordedAndTheCustomerTold() throws Exception {
        Fixture f = customerWithActiveBudget();
        long id = f.budget().getId();

        requestCode(id).andExpect(status().isOk());
        String code = codeEmailedFor(id);

        cancel(id, code, "Customer asked to stop this budget").andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(5000))
                .andExpect(jsonPath("$.customerEmail").value(f.user().getEmail()));

        assertEquals(BudgetStatus.CANCELLED, statusOf(f.budget()));
        assertEquals(0, new BigDecimal("6000").compareTo(wallets.findById(f.wallet().getId()).orElseThrow().getBalance()),
                "the unspent 5,000 went back to the customer's wallet");
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT admin_email, reason, refunded_amount, user_id FROM admin_budget_cancellations WHERE budget_id = ?", id);
        assertEquals(ADMIN, audit.get("admin_email"));
        assertEquals("Customer asked to stop this budget", audit.get("reason"));
        assertEquals(0, new BigDecimal("5000").compareTo((BigDecimal) audit.get("refunded_amount")));

        // The code worked once; it cannot be used again.
        cancel(id, code, "Trying the same code twice").andExpect(status().isConflict());
    }

    @Test
    void aWrongCodeOrNoCodeChangesNothing() throws Exception {
        Fixture f = customerWithActiveBudget();
        long id = f.budget().getId();

        cancel(id, "123456", "Customer asked to stop this budget").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No code is waiting, or it has ended. Ask for a new code."));

        requestCode(id).andExpect(status().isOk());
        String code = codeEmailedFor(id);
        String wrong = code.equals("000000") ? "111111" : "000000";
        cancel(id, wrong, "Customer asked to stop this budget").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("That code is wrong. Check the latest email."));

        assertEquals(BudgetStatus.ACTIVE, statusOf(f.budget()));
        assertEquals(0, new BigDecimal("1000").compareTo(wallets.findById(f.wallet().getId()).orElseThrow().getBalance()));
    }

    @Test
    void aCodeForOneBudgetCannotCancelAnother() throws Exception {
        Fixture a = customerWithActiveBudget();
        Fixture b = customerWithActiveBudget();
        requestCode(a.budget().getId()).andExpect(status().isOk());
        String codeForA = codeEmailedFor(a.budget().getId());

        cancel(b.budget().getId(), codeForA, "Using the code from another budget").andExpect(status().isUnauthorized());
        assertEquals(BudgetStatus.ACTIVE, statusOf(b.budget()));
    }

    @Test
    void moneyStillMovingBlocksTheCancel() throws Exception {
        Fixture f = customerWithActiveBudget();
        long id = f.budget().getId();
        jdbc.update("UPDATE envelopes SET held_amount = 300 WHERE id = ?", f.envelope().getId());

        mvc.perform(get("/admin/budgets").param("email", f.user().getEmail()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(jsonPath("$.budgets[0].blockers.length()").value(1));
        requestCode(id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "1 envelope has money on hold (for example an airtime purchase). Wait for it to finish."));
        assertEquals(BudgetStatus.ACTIVE, statusOf(f.budget()));
    }

    @Test
    void aReasonIsRequired() throws Exception {
        Fixture f = customerWithActiveBudget();
        cancel(f.budget().getId(), "123456", "  ").andExpect(status().isBadRequest());
    }

    @Test
    void customersCannotUseTheseEndpoints() throws Exception {
        Fixture f = customerWithActiveBudget();
        mvc.perform(get("/admin/budgets").param("email", f.user().getEmail()).with(user("someone@example.test").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/budgets/" + f.budget().getId() + "/cancel/code")
                        .with(user("someone@example.test").roles("USER")))
                .andExpect(status().isForbidden());
    }
}
