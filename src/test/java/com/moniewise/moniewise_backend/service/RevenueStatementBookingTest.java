package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
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
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueStatementBookingTest {

    @Mock private WalletRepository walletRepository;
    @Mock private RevenueLogRepository revenueLogRepository;
    @Mock private TransactionLogRepository transactionLogRepository;
    @Mock private VasPayableRepository vasPayableRepository;

    private Wallet revenue;
    private RevenueClassificationService service;

    @BeforeEach
    void setUp() {
        User owner = new User();
        owner.setId(4L);
        revenue = new Wallet();
        revenue.setUser(owner);
        revenue.setRevenueWallet(true);
        revenue.setBalance(new BigDecimal("1000.00"));
        service = new RevenueClassificationService(
                walletRepository,
                revenueLogRepository,
                transactionLogRepository,
                vasPayableRepository,
                new BigDecimal("70"));
        when(walletRepository.findByRevenueWalletTrue()).thenReturn(Optional.of(revenue));
        when(walletRepository.findByUserIdForUpdate(4L)).thenReturn(Optional.of(revenue));
    }

    @Test
    void aTransferOutDropsTheRevenueWalletOnce() {
        when(transactionLogRepository.existsByReference("STMT-NIP-OUT-1")).thenReturn(false, true);
        when(transactionLogRepository.existsByProviderNameAndProviderReference("RUBIES", "NIP-OUT-1"))
                .thenReturn(false, true);

        boolean first = service.bookStatementMovement(
                "NIP-OUT-1", false, new BigDecimal("250.00"),
                "transfer out", "0123456789", "Ada Okonkwo", LocalDate.of(2026, 10, 19));
        boolean second = service.bookStatementMovement(
                "NIP-OUT-1", false, new BigDecimal("250.00"),
                "transfer out", "0123456789", "Ada Okonkwo", LocalDate.of(2026, 10, 19));

        assertTrue(first);
        assertTrue(second);
        assertEquals(0, revenue.getBalance().compareTo(new BigDecimal("750.00")));
        ArgumentCaptor<TransactionLog> saved = ArgumentCaptor.forClass(TransactionLog.class);
        verify(transactionLogRepository, times(1)).save(saved.capture());
        assertEquals(TransactionType.REVENUE_STATEMENT_ENTRY, saved.getValue().getTransactionType());
        assertEquals("STMT-NIP-OUT-1", saved.getValue().getReference());
        assertEquals("0123456789", saved.getValue().getExternalAccountNumber());
        verify(walletRepository, times(1)).save(any());
        verify(revenueLogRepository, times(1)).save(any());
    }
}
