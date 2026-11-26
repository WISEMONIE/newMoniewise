package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.moniewise.moniewise_backend.aml.AmlTestSupport.NOW;
import static com.moniewise.moniewise_backend.aml.AmlTestSupport.naira;
import static com.moniewise.moniewise_backend.aml.OutflowGuard.Channel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutflowGuardTest {

    private static final long USER_ID = 35L;

    @Mock private InflowHoldRepository holds;
    @Mock private WalletRepository walletRepository;
    @Mock private AmlLockRepository locks;

    private OutflowGuard guard;
    private Wallet wallet;
    private final List<InflowHold> open = new ArrayList<>();

    @BeforeEach
    void setUp() {
        guard = new OutflowGuard(holds, walletRepository, locks, AmlTestSupport.defaults(),
                Optional.of(AmlTestSupport.clock()));
        wallet = new Wallet();
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setOpenedAt(NOW.minusDays(60));
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.of(wallet));
        when(holds.lockByUserAndStatusIn(eq(USER_ID), any())).thenReturn(open);
        when(holds.save(any(InflowHold.class))).thenAnswer(inv -> inv.getArgument(0));
        when(holds.saveAndFlush(any(InflowHold.class))).thenAnswer(inv -> {
            InflowHold h = inv.getArgument(0);
            open.add(h);
            return h;
        });
    }

    private InflowHold largeInflowHold(long used) {
        InflowHold hold = new InflowHold();
        hold.setId(9L);
        hold.setUserId(USER_ID);
        hold.setReason(InflowHold.Reason.LARGE_INFLOW);
        hold.setStatus(InflowHold.Status.ACTIVE);
        hold.setOutflowCap(naira(50_000));
        hold.setOutflowUsed(naira(used));
        hold.setStartedAt(NOW.minusHours(2));
        open.add(hold);
        return hold;
    }

    @Test
    void noHoldMeansNoLimit() {
        assertDoesNotThrow(() -> guard.authorize(USER_ID, naira(3_000_000), BANK_TRANSFER));
        verify(locks).lock(anyLong());
    }

    @Test
    void aHeldCustomerCanSendUpToTheCapAcrossChannels() {
        InflowHold hold = largeInflowHold(0);

        guard.authorize(USER_ID, naira(30_000), BANK_TRANSFER);
        guard.authorize(USER_ID, naira(20_000), AIRTIME);

        assertEquals(0, hold.getOutflowUsed().compareTo(naira(50_000)));
    }

    @Test
    void goingOverTheCapIsBlockedWithWhatIsLeft() {
        InflowHold hold = largeInflowHold(45_000);

        OutflowBlockedException e = assertThrows(OutflowBlockedException.class,
                () -> guard.authorize(USER_ID, naira(10_000), WISEMONIE_TRANSFER));

        assertEquals(OutflowBlockedException.OUTFLOW_HELD, e.getCode());
        assertEquals(0, e.getRemaining().compareTo(naira(5_000)));
        assertTrue(e.getMessage().contains("₦5,000"));
        assertEquals(0, hold.getOutflowUsed().compareTo(naira(45_000)), "nothing counted when blocked");
    }

    @Test
    void anEscalatedHoldStillHolds() {
        InflowHold hold = largeInflowHold(50_000);
        hold.setStatus(InflowHold.Status.ESCALATED);

        assertThrows(OutflowBlockedException.class, () -> guard.authorize(USER_ID, naira(1), DATA));
    }

    @Test
    void aFrozenWalletSendsNothingEvenWithAmlOff() {
        wallet.setStatus(WalletStatus.FROZEN);

        OutflowBlockedException e = assertThrows(OutflowBlockedException.class,
                () -> guard.authorize(USER_ID, naira(100), SCHEDULED_RELEASE));
        assertEquals(OutflowBlockedException.WALLET_FROZEN, e.getCode());
        verifyNoInteractions(locks);
    }

    @Test
    void aNewWalletGetsTheFirstDayLimit() {
        wallet.setOpenedAt(NOW.minusHours(5));

        guard.authorize(USER_ID, naira(15_000), BANK_TRANSFER);

        ArgumentCaptor<InflowHold> created = ArgumentCaptor.forClass(InflowHold.class);
        verify(holds).saveAndFlush(created.capture());
        InflowHold firstDay = created.getValue();
        assertEquals(InflowHold.Reason.FIRST_DAY, firstDay.getReason());
        assertEquals(NOW.minusHours(5).plusHours(24), firstDay.getEndsAt());
        assertEquals(0, firstDay.getOutflowUsed().compareTo(naira(15_000)));

        OutflowBlockedException e = assertThrows(OutflowBlockedException.class,
                () -> guard.authorize(USER_ID, naira(10_000), BANK_TRANSFER));
        assertEquals(OutflowBlockedException.NEW_ACCOUNT_LIMIT, e.getCode());
    }

    @Test
    void theFirstDayLimitIsOpenedOnlyOnce() {
        wallet.setOpenedAt(NOW.minusHours(5));
        when(holds.existsByUserIdAndReason(USER_ID, InflowHold.Reason.FIRST_DAY)).thenReturn(true);

        guard.authorize(USER_ID, naira(1_000), BANK_TRANSFER);

        verify(holds, never()).saveAndFlush(any());
    }

    @Test
    void aFinishedFirstDayLimitNoLongerApplies() {
        InflowHold firstDay = new InflowHold();
        firstDay.setReason(InflowHold.Reason.FIRST_DAY);
        firstDay.setStatus(InflowHold.Status.ACTIVE);
        firstDay.setOutflowCap(naira(20_000));
        firstDay.setOutflowUsed(naira(20_000));
        firstDay.setStartedAt(NOW.minusHours(30));
        firstDay.setEndsAt(NOW.minusHours(6));
        open.add(firstDay);

        assertDoesNotThrow(() -> guard.authorize(USER_ID, naira(500_000), BANK_TRANSFER));
        assertEquals(InflowHold.Status.EXPIRED, firstDay.getStatus());
    }

    @Test
    void noLimitsMeansNoBannerData() {
        when(holds.findByUserIdAndStatusInOrderByIdDesc(eq(USER_ID), any())).thenReturn(List.of());
        assertNull(guard.restrictionFor(USER_ID));
    }

    @Test
    void aReviewShowsWhatIsLeftToSend() {
        InflowHold hold = largeInflowHold(45_000);
        when(holds.findByUserIdAndStatusInOrderByIdDesc(eq(USER_ID), any())).thenReturn(List.of(hold));

        var r = guard.restrictionFor(USER_ID);

        assertEquals(true, r.get("underReview"));
        assertEquals(false, r.get("frozen"));
        assertEquals(false, r.get("newAccount"));
        assertEquals(0, ((BigDecimal) r.get("availableToSend")).compareTo(naira(5_000)));
        assertEquals(false, r.get("customerContacted"));
    }

    @Test
    void aFrozenWalletHasNothingToSend() {
        wallet.setStatus(WalletStatus.FROZEN);
        when(holds.findByUserIdAndStatusInOrderByIdDesc(eq(USER_ID), any())).thenReturn(List.of());

        var r = guard.restrictionFor(USER_ID);

        assertEquals(true, r.get("frozen"));
        assertEquals(0, ((BigDecimal) r.get("availableToSend")).signum());
    }

    @Test
    void aBrandNewWalletShowsTheFirstDayLimitBeforeAnyTransfer() {
        wallet.setOpenedAt(NOW.minusHours(2));
        when(holds.findByUserIdAndStatusInOrderByIdDesc(eq(USER_ID), any())).thenReturn(List.of());

        var r = guard.restrictionFor(USER_ID);

        assertEquals(true, r.get("newAccount"));
        assertEquals(NOW.minusHours(2).plusHours(24), r.get("newAccountUntil"));
        assertEquals(0, ((BigDecimal) r.get("availableToSend")).compareTo(naira(20_000)));
    }

    @Test
    void theSmallestAllowanceWins() {
        wallet.setOpenedAt(NOW.minusHours(2));
        InflowHold review = largeInflowHold(0);
        when(holds.findByUserIdAndStatusInOrderByIdDesc(eq(USER_ID), any())).thenReturn(List.of(review));

        var r = guard.restrictionFor(USER_ID);

        assertEquals(true, r.get("underReview"));
        assertEquals(true, r.get("newAccount"));
        assertEquals(0, ((BigDecimal) r.get("availableToSend")).compareTo(naira(20_000)));
    }

    @Test
    void zeroOrMissingAmountsAreIgnored() {
        assertDoesNotThrow(() -> guard.authorize(USER_ID, BigDecimal.ZERO, BANK_TRANSFER));
        assertDoesNotThrow(() -> guard.authorize(USER_ID, null, BANK_TRANSFER));
        verifyNoInteractions(locks);
    }
}
