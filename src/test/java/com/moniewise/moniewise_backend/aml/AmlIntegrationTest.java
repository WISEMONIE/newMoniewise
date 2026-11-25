package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.entity.KycProfile;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.repository.KycProfileRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the AML controls inside the real application against a real
 * PostgreSQL database: the Rubies credit webhook, the transfer endpoints,
 * the database lock under concurrent transfers, admin security and the
 * review job.
 *
 * <p>Skipped unless {@code AML_IT=true}. It needs a throwaway database and
 * the usual application settings (see scripts/run-aml-integration-tests.sh).
 * Never point it at a real database: it creates test customers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "AML_IT", matches = "true")
class AmlIntegrationTest {

    private static final String PIN = "2468";

    @Autowired private MockMvc mvc;
    @Autowired private WalletService walletService;
    @Autowired private OutflowGuard guard;
    @Autowired private InflowReviewService inflowReview;
    @Autowired private AmlCaseService cases;
    @Autowired private InflowHoldRepository holds;
    @Autowired private InflowEventRepository inflows;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private KycProfileRepository kycProfileRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /** No call ever reaches a real bank from these tests. */
    @MockBean private PaymentGatewayResolver paymentGatewayResolver;

    // ── Setup ───────────────────────────────────────────────────────────────

    private record Customer(User user, Wallet wallet) {
        Long id() {
            return user.getId();
        }
    }

    private Customer customer(BigDecimal balance, LocalDateTime walletOpenedAt, LocalDateTime signedUpAt) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setEmail("aml-it-" + tag + "@example.test");
        user.setPhone("0803" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999));
        user.setPassword(passwordEncoder.encode("not-used"));
        user.setRole(Role.USER);
        user.setVerified(true);
        user.setCreatedAt(signedUpAt);
        user.setTransactionPin(passwordEncoder.encode(PIN));
        Map<String, Object> profile = new HashMap<>();
        profile.put("name", "Emmanuel Madu");
        profile.put("firstName", "Emmanuel");
        profile.put("lastName", "Madu");
        user.setProfileData(profile);
        user = userRepository.saveAndFlush(user);

        KycProfile kyc = new KycProfile();
        kyc.setUser(user);
        kyc.setBvn("2" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        kyc.setBvnVerified(true);
        kyc.setKycStatus(KycProfile.KycStatus.VERIFIED);
        kyc.setFirstName("EMMANUEL");
        kyc.setLastName("MADU");
        kycProfileRepository.saveAndFlush(kyc);

        Wallet wallet = new Wallet();
        wallet.setUser(user);
        wallet.setBalance(balance);
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setAccountNumber("9" + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L));
        wallet.setProviderName("RUBIES");
        wallet.setProviderWalletRef(wallet.getAccountNumber());
        wallet.setOpenedAt(walletOpenedAt);
        wallet.setUpdatedAt(LocalDateTime.now());
        wallet = walletRepository.saveAndFlush(wallet);
        return new Customer(user, wallet);
    }

    private Customer established() {
        return customer(BigDecimal.ZERO, LocalDateTime.now().minusDays(90), LocalDateTime.now().minusDays(120));
    }

    private void rubiesCredit(Customer c, long amount, String reference, String senderName) {
        String json = "{"
                + "\"drCr\":\"CR\",\"responseCode\":\"00\","
                + "\"creditAccount\":\"" + c.wallet().getAccountNumber() + "\","
                + "\"paymentReference\":\"" + reference + "\","
                + "\"sessionId\":\"S-" + reference + "\","
                + "\"amount\":\"" + amount + ".00\","
                + "\"narration\":\"Transfer from " + senderName + "\","
                + "\"originatorName\":\"" + senderName + "\","
                + "\"originatorAccountNumber\":\"0123456789\","
                + "\"bankName\":\"GTBank\"}";
        walletService.processRubiesDepositWebhook(json);
    }

    private BigDecimal balance(Customer c) {
        return walletRepository.findById(c.wallet().getId()).orElseThrow().getBalance();
    }

    private InflowHold largeHold(Customer c) {
        return holds.findFirstByUserIdAndReasonAndStatusInOrderByIdDesc(
                c.id(), InflowHold.Reason.LARGE_INFLOW, InflowReviewService.HOLDING).orElseThrow();
    }

    private String withdrawBody(long amount) {
        return "{\"amount\":" + amount + ",\"transactionPin\":\"" + PIN + "\",\"narration\":\"test\","
                + "\"bankCode\":\"058\",\"bankName\":\"GTBank\",\"accountNumber\":\"0123456789\","
                + "\"accountName\":\"EMMANUEL MADU\"}";
    }

    // ── The Rubies credit webhook ──────────────────────────────────────────

    @Test
    void aLargeRubiesCreditIsCreditedAndStartsAHold() {
        Customer c = established();
        String ref = "IT-" + UUID.randomUUID();

        rubiesCredit(c, 1_500_000, ref, "ADA OKAFOR");

        assertEquals(0, balance(c).compareTo(new BigDecimal("1500000")), "the money is still credited");
        InflowHold hold = largeHold(c);
        assertEquals(InflowHold.Status.ACTIVE, hold.getStatus());
        assertEquals(SenderNameMatcher.Result.MISMATCH, hold.getNameCheck());
        assertEquals(0, hold.getOutflowCap().compareTo(new BigDecimal("50000")));
        List<InflowEvent> credits = inflows.findByHoldIdOrderByReceivedAtAsc(hold.getId());
        assertEquals(1, credits.size());
        assertEquals("ADA OKAFOR", credits.get(0).getSenderName());
        assertEquals("GTBank", credits.get(0).getSenderBank());
    }

    @Test
    void theSameWebhookTwiceCreditsAndRecordsOnce() {
        Customer c = established();
        String ref = "IT-" + UUID.randomUUID();

        rubiesCredit(c, 1_200_000, ref, "EMMANUEL MADU");
        rubiesCredit(c, 1_200_000, ref, "EMMANUEL MADU");

        assertEquals(0, balance(c).compareTo(new BigDecimal("1200000")));
        assertTrue(inflows.existsByUserIdAndReference(c.id(), ref));
        assertEquals(1, holds.findByUserIdOrderByIdDesc(c.id()).size());
        assertEquals(SenderNameMatcher.Result.MATCH, largeHold(c).getNameCheck());
    }

    @Test
    void smallCreditsAddUpToAHoldWithin24Hours() {
        Customer c = established();
        rubiesCredit(c, 400_000, "IT-" + UUID.randomUUID(), "ADA OKAFOR");
        rubiesCredit(c, 400_000, "IT-" + UUID.randomUUID(), "JOY EZE");
        assertTrue(holds.findByUserIdOrderByIdDesc(c.id()).isEmpty(), "800k is under the threshold");

        rubiesCredit(c, 300_000, "IT-" + UUID.randomUUID(), "SWIFT VENTURES LTD");

        InflowHold hold = largeHold(c);
        assertEquals(3, inflows.findByHoldIdOrderByReceivedAtAsc(hold.getId()).size());
        assertEquals(SenderNameMatcher.Result.COMPANY, hold.getNameCheck());
    }

    // ── Transfers ───────────────────────────────────────────────────────────

    @Test
    void aHeldCustomerIsStoppedAtTheWithdrawEndpoint() throws Exception {
        Customer c = established();
        rubiesCredit(c, 2_000_000, "IT-" + UUID.randomUUID(), "ADA OKAFOR");

        mvc.perform(post("/wallets/withdraw").with(user(c.user().getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(withdrawBody(1_500_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("being reviewed")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("₦50,000")));

        assertEquals(0, balance(c).compareTo(new BigDecimal("2000000")), "nothing left the wallet");
        verify(paymentGatewayResolver, never()).resolveForWallet(any());
        assertEquals(0, largeHold(c).getOutflowUsed().signum());
    }

    @Test
    void aTransferUnderTheCapGoesThroughAndIsCounted() {
        Customer c = established();
        rubiesCredit(c, 2_000_000, "IT-" + UUID.randomUUID(), "ADA OKAFOR");
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(paymentGatewayResolver.resolveForWallet(any())).thenReturn(gateway);

        var request = new com.moniewise.moniewise_backend.dto.request.WithdrawalRequest();
        request.setAmount(new BigDecimal("30000"));
        request.setTransactionPin(PIN);
        request.setBankCode("058");
        request.setBankName("GTBank");
        request.setAccountNumber("0123456789");
        request.setAccountName("EMMANUEL MADU");
        request.setNarration("test");
        try {
            walletService.processWithdrawal(c.id(), request);
        } catch (RuntimeException afterTheGuard) {
            // The mock gateway is not a real bank; the point is that the guard let it through.
        }

        verify(paymentGatewayResolver, atLeastOnce()).resolveForWallet(any());
        assertEquals(0, largeHold(c).getOutflowUsed().compareTo(new BigDecimal("30000")));
    }

    @Test
    void aWrongPinDoesNotUseUpTheAllowance() {
        Customer c = established();
        rubiesCredit(c, 2_000_000, "IT-" + UUID.randomUUID(), "ADA OKAFOR");
        var request = new com.moniewise.moniewise_backend.dto.request.WithdrawalRequest();
        request.setAmount(new BigDecimal("30000"));
        request.setTransactionPin("0000");

        assertThrows(IllegalArgumentException.class, () -> walletService.processWithdrawal(c.id(), request));
        assertEquals(0, largeHold(c).getOutflowUsed().signum());
    }

    // ── The database lock ──────────────────────────────────────────────────

    @Test
    void tenTransfersAtOnceCannotBreakTheCap() throws Exception {
        Customer c = established();
        rubiesCredit(c, 3_000_000, "IT-" + UUID.randomUUID(), "ADA OKAFOR");

        int passed = race(10, () -> guard.authorize(c.id(), new BigDecimal("10000"),
                OutflowGuard.Channel.BANK_TRANSFER));

        assertEquals(5, passed, "exactly five ₦10,000 transfers fit under ₦50,000");
        assertEquals(0, largeHold(c).getOutflowUsed().compareTo(new BigDecimal("50000")));
    }

    @Test
    void aNewWalletGetsOneFirstDayLimitEvenUnderLoad() throws Exception {
        Customer c = customer(new BigDecimal("100000"), LocalDateTime.now().minusHours(2),
                LocalDateTime.now().minusHours(3));

        int passed = race(10, () -> guard.authorize(c.id(), new BigDecimal("5000"),
                OutflowGuard.Channel.WISEMONIE_TRANSFER));

        assertEquals(4, passed, "exactly four ₦5,000 transfers fit under ₦20,000");
        long firstDay = holds.findByUserIdOrderByIdDesc(c.id()).stream()
                .filter(h -> h.getReason() == InflowHold.Reason.FIRST_DAY).count();
        assertEquals(1, firstDay);
    }

    @Test
    void aNewWalletReceivingOver20kIsReviewed() {
        Customer c = customer(BigDecimal.ZERO, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(3));
        rubiesCredit(c, 25_000, "IT-" + UUID.randomUUID(), "EMMANUEL MADU");
        assertEquals(0, largeHold(c).getThresholdUsed().compareTo(new BigDecimal("20000")));
    }

    /** Starts the calls together and counts the ones that were allowed. */
    private int race(int calls, Runnable call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < calls; i++) {
                Callable<Boolean> task = () -> {
                    start.await();
                    try {
                        call.run();
                        return true;
                    } catch (OutflowBlockedException blocked) {
                        return false;
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            int passed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    passed++;
                }
            }
            return passed;
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Admin tools and security ───────────────────────────────────────────

    @Test
    void onlyAdminsCanSeeHolds() throws Exception {
        mvc.perform(get("/admin/aml/holds")).andExpect(result ->
                assertTrue(result.getResponse().getStatus() == 401 || result.getResponse().getStatus() == 403,
                        "anonymous got " + result.getResponse().getStatus()));
        mvc.perform(get("/admin/aml/holds").with(user("someone@example.test").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/aml/holds").with(user("ops@example.test").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void theReviewFlowFromCaseFileToRelease() throws Exception {
        Customer c = established();
        rubiesCredit(c, 2_000_000, "IT-" + UUID.randomUUID(), "ADA OKAFOR");
        Long holdId = largeHold(c).getId();
        var admin = user("ops@example.test").roles("ADMIN");

        mvc.perform(get("/admin/aml/holds/" + holdId).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credits[0].senderName").value("ADA OKAFOR"))
                .andExpect(jsonPath("$.rubiesEscalation").value(
                        org.hamcrest.Matchers.containsString(c.wallet().getAccountNumber())));

        mvc.perform(post("/admin/aml/holds/" + holdId + "/contact").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Called, car sale\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/admin/aml/holds/" + holdId + "/release").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(holds.findById(holdId).orElseThrow().getStatus().isHolding());

        mvc.perform(post("/admin/aml/holds/" + holdId + "/release").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Sale receipt checked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releasedBy").value("ops@example.test"));

        assertDoesNotThrow(() -> guard.authorize(c.id(), new BigDecimal("1500000"),
                OutflowGuard.Channel.BANK_TRANSFER));
    }

    @Test
    void freezingStopsEveryTransferUntilUnfrozen() throws Exception {
        Customer c = established();
        var admin = user("ops@example.test").roles("ADMIN");

        mvc.perform(post("/admin/aml/users/" + c.id() + "/freeze").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Recall from sending bank\"}"))
                .andExpect(status().isOk());

        OutflowBlockedException e = assertThrows(OutflowBlockedException.class,
                () -> guard.authorize(c.id(), BigDecimal.ONE, OutflowGuard.Channel.AIRTIME));
        assertEquals(OutflowBlockedException.WALLET_FROZEN, e.getCode());

        mvc.perform(post("/admin/aml/users/" + c.id() + "/unfreeze").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Cleared\"}"))
                .andExpect(status().isOk());
        assertDoesNotThrow(() -> guard.authorize(c.id(), BigDecimal.ONE, OutflowGuard.Channel.AIRTIME));
    }

    // ── The review job ─────────────────────────────────────────────────────

    @Test
    void silentCustomersAreEscalatedAndContactedOnesAreNot() {
        Customer silent = established();
        Customer called = established();
        rubiesCredit(silent, 2_000_000, "IT-" + UUID.randomUUID(), "ADA OKAFOR");
        rubiesCredit(called, 2_000_000, "IT-" + UUID.randomUUID(), "ADA OKAFOR");
        for (Customer c : List.of(silent, called)) {
            InflowHold hold = largeHold(c);
            hold.setStartedAt(LocalDateTime.now().minusHours(25));
            holds.saveAndFlush(hold);
        }
        cases.recordContact(largeHold(called).getId(), "ops", "Called in");

        cases.reviewOpenHolds();

        assertEquals(InflowHold.Status.ESCALATED, largeHold(silent).getStatus());
        assertEquals(InflowHold.Status.ACTIVE, largeHold(called).getStatus());
        assertThrows(OutflowBlockedException.class, () -> guard.authorize(silent.id(), new BigDecimal("60000"),
                OutflowGuard.Channel.BANK_TRANSFER), "an escalated hold still holds");
    }
}
