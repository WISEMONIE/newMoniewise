package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.ReferralReward;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.ReferralRewardRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralFeeCreditTest {

    @Mock private UserRepository userRepository;
    @Mock private ReferralRewardRepository referralRewardRepository;
    @Mock private BudgetRepository budgetRepository;
    @Mock private SystemConfigService systemConfig;

    private ReferralService service;

    @BeforeEach
    void setUp() {
        service = new ReferralService(
                userRepository, referralRewardRepository, budgetRepository, systemConfig);
    }

    @Test
    void aFeeOfTwoUnitsDropsByOneUnitWhenACreditIsAvailable() {
        BigDecimal adjusted = ReferralService.feeAfterOneUnit(
                new BigDecimal("400"), new BigDecimal("200"), true);

        assertEquals(new BigDecimal("200"), adjusted);
    }

    @Test
    void aFirstBudgetFeeStaysFreeAndDoesNotUseACredit() {
        assertEquals(BigDecimal.ZERO, ReferralService.feeAfterOneUnit(
                BigDecimal.ZERO, new BigDecimal("200"), true));
    }

    @Test
    void readingTheFeeDoesNotUseTheCredit() {
        when(systemConfig.getBigDecimal(SystemConfigService.BUDGET_CREATION_FEE, BigDecimal.ZERO))
                .thenReturn(new BigDecimal("200"));
        when(referralRewardRepository.existsByReferrerUserIdAndStatus(4L, ReferralReward.EARNED))
                .thenReturn(true);

        BigDecimal adjusted = service.adjustedFee(4L, new BigDecimal("400"));

        assertEquals(new BigDecimal("200"), adjusted);
        verify(referralRewardRepository, never()).save(any());
    }

    @Test
    void ownCodeIsRejected() {
        User user = user(9L);
        User same = user(9L);
        same.setReferralCode("AMAKA234");
        when(userRepository.findByReferralCode("AMAKA234")).thenReturn(Optional.of(same));

        assertFalse(service.attach(user, "amaka234"));
        assertNull(user.getReferredByUserId());
        verify(userRepository, never()).save(any());
    }

    @Test
    void aUserCanBeReferredOnce() {
        User user = user(2L);
        user.setReferredByUserId(1L);

        assertFalse(service.attach(user, "OTHER123"));
        verify(userRepository, never()).findByReferralCode(any());
    }

    @Test
    void anUnknownCodeDoesNotFail() {
        User user = user(2L);
        when(userRepository.findByReferralCode("MISSING1")).thenReturn(Optional.empty());

        assertFalse(service.attach(user, "missing1"));
        assertNull(user.getReferredByUserId());
    }

    @Test
    void aValidCodeIsStored() {
        User user = user(2L);
        when(userRepository.findByReferralCode("AMAKA234")).thenReturn(Optional.of(user(1L)));

        assertTrue(service.attach(user, " amaka234 "));
        assertEquals(1L, user.getReferredByUserId());
    }

    @Test
    void theFirstFundedBudgetEarnsACreditWhenTheReferrerAlreadyHasABudget() {
        User friend = user(2L);
        friend.setReferredByUserId(1L);
        when(budgetRepository.countByUserId(2L)).thenReturn(1L);
        when(budgetRepository.countByUserId(1L)).thenReturn(1L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(friend));
        when(referralRewardRepository.existsByReferredUserId(2L)).thenReturn(false);
        when(referralRewardRepository.findByReferrerUserIdAndStatus(2L, ReferralReward.PENDING))
                .thenReturn(List.of());

        service.onBudgetFunded(2L);

        ArgumentCaptor<ReferralReward> saved = ArgumentCaptor.forClass(ReferralReward.class);
        verify(referralRewardRepository).save(saved.capture());
        assertEquals(ReferralReward.EARNED, saved.getValue().getStatus());
        assertEquals(1L, saved.getValue().getReferrerUserId());
        assertEquals(2L, saved.getValue().getReferredUserId());
    }

    @Test
    void aCreditWaitsUntilTheReferrerFundsABudget() {
        User friend = user(2L);
        friend.setReferredByUserId(1L);
        when(budgetRepository.countByUserId(2L)).thenReturn(1L);
        when(budgetRepository.countByUserId(1L)).thenReturn(0L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(friend));
        when(referralRewardRepository.existsByReferredUserId(2L)).thenReturn(false);
        when(referralRewardRepository.findByReferrerUserIdAndStatus(2L, ReferralReward.PENDING))
                .thenReturn(List.of());

        service.onBudgetFunded(2L);

        ArgumentCaptor<ReferralReward> saved = ArgumentCaptor.forClass(ReferralReward.class);
        verify(referralRewardRepository).save(saved.capture());
        assertEquals(ReferralReward.PENDING, saved.getValue().getStatus());
    }

    @Test
    void aPendingCreditBecomesEarnedWhenTheReferrerFundsABudget() {
        ReferralReward waiting = new ReferralReward();
        waiting.setReferrerUserId(1L);
        waiting.setReferredUserId(2L);
        waiting.setStatus(ReferralReward.PENDING);
        when(budgetRepository.countByUserId(1L)).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(referralRewardRepository.findByReferrerUserIdAndStatus(1L, ReferralReward.PENDING))
                .thenReturn(List.of(waiting));

        service.onBudgetFunded(1L);

        assertEquals(ReferralReward.EARNED, waiting.getStatus());
        verify(referralRewardRepository).saveAll(List.of(waiting));
    }

    @Test
    void consumingACreditRecordsTheWaivedUnitAndLeavesTheChargedFee() {
        ReferralReward earned = new ReferralReward();
        earned.setStatus(ReferralReward.EARNED);
        when(referralRewardRepository.findFirstByReferrerUserIdAndStatusOrderByIdAsc(4L, ReferralReward.EARNED))
                .thenReturn(Optional.of(earned));

        BigDecimal fullFee = new BigDecimal("400");
        BigDecimal charged = ReferralService.feeAfterOneUnit(fullFee, new BigDecimal("200"), true);
        BigDecimal waived = fullFee.subtract(charged);
        service.consumeCredit(4L, 88L, waived);

        assertEquals(new BigDecimal("200"), charged);
        assertEquals(new BigDecimal("200"), earned.getAmountWaived());
        assertEquals(ReferralReward.USED, earned.getStatus());
        assertEquals(88L, earned.getUsedBudgetId());
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
