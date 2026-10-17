package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.PayeelordVasTransaction;
import com.moniewise.moniewise_backend.entity.ReconciliationRun;
import com.moniewise.moniewise_backend.entity.VasPayable;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.VasTransactionStatus;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.WalletTransactionStatement;
import com.moniewise.moniewise_backend.repository.PayeelordVasTransactionRepository;
import com.moniewise.moniewise_backend.repository.ReconciliationRunRepository;
import com.moniewise.moniewise_backend.repository.RevenueLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.VasPayableRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueStatementServiceTest {

    private static final String SECRET_ACCOUNT = "ACCTSECRET999";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final String SALE = "VAS-AT-1";

    @Mock private WalletRepository walletRepository;
    @Mock private TransactionLogRepository transactionLogRepository;
    @Mock private RevenueLogRepository revenueLogRepository;
    @Mock private VasPayableRepository vasPayableRepository;
    @Mock private PayeelordVasTransactionRepository payeelordVasTransactionRepository;
    @Mock private ReconciliationRunRepository reconciliationRunRepository;
    @Mock private PaymentGatewayResolver paymentGatewayResolver;
    @Mock private SystemConfigService systemConfigService;
    @Mock private RevenueClassificationService revenueClassificationService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private PaymentGateway gateway;

    private Wallet revenue;
    private RevenueStatementService service;

    @BeforeEach
    void setUp() {
        revenue = new Wallet();
        revenue.setRevenueWallet(true);
        revenue.setProviderWalletRef(SECRET_ACCOUNT);
        revenue.setAccountNumber(SECRET_ACCOUNT);
        revenue.setBalance(new BigDecimal("7710.36"));

        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(walletRepository.findByRevenueWalletTrue()).thenReturn(Optional.of(revenue));
        lenient().when(paymentGatewayResolver.resolveForWallet(revenue)).thenReturn(gateway);
        lenient().when(systemConfigService.getString(eq(SystemConfigService.REVENUE_STATEMENT_CURSOR), nullable(String.class)))
                .thenReturn(null);
        lenient().when(transactionLogRepository.findByTypesAndStatusBetween(any(), any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(reconciliationRunRepository.save(any())).thenAnswer(invocation -> {
            ReconciliationRun run = invocation.getArgument(0);
            run.setId(7L);
            return run;
        });

        service = new RevenueStatementService(
                walletRepository,
                transactionLogRepository,
                revenueLogRepository,
                vasPayableRepository,
                payeelordVasTransactionRepository,
                reconciliationRunRepository,
                paymentGatewayResolver,
                systemConfigService,
                revenueClassificationService,
                new ObjectMapper(),
                transactionManager,
                false,
                "2026-10-18",
                14,
                1,
                new BigDecimal("70"));
    }

    @Test
    void reportOnlyDoesNotBookOrMoveTheCursorPastAHealableLine() {
        statement(line("REV-" + SALE, "CR", "1000.00", "2026-10-19", "00"), false);
        sale(false);

        Map<String, Object> report = service.run(TODAY, true, false);

        assertEquals("ATTENTION", report.get("status"));
        assertEquals(0, report.get("healed"));
        assertEquals(false, report.get("balanceRewritten"));
        assertEquals("2026-10-19", report.get("cursorAfter"));
        assertTrue((Boolean) report.get("cursorAdvanced"));
        verify(revenueClassificationService, never()).bookCompletedVasCollection(any(), any(), any(), any(), any());
        verify(revenueClassificationService, never()).recordWalletOpeningFee(any(), any());
        verify(walletRepository, never()).save(any());
        assertEquals(0, revenue.getBalance().compareTo(new BigDecimal("7710.36")));
        verify(systemConfigService).set(eq(SystemConfigService.REVENUE_STATEMENT_CURSOR), eq("2026-10-19"), any());
        assertFalse(String.valueOf(report).contains(SECRET_ACCOUNT));
    }

    @Test
    void healBooksTheMarginOnlyAndThenAdvancesTheCursor() {
        statement(line("REV-" + SALE, "CR", "1000.00", "2026-10-19", "00"), false);
        sale(true);

        Map<String, Object> report = service.run(TODAY, true, true);

        assertEquals(1, report.get("healed"));
        assertEquals(0, report.get("missingInDb"));
        assertEquals("2026-10-20", report.get("cursorAfter"));
        assertEquals(false, report.get("balanceRewritten"));
        verify(revenueClassificationService).bookCompletedVasCollection(
                eq(9L),
                eq(SALE),
                eq(new BigDecimal("1000.00")),
                eq(new BigDecimal("975.00")),
                eq(new BigDecimal("25.00")));
        verify(walletRepository, never()).save(any());
        assertFalse(String.valueOf(report).contains(SECRET_ACCOUNT));
    }

    @Test
    void historicalLineIsNotHealed() {
        statement(line("REV-" + SALE, "CR", "1000.00", "2026-10-17", "00"), false);
        sale(false);

        Map<String, Object> report = service.run(TODAY, true, true);

        assertEquals(0, report.get("healed"));
        assertEquals(1, report.get("missingInDb"));
        assertEquals("2026-10-20", report.get("cursorAfter"));
        verify(revenueClassificationService, never()).bookCompletedVasCollection(any(), any(), any(), any(), any());
    }

    @Test
    void unreachableReadDoesNotMoveTheCursor() {
        when(gateway.fetchWalletTransactionStatement(any(), any(), eq(SECRET_ACCOUNT), eq(1), eq(100)))
                .thenReturn(Optional.empty());

        Map<String, Object> report = service.run(TODAY, true, true);

        assertEquals("PROVIDER_UNREACHABLE", report.get("status"));
        assertEquals(false, report.get("cursorAdvanced"));
        assertEquals(false, report.get("balanceRewritten"));
        verify(systemConfigService, never()).set(any(), any(), any());
        verify(revenueClassificationService, never()).bookCompletedVasCollection(any(), any(), any(), any(), any());
        assertFalse(String.valueOf(report).contains(SECRET_ACCOUNT));
    }

    @Test
    void truncatedReadDoesNotMoveTheCursor() {
        statement(line("REV-" + SALE, "CR", "1000.00", "2026-10-19", "00"), true);
        sale(false);

        Map<String, Object> report = service.run(TODAY, true, true);

        assertEquals("TRUNCATED", report.get("status"));
        assertEquals(false, report.get("cursorAdvanced"));
        assertEquals(0, report.get("healed"));
        verify(systemConfigService, never()).set(any(), any(), any());
        verify(revenueClassificationService, never()).bookCompletedVasCollection(any(), any(), any(), any(), any());
    }

    @Test
    void previewDoesNotPersistOrHeal() {
        statement(line("WALLET-OPEN-9", "DR", "70.00", "2026-10-19", "00"), false);

        Map<String, Object> report = service.run(TODAY, false, false);

        assertEquals(false, report.get("persisted"));
        assertEquals(false, report.get("cursorAdvanced"));
        assertEquals(0, report.get("healed"));
        verify(reconciliationRunRepository, never()).save(any());
        verify(systemConfigService, never()).set(any(), any(), any());
        verify(revenueClassificationService, never()).recordWalletOpeningFee(any(), any());
    }

    private void statement(Map<String, Object> line, boolean truncated) {
        when(gateway.fetchWalletTransactionStatement(any(), any(), eq(SECRET_ACCOUNT), eq(1), eq(100)))
                .thenReturn(Optional.of(new WalletTransactionStatement(List.of(line), truncated)));
    }

    private void sale(boolean creditedAfterHeal) {
        PayeelordVasTransaction txn = new PayeelordVasTransaction();
        txn.setReference(SALE);
        txn.setStatus(VasTransactionStatus.SUCCESSFUL);
        txn.setUserId(9L);
        txn.setSellingAmount(new BigDecimal("1000.00"));
        txn.setCostAmount(new BigDecimal("975.00"));
        txn.setMarginAmount(new BigDecimal("25.00"));
        when(payeelordVasTransactionRepository.findByReference(SALE)).thenReturn(Optional.of(txn));
        VasPayable open = new VasPayable();
        open.setMarginCredited(false);
        if (creditedAfterHeal) {
            VasPayable booked = new VasPayable();
            booked.setMarginCredited(true);
            when(vasPayableRepository.findByPurchaseReference(SALE))
                    .thenReturn(Optional.of(open), Optional.of(booked));
        } else {
            when(vasPayableRepository.findByPurchaseReference(SALE)).thenReturn(Optional.of(open));
        }
    }

    private static Map<String, Object> line(String reference, String drCr, String amount, String date, String code) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("paymentReference", reference);
        raw.put("drCr", drCr);
        raw.put("amount", amount);
        raw.put("transactionDate", date);
        raw.put("responseCode", code);
        return raw;
    }
}
