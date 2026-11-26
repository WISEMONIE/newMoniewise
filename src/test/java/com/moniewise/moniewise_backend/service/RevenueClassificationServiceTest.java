package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.RevenueLog;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.VasPayable;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.repository.RevenueLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.VasPayableRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueClassificationServiceTest {

    private static final String REF = "VAS-AT-9-1";

    @Mock private WalletRepository walletRepository;
    @Mock private RevenueLogRepository revenueLogRepository;
    @Mock private TransactionLogRepository transactionLogRepository;
    @Mock private VasPayableRepository vasPayableRepository;

    private Wallet revenue;
    private RevenueClassificationService service;

    @BeforeEach
    void setUp() {
        User owner = new User();
        owner.setId(2L);
        revenue = new Wallet();
        revenue.setUser(owner);
        revenue.setBalance(new BigDecimal("7701.36"));
        revenue.setRevenueWallet(true);

        service = new RevenueClassificationService(
                walletRepository,
                revenueLogRepository,
                transactionLogRepository,
                vasPayableRepository,
                new BigDecimal("70"));
    }

    @Test
    void completedCollectionCreditsMarginOnceAndKeepsCostAsPayable() {
        when(vasPayableRepository.findByPurchaseReference(REF)).thenReturn(Optional.empty());
        when(vasPayableRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(vasPayableRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(walletRepository.findByRevenueWalletTrue()).thenReturn(Optional.of(revenue));
        when(walletRepository.findByUserIdForUpdate(2L)).thenReturn(Optional.of(revenue));
        when(revenueLogRepository.existsByDescriptionContaining("ref=" + REF + " ")).thenReturn(false);

        service.bookCompletedVasCollection(9L, REF,
                new BigDecimal("1000.00"), new BigDecimal("975.00"), new BigDecimal("25.00"));

        assertEquals(0, revenue.getBalance().compareTo(new BigDecimal("7726.36")));

        ArgumentCaptor<VasPayable> payableCaptor = ArgumentCaptor.forClass(VasPayable.class);
        verify(vasPayableRepository).save(payableCaptor.capture());
        VasPayable payable = payableCaptor.getValue();
        assertEquals(REF, payable.getPurchaseReference());
        assertEquals("REV-" + REF, payable.getCollectionReference());
        assertEquals(0, payable.getCostAmount().compareTo(new BigDecimal("975.00")));
        assertEquals(0, payable.getMarginAmount().compareTo(new BigDecimal("25.00")));
        assertTrue(payable.isMarginCredited());
        assertFalse(payable.isSwept());

        ArgumentCaptor<RevenueLog> logCaptor = ArgumentCaptor.forClass(RevenueLog.class);
        verify(revenueLogRepository).save(logCaptor.capture());
        assertEquals("vas_markup", logCaptor.getValue().getType());
        assertEquals(0, logCaptor.getValue().getAmount().compareTo(new BigDecimal("25.00")));
        assertEquals(9L, logCaptor.getValue().getUserId());
    }

    @Test
    void secondBookingDoesNotCreditTheMarginAgain() {
        VasPayable booked = new VasPayable();
        booked.setMarginCredited(true);
        when(vasPayableRepository.findByPurchaseReference(REF)).thenReturn(Optional.of(booked));

        service.bookCompletedVasCollection(9L, REF,
                new BigDecimal("1000.00"), new BigDecimal("975.00"), new BigDecimal("25.00"));

        assertEquals(0, revenue.getBalance().compareTo(new BigDecimal("7701.36")));
        verify(walletRepository, never()).save(any());
        verify(revenueLogRepository, never()).save(any());
    }

    @Test
    void markupAlreadyLoggedIsNotWrittenAgainButIsAddedToTheBalance() {
        VasPayable row = new VasPayable();
        row.setMarginCredited(false);
        when(vasPayableRepository.findByPurchaseReference(REF)).thenReturn(Optional.of(row));
        when(vasPayableRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(walletRepository.findByRevenueWalletTrue()).thenReturn(Optional.of(revenue));
        when(walletRepository.findByUserIdForUpdate(2L)).thenReturn(Optional.of(revenue));
        when(revenueLogRepository.existsByDescriptionContaining("ref=" + REF + " ")).thenReturn(true);

        service.bookCompletedVasCollection(9L, REF,
                new BigDecimal("1000.00"), new BigDecimal("975.00"), new BigDecimal("25.00"));

        assertEquals(0, revenue.getBalance().compareTo(new BigDecimal("7726.36")));
        assertTrue(row.isMarginCredited());
        verify(revenueLogRepository, never()).save(any());
    }

    @Test
    void zeroMarginStillStoresTheCostAndDoesNotTouchRevenue() {
        when(vasPayableRepository.findByPurchaseReference(REF)).thenReturn(Optional.empty());
        when(vasPayableRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(vasPayableRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.bookCompletedVasCollection(9L, REF,
                new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO);

        assertEquals(0, revenue.getBalance().compareTo(new BigDecimal("7701.36")));
        verify(walletRepository, never()).save(any());
        ArgumentCaptor<VasPayable> captor = ArgumentCaptor.forClass(VasPayable.class);
        verify(vasPayableRepository).save(captor.capture());
        assertEquals(0, captor.getValue().getCostAmount().compareTo(new BigDecimal("100.00")));
        assertTrue(captor.getValue().isMarginCredited());
    }

    @Test
    void missingRevenueWalletLeavesTheMarginUncredited() {
        when(vasPayableRepository.findByPurchaseReference(REF)).thenReturn(Optional.empty());
        when(vasPayableRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(walletRepository.findByRevenueWalletTrue()).thenReturn(Optional.empty());

        service.bookCompletedVasCollection(9L, REF,
                new BigDecimal("1000.00"), new BigDecimal("975.00"), new BigDecimal("25.00"));

        verify(walletRepository, never()).save(any());
        verify(revenueLogRepository, never()).save(any());
        verify(vasPayableRepository, never()).save(any());
    }

    @Test
    void walletOpeningFeeDebitsSeventyOnce() {
        when(transactionLogRepository.existsByReference("WALLET-OPEN-9")).thenReturn(false);
        when(transactionLogRepository.existsByReferenceStartingWith("KYC-BVN-9-")).thenReturn(false);
        when(walletRepository.findByRevenueWalletTrue()).thenReturn(Optional.of(revenue));
        when(walletRepository.findByUserIdForUpdate(2L)).thenReturn(Optional.of(revenue));

        service.recordWalletOpeningFee(9L, "1000001111");

        assertEquals(0, revenue.getBalance().compareTo(new BigDecimal("7631.36")));
        ArgumentCaptor<TransactionLog> captor = ArgumentCaptor.forClass(TransactionLog.class);
        verify(transactionLogRepository).save(captor.capture());
        assertEquals(TransactionType.WALLET_OPEN_FEE, captor.getValue().getTransactionType());
        assertEquals("WALLET-OPEN-9", captor.getValue().getReference());
        assertEquals(2L, captor.getValue().getUserId());
        assertEquals(0, captor.getValue().getAmount().compareTo(new BigDecimal("70")));
    }

    @Test
    void walletOpeningFeeIsNotChargedTwice() {
        when(transactionLogRepository.existsByReference("WALLET-OPEN-9")).thenReturn(true);

        service.recordWalletOpeningFee(9L, "1000001111");

        assertEquals(0, revenue.getBalance().compareTo(new BigDecimal("7701.36")));
        verify(transactionLogRepository, never()).save(any());
    }

    @Test
    void existingBvnFeeStandsInForTheOpeningFee() {
        when(transactionLogRepository.existsByReference("WALLET-OPEN-9")).thenReturn(false);
        when(transactionLogRepository.existsByReferenceStartingWith("KYC-BVN-9-")).thenReturn(true);

        service.recordWalletOpeningFee(9L, "1000001111");

        verify(walletRepository, never()).save(any());
        verify(transactionLogRepository, never()).save(any());
    }

    @Test
    void revenueWalletItselfIsNotChargedAnOpeningFee() {
        when(transactionLogRepository.existsByReference("WALLET-OPEN-2")).thenReturn(false);
        when(transactionLogRepository.existsByReferenceStartingWith("KYC-BVN-2-")).thenReturn(false);
        when(walletRepository.findByRevenueWalletTrue()).thenReturn(Optional.of(revenue));
        when(walletRepository.findByUserIdForUpdate(2L)).thenReturn(Optional.of(revenue));

        service.recordWalletOpeningFee(2L, "1000005822");

        verify(walletRepository, never()).save(any());
        verify(transactionLogRepository, never()).save(any());
    }

    @Test
    void explainMultipliesMissingWalletsByTheOpeningFee() {
        when(walletRepository.findByRevenueWalletTrue()).thenReturn(Optional.of(revenue));
        when(vasPayableRepository.sumOutstandingCost()).thenReturn(new BigDecimal("900.00"));
        when(vasPayableRepository.sumMarginNotCredited()).thenReturn(new BigDecimal("100.50"));
        when(walletRepository.countRubiesWalletsMissingOpeningFee()).thenReturn(2L);

        RevenuePosition.Result result = service.explain(new BigDecimal("8715.36"));

        assertEquals(0, result.dbRevenue().compareTo(new BigDecimal("7701.36")));
        assertEquals(0, result.payeelordCostOutstanding().compareTo(new BigDecimal("900.00")));
        assertEquals(0, result.marginNotInRevenueBalance().compareTo(new BigDecimal("100.50")));
        assertEquals(0, result.missingWalletOpenFees().compareTo(new BigDecimal("140.00")));
        assertEquals(0, result.expectedRubies().compareTo(new BigDecimal("8561.86")));
        assertEquals(0, result.unexplained().compareTo(new BigDecimal("153.50")));
        assertEquals(RevenuePosition.STATUS_MISMATCH, result.status());
    }

    @Test
    void blankPurchaseReferenceIsIgnored() {
        service.bookCompletedVasCollection(9L, "  ",
                new BigDecimal("10"), new BigDecimal("9"), new BigDecimal("1"));
        verify(vasPayableRepository, never()).save(any());
        verify(vasPayableRepository, never()).saveAndFlush(any());
    }
}
