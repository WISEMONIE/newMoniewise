package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.entity.KycProfile;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.repository.KycProfileRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static com.moniewise.moniewise_backend.aml.AmlTestSupport.NOW;
import static com.moniewise.moniewise_backend.aml.AmlTestSupport.naira;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InflowReviewServiceTest {

    private static final long USER_ID = 35L;

    @Mock private InflowEventRepository inflowEvents;
    @Mock private InflowHoldRepository holds;
    @Mock private UserRepository userRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private KycProfileRepository kycProfileRepository;
    @Mock private AmlNotifier notifier;

    private InflowReviewService service;
    private User user;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        service = new InflowReviewService(inflowEvents, holds, userRepository, walletRepository,
                kycProfileRepository, AmlTestSupport.defaults(), notifier, Optional.of(AmlTestSupport.clock()));
        user = new User();
        user.setId(USER_ID);
        user.setEmail("customer@example.test");
        user.setCreatedAt(NOW.minusDays(90));
        user.setProfileData(new HashMap<>());
        wallet = new Wallet();
        wallet.setOpenedAt(NOW.minusDays(60));

        KycProfile kyc = new KycProfile();
        kyc.setFirstName("EMMANUEL");
        kyc.setLastName("MADU");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.of(wallet));
        when(kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(USER_ID)).thenReturn(Optional.of(kyc));
        when(inflowEvents.saveAndFlush(any(InflowEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(holds.saveAndFlush(any(InflowHold.class))).thenAnswer(inv -> {
            InflowHold h = inv.getArgument(0);
            h.setId(501L);
            return h;
        });
        when(holds.findFirstByUserIdAndReasonAndStatusInOrderByIdDesc(eq(USER_ID), any(), any()))
                .thenReturn(Optional.empty());
        when(inflowEvents.findByUserIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(eq(USER_ID), any()))
                .thenReturn(List.of());
    }

    private Optional<InflowHold> credit(long amount, long total24h, String sender) {
        when(inflowEvents.sumSince(eq(USER_ID), any())).thenReturn(naira(total24h));
        return service.recordInflow(new InflowReviewService.Inflow(
                USER_ID, naira(amount), "REF-" + amount, sender, "GTBank", "0123456789", false));
    }

    @Test
    void smallCreditsAreRecordedButNotHeld() {
        assertTrue(credit(200_000, 200_000, "ADA OKAFOR").isEmpty());
        verify(inflowEvents).saveAndFlush(any(InflowEvent.class));
        verify(holds, never()).saveAndFlush(any());
        verifyNoInteractions(notifier);
    }

    @Test
    void aCreditAtTheThresholdStartsAHold() {
        InflowHold hold = credit(1_000_000, 1_000_000, "ADA OKAFOR").orElseThrow();

        assertEquals(InflowHold.Reason.LARGE_INFLOW, hold.getReason());
        assertEquals(InflowHold.Status.ACTIVE, hold.getStatus());
        assertEquals(0, hold.getOutflowCap().compareTo(naira(50_000)));
        assertNull(hold.getEndsAt(), "a large-inflow hold never ends on its own");
        assertEquals(SenderNameMatcher.Result.MISMATCH, hold.getNameCheck());
        verify(notifier).customerHoldStarted(user, hold.getOutflowCap());
        verify(notifier).compliance(contains("Hold 501 started"), anyString());
    }

    @Test
    void manySmallerCreditsAddingUpToTheThresholdStartAHold() {
        assertTrue(credit(300_000, 1_100_000, "EMMANUEL MADU").isPresent());
    }

    @Test
    void sameNameSendersAreStillHeldButMarkedLowerRisk() {
        InflowHold hold = credit(1_200_000, 1_200_000, "MADU EMMANUEL C").orElseThrow();
        assertEquals(SenderNameMatcher.Result.MATCH, hold.getNameCheck());
    }

    @Test
    void youngAccountsUseTheLowerThreshold() {
        user.setCreatedAt(NOW.minusDays(10));
        assertTrue(credit(500_000, 500_000, "ADA OKAFOR").isPresent());
    }

    @Test
    void walletsInTheirFirstDayUseTheFirstDayLimit() {
        user.setCreatedAt(NOW.minusDays(1));
        wallet.setOpenedAt(NOW.minusHours(3));
        assertTrue(credit(25_000, 25_000, "ADA OKAFOR").isPresent());
    }

    @Test
    void aFurtherLargeCreditJoinsTheOpenHold() {
        InflowHold open = new InflowHold();
        open.setId(77L);
        open.setNameCheck(SenderNameMatcher.Result.MATCH);
        open.setOutflowCap(naira(50_000));
        when(holds.findFirstByUserIdAndReasonAndStatusInOrderByIdDesc(eq(USER_ID), any(), any()))
                .thenReturn(Optional.of(open));

        InflowHold hold = credit(2_000_000, 3_000_000, "SWIFT VENTURES LTD").orElseThrow();

        assertSame(open, hold);
        assertEquals(SenderNameMatcher.Result.COMPANY, hold.getNameCheck(), "the riskier check wins");
        verify(holds, never()).saveAndFlush(any());
        verify(notifier, never()).customerHoldStarted(any(), any());
    }

    @Test
    void theSameCreditTwiceIsIgnored() {
        when(inflowEvents.existsByUserIdAndReference(USER_ID, "REF-1000000")).thenReturn(true);
        assertTrue(credit(1_000_000, 1_000_000, "ADA").isEmpty());
        verify(inflowEvents, never()).saveAndFlush(any());
    }

    @Test
    void recordsWhoSentIt() {
        credit(1_000, 1_000, "ADA OKAFOR");
        ArgumentCaptor<InflowEvent> saved = ArgumentCaptor.forClass(InflowEvent.class);
        verify(inflowEvents).saveAndFlush(saved.capture());
        assertEquals("ADA OKAFOR", saved.getValue().getSenderName());
        assertEquals("GTBank", saved.getValue().getSenderBank());
        assertEquals(NOW, saved.getValue().getReceivedAt());
        assertEquals(0, saved.getValue().getAmount().compareTo(BigDecimal.valueOf(1_000)));
    }
}
