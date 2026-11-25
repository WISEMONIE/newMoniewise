package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.KycProfile;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.exception.WalletProvisioningException;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalletOpeningServiceTest {

    private static final long USER_ID = 7L;
    private static final String BVN = "22222222222";

    @Mock private UserRepository userRepository;
    @Mock private KycProfileRepository kycProfileRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private WalletService walletService;
    @Mock private NotificationService notificationService;
    @Mock private ActivationJourneyNudgeService activationJourneyNudgeService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private WalletOpeningService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new WalletOpeningService(userRepository, kycProfileRepository, walletRepository,
                walletService, notificationService, activationJourneyNudgeService, redisTemplate);
        user = new User();
        user.setId(USER_ID);
        user.setEmail("person@example.test");
        user.setPhone("08030000000");
        user.setBvn(BVN);
        user.setProfileData(new HashMap<>());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
    }

    @Test
    void returnsTheExistingAccountWithoutCallingTheBank() {
        Wallet existing = wallet("2000001111");
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        assertSame(existing, service.openFromVerifiedKyc(USER_ID));
        verify(walletService, never()).createWalletForUser(any());
    }

    @Test
    void opensFromTheVerifiedKycProfileAndUsesItsIdentity() {
        verifiedKyc();
        Wallet opened = wallet("2000002222");
        when(walletService.createWalletForUser(any(User.class))).thenReturn(opened);

        assertSame(opened, service.openFromVerifiedKyc(USER_ID));

        ArgumentCaptor<User> sent = ArgumentCaptor.forClass(User.class);
        verify(walletService).createWalletForUser(sent.capture());
        Map<String, Object> profile = sent.getValue().getProfileData();
        assertEquals("ADA", profile.get("bvnFirstName"));
        assertEquals("OKAFOR", profile.get("bvnLastName"));
        assertEquals("1995-04-12", profile.get("dateOfBirth"));
        assertEquals("Ada Okafor", profile.get("name"));
        verify(activationJourneyNudgeService).nudgeAfterWalletCreated(USER_ID);
        verify(redisTemplate).delete(List.of(
                WalletOpeningService.ATTEMPTS_KEY + USER_ID, WalletOpeningService.HOLD_KEY + USER_ID,
                WalletOpeningService.LAST_ERROR_KEY + USER_ID));
    }

    @Test
    void sendsRubiesAnIsoDateOfBirth() {
        verifiedKyc();
        kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(USER_ID).orElseThrow()
                .setDateOfBirth("Mar 19, 1998");
        when(walletService.createWalletForUser(any(User.class))).thenReturn(wallet("2000002222"));

        service.openFromVerifiedKyc(USER_ID);

        assertEquals("1998-03-19", user.getProfileData().get("dateOfBirth"));
    }

    @Test
    void keepsADisplayNameTheUserAlreadyHas() {
        verifiedKyc();
        user.getProfileData().put("firstName", "Adaeze");
        user.getProfileData().put("name", "Adaeze Okafor");
        when(walletService.createWalletForUser(any(User.class))).thenReturn(wallet("2000002222"));

        service.openFromVerifiedKyc(USER_ID);

        assertEquals("Adaeze Okafor", user.getProfileData().get("name"));
        assertEquals("ADA", user.getProfileData().get("bvnFirstName"));
    }

    @Test
    void refusesWithoutAVerifiedBvn() {
        WalletProvisioningException e = assertThrows(WalletProvisioningException.class,
                () -> service.openFromVerifiedKyc(USER_ID));
        assertEquals("WALLET_KYC_REQUIRED", e.getCode());
        verify(walletService, never()).createWalletForUser(any());
    }

    @Test
    void refusesWhenTheUserBvnDiffersFromTheVerifiedOne() {
        verifiedKyc();
        user.setBvn("33333333333");

        assertThrows(WalletProvisioningException.class, () -> service.openFromVerifiedKyc(USER_ID));
        verify(walletService, never()).createWalletForUser(any());
    }

    @Test
    void asksForAPhoneNumberFirst() {
        verifiedKyc();
        user.setPhone(" ");

        WalletProvisioningException e = assertThrows(WalletProvisioningException.class,
                () -> service.openFromVerifiedKyc(USER_ID));
        assertEquals("WALLET_PHONE_REQUIRED", e.getCode());
    }

    @Test
    void aBankFailurePutsTheUserOnHold() {
        verifiedKyc();
        when(walletService.createWalletForUser(any(User.class)))
                .thenThrow(WalletProvisioningException.providerUnavailable("timeout"));
        when(valueOps.increment(WalletOpeningService.ATTEMPTS_KEY + USER_ID)).thenReturn(1L);

        assertThrows(WalletProvisioningException.class, () -> service.openFromVerifiedKyc(USER_ID));
        verify(valueOps).set(eq(WalletOpeningService.HOLD_KEY + USER_ID), eq("1"), eq(Duration.ofMinutes(15)));
    }

    @Test
    void aSecondServerOpeningTheSameAccountIsNotAFailure() {
        verifiedKyc();
        when(walletService.createWalletForUser(any(User.class)))
                .thenThrow(WalletProvisioningException.openingInProgress());

        assertThrows(WalletProvisioningException.class, () -> service.openFromVerifiedKyc(USER_ID));
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    void theSafeCallSkipsUsersOnHold() {
        verifiedKyc();
        when(redisTemplate.hasKey(WalletOpeningService.HOLD_KEY + USER_ID)).thenReturn(true);

        assertTrue(service.tryOpenFromVerifiedKyc(USER_ID, "test").isEmpty());
        verify(walletService, never()).createWalletForUser(any());
    }

    @Test
    void theBvnCheckTriesEvenDuringAWait() {
        verifiedKyc();
        when(redisTemplate.hasKey(WalletOpeningService.HOLD_KEY + USER_ID)).thenReturn(true);
        Wallet opened = wallet("2000004444");
        when(walletService.createWalletForUser(any(User.class))).thenReturn(opened);

        assertSame(opened, service.openAfterBvnCheck(USER_ID).wallet());
    }

    @Test
    void theBvnCheckNeverFailsBecauseOfTheBank() {
        verifiedKyc();
        when(walletService.createWalletForUser(any(User.class))).thenThrow(new RuntimeException("down"));

        var outcome = service.openAfterBvnCheck(USER_ID);
        assertTrue(!outcome.isOpened());
        assertEquals("WALLET_CREATION_FAILED", outcome.code());
        assertTrue(outcome.retryable(), "a bank outage is worth retrying");
    }

    @Test
    void aNameMismatchTellsTheCustomerWhatToDoAndIsNotRetryable() {
        verifiedKyc();
        when(walletService.createWalletForUser(any(User.class)))
                .thenThrow(WalletProvisioningException.kycIdentityMismatch("Names do not match"));

        var response = service.openAfterBvnCheck(USER_ID).toResponse();

        assertEquals("FAILED", response.get("status"));
        assertEquals("WALLET_KYC_NAME_MISMATCH", response.get("code"));
        assertEquals(false, response.get("retryable"));
        String message = (String) response.get("message");
        assertTrue(message.contains("contact support"), message);
        assertTrue(!message.contains("Names do not match"), "the bank's own words stay internal");
    }

    @Test
    void aMissingPhoneNumberIsNotRetryable() {
        verifiedKyc();
        user.setPhone(null);

        var response = service.openAfterBvnCheck(USER_ID).toResponse();

        assertEquals("WALLET_PHONE_REQUIRED", response.get("code"));
        assertEquals(false, response.get("retryable"));
    }

    @Test
    void anOpenedWalletSaysSoAndNothingElse() {
        verifiedKyc();
        when(walletService.createWalletForUser(any(User.class))).thenReturn(wallet("2000005555"));

        var response = service.openAfterBvnCheck(USER_ID).toResponse();

        assertEquals(java.util.Map.of("status", "OPENED"), response);
    }

    @Test
    void theSafeCallNeverThrows() {
        verifiedKyc();
        when(walletService.createWalletForUser(any(User.class))).thenThrow(new RuntimeException("down"));

        assertTrue(service.tryOpenFromVerifiedKyc(USER_ID, "test").isEmpty());
    }

    @Test
    void failedOpeningsListOnlyWalletsThatFailedWithTheBanksReason() {
        user.getProfileData().put("name", "Joshua Mathew");
        User neverTried = new User();
        neverTried.setId(8L);
        when(userRepository.findVerifiedUserIdsWithoutFundingAccount(50)).thenReturn(List.of(USER_ID, 8L));
        when(valueOps.get(WalletOpeningService.ATTEMPTS_KEY + USER_ID)).thenReturn("5");
        when(valueOps.get(WalletOpeningService.LAST_ERROR_KEY + USER_ID))
                .thenReturn("WALLET_KYC_NAME_MISMATCH: Names do not match");
        when(valueOps.get(WalletOpeningService.ATTEMPTS_KEY + 8L)).thenReturn(null);

        var rows = service.failedOpenings(50);

        assertEquals(1, rows.size(), "a wallet that never failed is not a to-do");
        assertEquals("Joshua Mathew", rows.get(0).get("customerName"));
        assertEquals(5, rows.get(0).get("attempts"));
        assertEquals("WALLET_KYC_NAME_MISMATCH: Names do not match", rows.get(0).get("lastError"));
    }

    @Test
    void theFailureReasonKeepsTheBanksWords() {
        assertEquals("WALLET_KYC_NAME_MISMATCH: Names do not match", WalletOpeningService.failureText(
                WalletProvisioningException.kycIdentityMismatch("Names do not match")));
        assertEquals("down", WalletOpeningService.failureText(new RuntimeException("down")));
    }

    @Test
    void theRetryJobOpensEachStuckUser() {
        verifiedKyc();
        when(userRepository.findVerifiedUserIdsWithoutFundingAccount(25)).thenReturn(List.of(USER_ID));
        when(walletService.createWalletForUser(any(User.class))).thenReturn(wallet("2000003333"));

        service.retryStuckOpenings();

        verify(walletService).createWalletForUser(any(User.class));
    }

    @Test
    void holdGrowsAndStopsAtADay() {
        RuntimeException down = new RuntimeException("down");
        assertEquals(Duration.ofMinutes(15), WalletOpeningService.holdAfter(1, down));
        assertEquals(Duration.ofMinutes(30), WalletOpeningService.holdAfter(2, down));
        assertEquals(Duration.ofHours(4), WalletOpeningService.holdAfter(5, down));
        assertEquals(Duration.ofHours(24), WalletOpeningService.holdAfter(30, down));
        assertEquals(Duration.ofHours(24), WalletOpeningService.holdAfter(1,
                WalletProvisioningException.kycIdentityMismatch("name")));
    }

    private void verifiedKyc() {
        KycProfile kyc = new KycProfile();
        kyc.setBvn(BVN);
        kyc.setBvnVerified(true);
        kyc.setKycStatus(KycProfile.KycStatus.VERIFIED);
        kyc.setFirstName("ADA");
        kyc.setLastName("OKAFOR");
        kyc.setDateOfBirth("1995-04-12");
        when(kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(USER_ID)).thenReturn(Optional.of(kyc));
    }

    private static Wallet wallet(String number) {
        Wallet wallet = new Wallet();
        wallet.setAccountNumber(number);
        wallet.setBankName("Rubies MFB");
        wallet.setBalance(BigDecimal.ZERO);
        return wallet;
    }
}
