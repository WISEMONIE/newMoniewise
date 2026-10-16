package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moniewise.moniewise_backend.dto.response.TransactionListResponse;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.TransactionDecisionRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionRequestRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionListPageTest {

    @Mock private DecisionEngineService decisionEngineService;
    @Mock private WalletService walletService;
    @Mock private TransactionRequestRepository transactionRequestRepository;
    @Mock private TransactionDecisionRepository transactionDecisionRepository;
    @Mock private UserRepository userRepository;
    @Mock private TransactionLogRepository transactionLogRepository;
    @Mock private EnvelopeRepository envelopeRepository;
    @Mock private BudgetRepository budgetRepository;
    @Mock private MarkupCalculatorService markupCalculatorService;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(
                decisionEngineService,
                walletService,
                transactionRequestRepository,
                transactionDecisionRepository,
                userRepository,
                transactionLogRepository,
                envelopeRepository,
                budgetRepository,
                markupCalculatorService);
    }

    @Test
    void oneBadRowDoesNotDropTheRestOfThePage() {
        TransactionLog deposit = deposit(10L);
        TransactionLog broken = new TransactionLog();
        broken.setId(11L);
        broken.setUserId(1L);
        broken.setAmount(new BigDecimal("10.00"));
        broken.setStatus(TransactionStatus.COMPLETED);
        broken.setCreatedAt(LocalDateTime.of(2026, 10, 16, 11, 0));

        when(transactionLogRepository.findUserVisibleTransactions(eq(1L), any(), any()))
                .thenReturn(new PageImpl<>(List.of(deposit, broken), PageRequest.of(0, 30), 40));

        Page<TransactionListResponse> page = transactionService.getTransactionsForUser(1L, 0, 30);

        assertEquals(1, page.getContent().size());
        assertEquals(10L, page.getContent().get(0).id());
        assertEquals("Wallet Funded", page.getContent().get(0).title());
        assertEquals(40, page.getTotalElements());
        assertFalse(page.isLast());
    }

    @Test
    void nonStringProfileNameDoesNotFailATransferRow() {
        TransactionLog transfer = new TransactionLog(
                1L, null, null, null, new BigDecimal("200.00"),
                TransactionType.WALLET_TO_USER, "Transfer");
        transfer.setId(12L);
        transfer.setCounterpartyUserId(8L);
        transfer.setCreatedAt(LocalDateTime.of(2026, 10, 16, 12, 0));

        User counterparty = new User();
        counterparty.setId(8L);
        Map<String, Object> profile = new HashMap<>();
        profile.put("name", 12);
        counterparty.setProfileData(profile);

        when(transactionLogRepository.findUserVisibleTransactions(eq(1L), any(), any()))
                .thenReturn(new PageImpl<>(List.of(transfer), PageRequest.of(0, 30), 1));
        when(userRepository.findById(8L)).thenReturn(Optional.of(counterparty));

        Page<TransactionListResponse> page = transactionService.getTransactionsForUser(1L, 0, 30);

        assertEquals(1, page.getContent().size());
        assertEquals("Transfer to Wisemonie User", page.getContent().get(0).title());
    }

    @Test
    void everyRowFailingStillFailsTheRequest() {
        TransactionLog broken = new TransactionLog();
        broken.setId(11L);
        broken.setAmount(new BigDecimal("10.00"));
        broken.setStatus(TransactionStatus.COMPLETED);
        broken.setCreatedAt(LocalDateTime.of(2026, 10, 16, 11, 0));

        when(transactionLogRepository.findUserVisibleTransactions(eq(1L), any(), any()))
                .thenReturn(new PageImpl<>(List.of(broken), PageRequest.of(0, 30), 1));

        assertThrows(RuntimeException.class, () -> transactionService.getTransactionsForUser(1L, 0, 30));
    }

    @Test
    void pageJsonKeepsAContentArray() throws Exception {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .modules(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        TransactionListResponse row = new TransactionListResponse(
                10L,
                "Wallet Funded",
                "Deposit",
                new BigDecimal("500.00"),
                null,
                "WALLET",
                "IN",
                "/transactions/10",
                LocalDateTime.of(2026, 10, 16, 12, 0),
                TransactionStatus.COMPLETED,
                TransactionType.WALLET_DEPOSIT);
        Page<TransactionListResponse> page = new PageImpl<>(List.of(row), PageRequest.of(0, 30), 1);

        JsonNode root = mapper.readTree(mapper.writeValueAsString(page));

        assertTrue(root.get("content").isArray());
        assertEquals(1, root.get("content").size());
        assertEquals(10, root.get("content").get(0).get("id").asLong());
        assertEquals(500.0, root.get("content").get(0).get("amount").asDouble());
        assertEquals("WALLET_DEPOSIT", root.get("content").get(0).get("type").asText());
        assertFalse(root.has("id") && root.has("amount"));
    }

    private static TransactionLog deposit(Long id) {
        TransactionLog deposit = new TransactionLog(
                1L, null, null, null, new BigDecimal("500.00"),
                TransactionType.WALLET_DEPOSIT, "Deposit");
        deposit.setId(id);
        deposit.setCreatedAt(LocalDateTime.of(2026, 10, 16, 12, 0));
        return deposit;
    }
}
