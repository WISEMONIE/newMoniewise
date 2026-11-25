package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.KycProfile;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.exception.WalletProvisioningException;
import com.moniewise.moniewise_backend.repository.KycProfileRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The one place a customer's Rubies account is opened.
 *
 * <p>A wallet opens from the verified KYC profile, never from what a single
 * request happens to carry. So a BVN that was verified once always leads to
 * an account: right after the BVN check, on the next wallet read, or on the
 * retry job below if the bank was down.
 */
@Service
public class WalletOpeningService {

    private static final Logger logger = LoggerFactory.getLogger(WalletOpeningService.class);

    static final String ATTEMPTS_KEY = "wallet:open:attempts:";
    static final String HOLD_KEY = "wallet:open:hold:";
    static final String LAST_ERROR_KEY = "wallet:open:last-error:";

    /** After this many failures the website admin is told, once. */
    static final int ADMIN_ALERT_ATTEMPTS = 5;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @Lazy
    private com.moniewise.moniewise_backend.aml.AmlNotifier amlNotifier;

    private static final Duration FIRST_HOLD = Duration.ofMinutes(15);
    private static final Duration MAX_HOLD = Duration.ofHours(24);
    private static final Duration ATTEMPTS_TTL = Duration.ofDays(14);

    private final UserRepository userRepository;
    private final KycProfileRepository kycProfileRepository;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final NotificationService notificationService;
    private final ActivationJourneyNudgeService activationJourneyNudgeService;
    private final StringRedisTemplate redisTemplate;

    @Value("${moniewise.wallet-opening.retry.enabled:true}")
    private boolean retryEnabled = true;

    @Value("${moniewise.wallet-opening.retry.batch-size:25}")
    private int retryBatchSize = 25;

    public WalletOpeningService(UserRepository userRepository,
                                KycProfileRepository kycProfileRepository,
                                WalletRepository walletRepository,
                                @Lazy WalletService walletService,
                                NotificationService notificationService,
                                ActivationJourneyNudgeService activationJourneyNudgeService,
                                StringRedisTemplate redisTemplate) {
        this.userRepository = userRepository;
        this.kycProfileRepository = kycProfileRepository;
        this.walletRepository = walletRepository;
        this.walletService = walletService;
        this.notificationService = notificationService;
        this.activationJourneyNudgeService = activationJourneyNudgeService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Opens the account for a user whose BVN is verified, or returns the one
     * they already have. Throws {@link WalletProvisioningException} with a
     * customer-facing message when it cannot.
     */
    public Wallet openFromVerifiedKyc(Long userId) {
        Optional<Wallet> ready = fundingWallet(userId);
        if (ready.isPresent()) {
            return ready.get();
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        KycProfile kyc = verifiedKyc(userId)
                .orElseThrow(WalletProvisioningException::missingVerifiedKycData);

        if (isBlank(user.getBvn())) {
            user.setBvn(kyc.getBvn());
        } else if (!user.getBvn().trim().equals(kyc.getBvn().trim())) {
            logger.error("[WalletOpen] BVN on user={} does not match the verified KYC profile", userId);
            throw WalletProvisioningException.missingVerifiedKycData();
        }
        if (isBlank(user.getPhone())) {
            throw WalletProvisioningException.phoneRequired();
        }

        applyVerifiedIdentity(user, kyc);
        User saved = userRepository.save(user);

        try {
            Wallet wallet = walletService.createWalletForUser(saved);
            clearRetryState(userId);
            logger.info("[WalletOpen] Account {} opened for user={}", wallet.getAccountNumber(), userId);
            afterOpened(saved, wallet);
            return wallet;
        } catch (WalletProvisioningException e) {
            if (!WalletProvisioningException.OPENING_IN_PROGRESS.equals(e.getCode())) {
                recordFailure(userId, e);
            }
            throw e;
        } catch (RuntimeException e) {
            recordFailure(userId, e);
            throw WalletProvisioningException.providerUnavailable(e.getMessage());
        }
    }

    /**
     * What happened to the wallet during a BVN check, for the Open Wallet
     * screen. The message is safe to show the customer; retryable says
     * whether "Try again" can help, or whether something else must change
     * first (their phone number, or their BVN name with support).
     */
    public record OpeningOutcome(Wallet wallet, String code, String message, boolean retryable) {

        static OpeningOutcome opened(Wallet wallet) {
            return new OpeningOutcome(wallet, null, null, false);
        }

        static OpeningOutcome failed(WalletProvisioningException e) {
            return new OpeningOutcome(null, e.getCode(), e.getUserMessage(), RETRYABLE.contains(e.getCode()));
        }

        public boolean isOpened() {
            return wallet != null;
        }

        /** The "wallet" part of the BVN check response. */
        public java.util.Map<String, Object> toResponse() {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("status", isOpened() ? "OPENED" : "FAILED");
            if (!isOpened()) {
                body.put("code", code);
                body.put("message", message);
                body.put("retryable", retryable);
            }
            return body;
        }
    }

    /** Failures where trying again later can work. The rest need something fixed first. */
    static final java.util.Set<String> RETRYABLE = java.util.Set.of(
            "WALLET_CREATION_FAILED", WalletProvisioningException.OPENING_IN_PROGRESS);

    /**
     * Called by the BVN check on the Open Wallet screen, the one place a user
     * asks for an account. It tries even during a wait after a failure,
     * because the user tapped for it. It never fails the BVN check; the
     * outcome tells the screen what happened and what the customer can do.
     */
    public OpeningOutcome openAfterBvnCheck(Long userId) {
        try {
            return OpeningOutcome.opened(openFromVerifiedKyc(userId));
        } catch (WalletProvisioningException e) {
            logger.warn("[WalletOpen] BVN check could not open a wallet for user={}: {}", userId, e.getCode());
            return OpeningOutcome.failed(e);
        } catch (RuntimeException e) {
            logger.warn("[WalletOpen] BVN check could not open a wallet for user={}: {}", userId, e.getMessage());
            return OpeningOutcome.failed(WalletProvisioningException.providerUnavailable(e.getMessage()));
        }
    }

    /**
     * Same as {@link #openFromVerifiedKyc} for callers that must not fail,
     * such as the BVN check and the wallet read. Skips users on hold after a
     * recent failure, so a busy screen does not keep calling the bank.
     */
    public Optional<Wallet> tryOpenFromVerifiedKyc(Long userId, String trigger) {
        if (userId == null) {
            return Optional.empty();
        }
        Optional<Wallet> ready = fundingWallet(userId);
        if (ready.isPresent()) {
            return ready;
        }
        if (verifiedKyc(userId).isEmpty() || isOnHold(userId)) {
            return Optional.empty();
        }
        try {
            return Optional.of(openFromVerifiedKyc(userId));
        } catch (RuntimeException e) {
            logger.warn("[WalletOpen] {} could not open a wallet for user={}: {}", trigger, userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Picks up anyone left with a verified BVN and no account: the bank was
     * down, the app closed mid-way, or a request timed out. Each failure waits
     * longer before the next try, up to a day.
     */
    @Scheduled(fixedDelayString = "${moniewise.wallet-opening.retry.delay-ms:600000}",
            initialDelayString = "${moniewise.wallet-opening.retry.initial-delay-ms:120000}")
    public void retryStuckOpenings() {
        if (!retryEnabled) {
            return;
        }
        List<Long> stuck = userRepository.findVerifiedUserIdsWithoutFundingAccount(retryBatchSize);
        if (stuck.isEmpty()) {
            return;
        }
        int opened = 0;
        for (Long userId : stuck) {
            if (tryOpenFromVerifiedKyc(userId, "Retry job").isPresent()) {
                opened++;
            }
        }
        logger.info("[WalletOpen] Retry job checked {} verified user(s) without an account, opened {}",
                stuck.size(), opened);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Optional<Wallet> fundingWallet(Long userId) {
        return walletRepository.findByUserId(userId).filter(WalletOpeningService::hasFundingAccount);
    }

    static boolean hasFundingAccount(Wallet wallet) {
        String number = wallet.getAccountNumber();
        return number != null && number.trim().matches("\\d{10}");
    }

    private Optional<KycProfile> verifiedKyc(Long userId) {
        return kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(userId)
                .filter(KycProfile::isBvnVerified)
                .filter(k -> k.getKycStatus() == KycProfile.KycStatus.VERIFIED)
                .filter(k -> !isBlank(k.getBvn()));
    }

    /**
     * Rubies checks the name and date of birth against the BVN record, so the
     * verified KYC values win. The display name is only filled when missing.
     */
    static void applyVerifiedIdentity(User user, KycProfile kyc) {
        Map<String, Object> profile = user.getProfileData() != null
                ? new HashMap<>(user.getProfileData())
                : new HashMap<>();

        putIfPresent(profile, "bvnFirstName", kyc.getFirstName());
        putIfPresent(profile, "bvnLastName", kyc.getLastName());
        putIfPresent(profile, "dateOfBirth",
                com.moniewise.moniewise_backend.utils.DateOfBirth.toIso(kyc.getDateOfBirth()));

        if (isBlank(asText(profile.get("firstName"))) && !isBlank(kyc.getFirstName())
                && !isBlank(kyc.getLastName())) {
            String first = displayName(kyc.getFirstName());
            String last = displayName(kyc.getLastName());
            profile.put("firstName", first);
            profile.put("lastName", last);
            profile.put("name", first + " " + last);
        }
        user.setProfileData(profile);
    }

    private void afterOpened(User user, Wallet wallet) {
        try {
            activationJourneyNudgeService.nudgeAfterWalletCreated(user.getId());
        } catch (Exception e) {
            logger.warn("[WalletOpen] Nudge failed for user={}: {}", user.getId(), e.getMessage());
        }
        String firstName = firstWord(asText(user.getProfileData() != null
                ? user.getProfileData().get("name") : null));
        CompletableFuture.runAsync(() -> {
            try {
                notificationService.sendWelcomeEmail(
                        user.getEmail(),
                        firstName,
                        wallet.getAccountNumber(),
                        wallet.getBankName(),
                        wallet.getBalance());
            } catch (Exception e) {
                logger.error("[WalletOpen] Welcome email failed for {}: {}", user.getEmail(), e.getMessage());
            }
        });
    }

    private boolean isOnHold(Long userId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(HOLD_KEY + userId));
        } catch (Exception e) {
            return false;
        }
    }

    private void recordFailure(Long userId, Exception cause) {
        try {
            Long attempts = redisTemplate.opsForValue().increment(ATTEMPTS_KEY + userId);
            redisTemplate.expire(ATTEMPTS_KEY + userId, ATTEMPTS_TTL);
            Duration hold = holdAfter(attempts == null ? 1 : attempts, cause);
            redisTemplate.opsForValue().set(HOLD_KEY + userId, "1", hold);
            redisTemplate.opsForValue().set(LAST_ERROR_KEY + userId, failureText(cause), ATTEMPTS_TTL);
            logger.warn("[WalletOpen] Attempt {} failed for user={}, next try in {} min: {}",
                    attempts, userId, hold.toMinutes(), cause.getMessage());
            if (attempts != null && attempts >= ADMIN_ALERT_ATTEMPTS) {
                logger.error("[WalletOpen] user={} still has no account after {} attempts. Needs support review.",
                        userId, attempts);
            }
            if (attempts != null && attempts == ADMIN_ALERT_ATTEMPTS && amlNotifier != null) {
                amlNotifier.adminsWalletNeedsFixing();
            }
        } catch (Exception e) {
            logger.debug("[WalletOpen] Could not record failure for user={}: {}", userId, e.getMessage());
        }
    }

    static Duration holdAfter(long attempts, Exception cause) {
        if (cause instanceof WalletProvisioningException wpe
                && "WALLET_KYC_NAME_MISMATCH".equals(wpe.getCode())) {
            return MAX_HOLD;
        }
        long factor = 1L << Math.min(Math.max(attempts - 1, 0), 7);
        Duration hold = FIRST_HOLD.multipliedBy(factor);
        return hold.compareTo(MAX_HOLD) > 0 ? MAX_HOLD : hold;
    }

    /** What the admin site shows for a failed opening: the bank's reason when there is one. */
    static String failureText(Exception cause) {
        if (cause instanceof WalletProvisioningException wpe) {
            String bank = wpe.getProviderMessage();
            return wpe.getCode() + (bank == null || bank.isBlank() ? "" : ": " + bank);
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    /**
     * Verified customers whose wallet has failed to open at least once, for
     * the admin site's to-do list. Reads attempts and the last reason from
     * Redis; Redis being down just leaves those fields empty.
     */
    public List<java.util.Map<String, Object>> failedOpenings(int limit) {
        List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Long userId : userRepository.findVerifiedUserIdsWithoutFundingAccount(limit)) {
            String attempts = redisValue(ATTEMPTS_KEY + userId);
            if (attempts == null) {
                continue; // not tried yet, or never failed: the retry job will handle it
            }
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                continue;
            }
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("userId", userId);
            Object name = user.getProfileData() != null ? user.getProfileData().get("name") : null;
            row.put("customerName", name == null ? "" : name.toString());
            row.put("email", user.getEmail() == null ? "" : user.getEmail());
            row.put("attempts", Integer.parseInt(attempts.trim()));
            row.put("lastError", redisValue(LAST_ERROR_KEY + userId));
            row.put("waitingForNextTry", isOnHold(userId));
            rows.add(row);
        }
        return rows;
    }

    private String redisValue(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private void clearRetryState(Long userId) {
        try {
            redisTemplate.delete(List.of(ATTEMPTS_KEY + userId, HOLD_KEY + userId, LAST_ERROR_KEY + userId));
        } catch (Exception ignored) {
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (!isBlank(value)) {
            map.put(key, value.trim());
        }
    }

    private static String displayName(String raw) {
        String text = raw.trim().toLowerCase();
        StringBuilder out = new StringBuilder(text.length());
        boolean upper = true;
        for (char c : text.toCharArray()) {
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = c == ' ' || c == '-' || c == '\'';
        }
        return out.toString();
    }

    private static String firstWord(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
