package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.ProvidusExpressGateway;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.RevenueLogRepository;
import com.moniewise.moniewise_backend.repository.SavingsGoalRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.repository.WithdrawalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletOpeningFeeBoundaryTest {

    @Mock private WalletRepository walletRepository;
    @Mock private TransactionLogRepository transactionLogRepository;
    @Mock private NotificationService notificationService;
    @Mock private ActivationJourneyNudgeService activationJourneyNudgeService;
    @Mock private UserRepository userRepository;
    @Mock private PaymentGatewayResolver paymentGatewayResolver;
    @Mock private ProvidusExpressGateway providusExpressGateway;
    @Mock private WithdrawalRepository withdrawalRepository;
    @Mock private UserService userService;
    @Mock private WithdrawalFeeService withdrawalFeeService;
    @Mock private TransferFeeService transferFeeService;
    @Mock private MarkupCalculatorService markupCalculatorService;
    @Mock private RevenueLogRepository revenueLogRepository;
    @Mock private SavingsGoalRepository savingsGoalRepository;
    @Mock private EnvelopeRepository envelopeRepository;
    @Mock private SystemConfigService systemConfigService;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private MonnieCacheInvalidationService monnieCacheInvalidationService;
    @Mock private FeeReserveService feeReserveService;
    @Mock private RevenueClassificationService revenueClassificationService;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(
                walletRepository,
                transactionLogRepository,
                notificationService,
                activationJourneyNudgeService,
                userRepository,
                paymentGatewayResolver,
                providusExpressGateway,
                withdrawalRepository,
                userService,
                withdrawalFeeService,
                transferFeeService,
                markupCalculatorService,
                revenueLogRepository,
                savingsGoalRepository,
                envelopeRepository,
                systemConfigService,
                redisTemplate,
                monnieCacheInvalidationService,
                feeReserveService,
                revenueClassificationService);
    }

    @Test
    void openingFeeDebitUsesItsOwnTransaction() throws Exception {
        Transactional transactional = RevenueClassificationService.class
                .getMethod("recordWalletOpeningFee", Long.class, String.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }

    @Test
    void walletIsKeptWhenTheOpeningFeeWriteFails() {
        User user = new User();
        user.setId(9L);

        PaymentGateway gateway = mock(PaymentGateway.class);
        Map<String, String> account = new HashMap<>();
        account.put("accountNumber", "2000001111");
        account.put("accountName", "Test User");
        account.put("bank", "Rubies MFB");
        account.put("providerName", "RUBIES");

        when(walletRepository.existsByUser(user)).thenReturn(false);
        when(walletRepository.save(any(Wallet.class))).thenAnswer(invocation -> {
            Wallet saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(44L);
            }
            return saved;
        });
        when(paymentGatewayResolver.resolveDefault()).thenReturn(gateway);
        when(gateway.createVirtualAccount(user)).thenReturn(account);
        when(gateway.getProviderName()).thenReturn("RUBIES");
        doThrow(new IllegalStateException("revenue row locked"))
                .when(revenueClassificationService)
                .recordWalletOpeningFee(eq(9L), eq("2000001111"));

        Wallet created = walletService.createWalletForUser(user);

        assertEquals(44L, created.getId());
        assertEquals("2000001111", created.getAccountNumber());
        assertEquals("RUBIES", created.getProviderName());
        verify(revenueClassificationService).recordWalletOpeningFee(9L, "2000001111");
    }
}
