package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.moniewise.moniewise_backend.aml.AmlTestSupport.NOW;
import static com.moniewise.moniewise_backend.aml.AmlTestSupport.naira;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AmlCaseServiceTest {

    private static final long USER_ID = 35L;

    @Mock private InflowHoldRepository holds;
    @Mock private InflowReviewService inflowReview;
    @Mock private UserRepository userRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private WalletService walletService;
    @Mock private AmlNotifier notifier;
    @Mock private AmlLockRepository locks;

    private AmlCaseService cases;
    private InflowHold hold;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        cases = new AmlCaseService(holds, inflowReview, userRepository, walletRepository, walletService,
                notifier, AmlTestSupport.defaults(), locks, Optional.of(AmlTestSupport.clock()));
        hold = new InflowHold();
        hold.setId(9L);
        hold.setUserId(USER_ID);
        hold.setReason(InflowHold.Reason.LARGE_INFLOW);
        hold.setStatus(InflowHold.Status.ACTIVE);
        hold.setOutflowCap(naira(50_000));
        hold.setOutflowUsed(naira(50_000));
        hold.setInflowTotal24h(naira(3_000_000));
        hold.setThresholdUsed(naira(1_000_000));
        hold.setNameCheck(SenderNameMatcher.Result.MISMATCH);
        hold.setStartedAt(NOW.minusHours(30));

        User user = new User();
        user.setId(USER_ID);
        Map<String, Object> profile = new HashMap<>();
        profile.put("name", "Joshua Mathew");
        user.setProfileData(profile);
        wallet = new Wallet();
        wallet.setAccountNumber("2000001111");
        wallet.setBalance(new BigDecimal("2950000"));
        wallet.setStatus(WalletStatus.ACTIVE);

        when(holds.findById(9L)).thenReturn(Optional.of(hold));
        when(holds.save(any(InflowHold.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.of(wallet));
        when(inflowReview.creditsOn(any())).thenReturn(List.of());
        when(locks.tryLock(anyLong())).thenReturn(true);
    }

    @Test
    void theReviewJobSkipsWhenAnotherServerIsRunningIt() {
        when(locks.tryLock(anyLong())).thenReturn(false);
        when(holds.findByReasonAndStatusAndCustomerContactedAtIsNullAndStartedAtBefore(any(), any(), any()))
                .thenReturn(List.of(hold));

        cases.reviewOpenHolds();

        assertEquals(InflowHold.Status.ACTIVE, hold.getStatus());
        verifyNoInteractions(notifier);
    }

    @Test
    void releasingRecordsWhoAndWhyAndTellsTheCustomer() {
        cases.release(9L, "ops@wisemonie.test", "Salary from own GTBank account, screenshot on file");

        assertEquals(InflowHold.Status.RELEASED, hold.getStatus());
        assertEquals("ops@wisemonie.test", hold.getReleasedBy());
        assertEquals(NOW, hold.getReleasedAt());
        assertTrue(hold.getNotes().contains("Released"));
        verify(notifier).customerHoldReleased(any());
    }

    @Test
    void aReleaseNeedsAReason() {
        assertThrows(IllegalArgumentException.class, () -> cases.release(9L, "ops", " "));
        assertEquals(InflowHold.Status.ACTIVE, hold.getStatus());
    }

    @Test
    void aClosedHoldCannotBeReleasedAgain() {
        hold.setStatus(InflowHold.Status.RELEASED);
        assertThrows(IllegalStateException.class, () -> cases.release(9L, "ops", "again"));
    }

    @Test
    void silenceAfterTheHoldEscalatesButKeepsTheHold() {
        when(holds.findByReasonAndStatusAndCustomerContactedAtIsNullAndStartedAtBefore(
                eq(InflowHold.Reason.LARGE_INFLOW), eq(InflowHold.Status.ACTIVE), eq(NOW.minusHours(24))))
                .thenReturn(List.of(hold));

        cases.reviewOpenHolds();

        assertEquals(InflowHold.Status.ESCALATED, hold.getStatus());
        assertTrue(hold.getStatus().isHolding());
        assertEquals(NOW, hold.getEscalatedAt());
        verify(notifier).compliance(contains("Silent customer on hold 9"), contains("2000001111"));
    }

    @Test
    void theReviewJobClosesFinishedFirstDayLimits() {
        InflowHold firstDay = new InflowHold();
        firstDay.setReason(InflowHold.Reason.FIRST_DAY);
        firstDay.setStatus(InflowHold.Status.ACTIVE);
        when(holds.findByReasonAndStatusAndEndsAtBefore(InflowHold.Reason.FIRST_DAY, InflowHold.Status.ACTIVE, NOW))
                .thenReturn(List.of(firstDay));

        cases.reviewOpenHolds();

        assertEquals(InflowHold.Status.EXPIRED, firstDay.getStatus());
    }

    @Test
    void recordingContactStopsTheSilenceClock() {
        cases.recordContact(9L, "ops", "Customer called, says it is a car sale, will send the receipt");

        assertEquals(NOW, hold.getCustomerContactedAt());
        assertTrue(hold.getNotes().contains("car sale"));
    }

    @Test
    void theEscalationHasTheDetailsRubiesAsksFor() {
        Map<String, Object> result = cases.escalate(9L, "ops", null);

        String text = (String) result.get("sendToRubies");
        assertTrue(text.contains("Joshua Mathew"));
        assertTrue(text.contains("2000001111"));
        assertTrue(text.contains("₦3,000,000"));
        assertTrue(text.contains("has not contacted us"));
        assertEquals(InflowHold.Status.ESCALATED, hold.getStatus());
    }

    @Test
    void freezingStopsTheWalletAndClearsItsCache() {
        cases.freeze(USER_ID, "ops", "Recall request from sending bank");

        assertEquals(WalletStatus.FROZEN, wallet.getStatus());
        verify(walletRepository).save(wallet);
        verify(walletService).evictWalletCache(USER_ID);

        cases.unfreeze(USER_ID, "ops", "Cleared with Rubies");
        assertEquals(WalletStatus.ACTIVE, wallet.getStatus());
    }
}
