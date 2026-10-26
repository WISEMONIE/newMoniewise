package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.firebase.cloud.StorageClient;
import com.moniewise.moniewise_backend.dto.PendingRegistrationData;
import com.moniewise.moniewise_backend.dto.request.ProfileRequest;
import com.moniewise.moniewise_backend.dto.response.BvnVerificationResultDto;
import com.moniewise.moniewise_backend.dto.response.UserSummaryResponse;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.enums.Gender;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import com.moniewise.moniewise_backend.psp.ProvidusExpressGateway;
import com.moniewise.moniewise_backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Service
public class UserService implements UserDetailsService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    /** The ₦100 withdrawal minimum. A wallet balance below this is un-withdrawable
     *  dust (the withdraw-to-bank flow rejects sub-₦100 and its fee would exceed
     *  it), so it must not permanently block account deletion. */
    private static final BigDecimal MIN_WITHDRAWABLE_BALANCE = new BigDecimal("100");
    private static final int P2P_SEARCH_LIMIT = 15;
    private static final int P2P_SEARCH_MIN_LENGTH = 2;
    private static final int P2P_SEARCH_MAX_LENGTH = 64;
    private static final long P2P_SEARCH_CACHE_TTL_SECONDS = 30L;
    private static final String P2P_SEARCH_CACHE_PREFIX = "p2p:user_search:";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // Now sourced from AppConfig
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final OtpService otpService;

    private final PasswordResetTokenRepository passwordResetTokenRepository;

    // Add to class dependencies
    private final NotificationService notificationService;
    private final ActivationJourneyNudgeService activationJourneyNudgeService;

    private final BudgetRepository budgetRepository;
    private final SavingsGoalRepository savingsGoalRepository;

    private final RegistrationCacheService registrationCacheService;
    private final ProvidusExpressGateway providusExpressGateway;
    private final KycProfileRepository kycProfileRepository;
    private final KycService kycService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ReferralService referralService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, WalletService walletService, WalletRepository walletRepository, WalletService walletService1, OtpService otpService, PasswordResetTokenRepository passwordResetTokenRepository, NotificationService notificationService, ActivationJourneyNudgeService activationJourneyNudgeService, BudgetRepository budgetRepository, RegistrationCacheService registrationCacheService, ProvidusExpressGateway providusExpressGateway, KycProfileRepository kycProfileRepository, StringRedisTemplate redisTemplate, ObjectMapper objectMapper, SavingsGoalRepository savingsGoalRepository, KycService kycService, ReferralService referralService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder; // No link to SecurityConfig
        this.walletRepository = walletRepository;
        this.walletService = walletService1;
        this.otpService = otpService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.notificationService = notificationService;
        this.activationJourneyNudgeService = activationJourneyNudgeService;
        this.budgetRepository = budgetRepository;
        this.savingsGoalRepository = savingsGoalRepository;
        this.registrationCacheService = registrationCacheService;
        this.providusExpressGateway = providusExpressGateway;
        this.kycProfileRepository = kycProfileRepository;
        this.kycService = kycService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.referralService = referralService;
    }

    /**
     * Stage-1 of the new two-step signup flow.
     *
     * <p>The user's credentials are stored <b>only in Redis</b> (with a 10-minute TTL)
     * and a 6-digit OTP is emailed.  Nothing is written to PostgreSQL at this stage,
     * so no "ghost" unverified accounts accumulate in the database.
     *
     * <p>Stage-2 is {@link #createUserFromPendingRegistration(String, String)}, called
     * when the client submits the correct OTP via {@code POST /auth/verify-signup-otp}.
     *
     * <p>If the same email is submitted again while its Redis key is still live
     * (e.g. user did not receive the email), the OTP is refreshed and re-sent.
     *
     * @param email    the user's email address
     * @param phone    the user's phone number
     * @param password the plain-text password (will be BCrypt-encoded before storage)
     */
    public void signup(String email, String phone, String password) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Phone number is required.");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password is required.");
        }

        email = email.toLowerCase().trim();
        phone = phone.trim();

        // 1. Guard: reject if a fully-verified account already exists in the DB ──────
        Optional<User> existingUser = userRepository.findGlobalByEmail(email);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (user.isDeleted()) {
                throw new IllegalArgumentException("This email is linked to a permanently closed account and can't be used to sign up again. Please contact support if you need help.");
            }
            throw new IllegalArgumentException("Email already registered: " + email);
        }

        Optional<User> existingPhone = userRepository.findGlobalByPhone(phone);
        if (existingPhone.isPresent()) {
            if (existingPhone.get().isDeleted()) {
                throw new IllegalArgumentException("This phone number is linked to a permanently closed account and can't be used to sign up again. Please contact support if you need help.");
            }
            throw new IllegalArgumentException("An account with the phone number '" + phone + "' already exists.");
        }

        // 2. Build the pending record ──────────────────────────────────────────────
        String encodedPassword = passwordEncoder.encode(password);

        // 6-digit OTP, expires in 5 minutes
        SecureRandom random = new SecureRandom();
        String otpCode = String.valueOf(random.nextInt(900000) + 100000);
        long expiresAt = System.currentTimeMillis() + (5L * 60 * 1000);

        PendingRegistrationData pending = new PendingRegistrationData(
                email,
                phone,
                encodedPassword,
                otpCode,
                expiresAt
        );
        final String signupEmail = email;

        // 3. Store in Redis (overwrites any previous pending entry for this email) ──
        registrationCacheService.save(pending);

        // 4. Send OTP email asynchronously (mirrors existing OtpService behaviour) ──
        CompletableFuture.runAsync(() -> {
            try {
                notificationService.sendOtpEmail(signupEmail, otpCode);
            } catch (Exception ex) {
                logger.error("Failed to send signup OTP email to {}: {}", signupEmail, ex.getMessage());
            }
        });

        logger.info("Signup initiated for {} — OTP sent, pending Redis key created.", email);
    }

    /**
     * Regenerates and resends the signup OTP for an existing pending registration.
     *
     * <p>The pending record must still be alive in Redis (i.e. the original signup
     * was called within the last {@code RegistrationCacheService.TTL_MINUTES} minutes).
     * If the Redis key has expired the user must restart the signup flow.
     *
     * <p>A fresh 6-digit OTP replaces the previous one and the TTL is reset, giving
     * the user another full window to verify.
     *
     * @param email the email address used during {@link #signup}
     * @throws IllegalArgumentException if no pending registration exists for the email
     */
    public void resendSignupOtp(String email) {
        PendingRegistrationData existing = registrationCacheService
                .findByEmail(email.toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No pending registration found for this email. Please sign up again."));

        // Generate a fresh OTP and reset the TTL
        SecureRandom random = new SecureRandom();
        String newOtp = String.valueOf(random.nextInt(900000) + 100000);
        long newExpiry = System.currentTimeMillis() + (5L * 60 * 1000);

        PendingRegistrationData refreshed = new PendingRegistrationData(
                existing.getEmail(),
                existing.getPhone(),
                existing.getEncodedPassword(),
                newOtp,
                newExpiry
        );
        registrationCacheService.save(refreshed);

        // Send the new OTP email asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                notificationService.sendOtpEmail(existing.getEmail(), newOtp);
            } catch (Exception ex) {
                logger.error("Failed to resend signup OTP email to {}: {}", existing.getEmail(), ex.getMessage());
            }
        });

        logger.info("Signup OTP resent for {} — new OTP generated and Redis TTL reset.", email);
    }

    /**
     * Stage-2 of the two-step signup flow.
     *
     * <p>Validates the OTP submitted by the client, then atomically pulls the
     * pending registration data from Redis and persists a new {@link User} to
     * PostgreSQL.  The Redis key is deleted on success.
     *
     * @param email   the email address used during stage-1 signup
     * @param otpCode the 6-digit OTP the user received by email
     * @return the newly persisted {@link User}
     * @throws IllegalArgumentException if no pending registration is found, the OTP
     *                                  is wrong, or the OTP has expired
     */
    @Transactional
    public User createUserFromPendingRegistration(String email, String otpCode) {

        // 1. Fetch pending data from Redis ─────────────────────────────────────────
        PendingRegistrationData pending = registrationCacheService
                .findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No pending registration found for this email. " +
                        "The OTP may have expired — please sign up again."));

        // 2. Validate OTP ──────────────────────────────────────────────────────────
        if (!pending.getOtpCode().equals(otpCode)) {
            throw new IllegalArgumentException("Invalid OTP code.");
        }
        if (System.currentTimeMillis() > pending.getOtpExpiresAtEpochMillis()) {
            registrationCacheService.delete(email); // clean up expired key
            throw new IllegalArgumentException("OTP has expired. Please sign up again to request a new code.");
        }

        // 3. One final duplicate guard (handles race conditions) ──────────────────
        Optional<User> raceEmail = userRepository.findGlobalByEmail(email);
        if (raceEmail.isPresent()) {
            registrationCacheService.delete(email);
            if (raceEmail.get().isDeleted()) {
                throw new IllegalArgumentException("This email is linked to a permanently closed account and can't be used to sign up again.");
            }
            throw new IllegalArgumentException("Email already registered: " + email);
        }
        if (pending.getPhone() == null || pending.getPhone().isBlank()) {
            registrationCacheService.delete(email);
            throw new IllegalArgumentException("Phone number is required. Please sign up again.");
        }
        Optional<User> racePhone = userRepository.findGlobalByPhone(pending.getPhone());
        if (racePhone.isPresent()) {
            registrationCacheService.delete(email);
            if (racePhone.get().isDeleted()) {
                throw new IllegalArgumentException("This phone number is linked to a permanently closed account and can't be used to sign up again.");
            }
            throw new IllegalArgumentException("An account with the phone number '" + pending.getPhone() + "' already exists.");
        }

        // 4. Persist user to PostgreSQL ───────────────────────────────────────────
        User user = new User();
        user.setEmail(pending.getEmail());
        user.setPhone(pending.getPhone());
        user.setPassword(pending.getEncodedPassword()); // already BCrypt-encoded
        user.setRole(Role.USER);
        user.setProfileData(new HashMap<>());
        user.setVerified(true);  // OTP just verified — mark immediately as verified
        user.setCreatedAt(LocalDateTime.now());
        user.setTncAccepted(false);

        // Carry across BVN if the pre-verify step was completed during signup
        if (pending.getBvn() != null && !pending.getBvn().isBlank()) {
            Optional<User> bvnOwner = userRepository.findGlobalByBvn(pending.getBvn());
            if (bvnOwner.isPresent() && !bvnOwner.get().getId().equals(user.getId())) {
                if (bvnOwner.get().isDeleted()) {
                    throw new IllegalArgumentException("This BVN is linked to a permanently closed account and can't be used to sign up again. Please contact support if you need help.");
                }
                throw new IllegalArgumentException("This BVN is already linked to another account.");
            }
            user.setBvn(pending.getBvn());
        }

        User savedUser = userRepository.save(user);

        // 4b. If BVN was pre-verified, create the KycProfile now so the user
        //     starts their session already verified — no second prompt needed
        BvnVerificationResultDto bvnResult = pending.getBvnVerificationResult();
        if (bvnResult != null && pending.getBvn() != null) {
            try {
                KycProfile profile = new KycProfile();
                profile.setUser(savedUser);
                profile.setBvn(pending.getBvn());
                profile.setBvnVerified(true);
                profile.setKycStatus(KycProfile.KycStatus.VERIFIED);
                profile.setNameOnCard(bvnResult.getNameOnCard());
                profile.setEnrolmentBank(bvnResult.getEnrolmentBank());
                profile.setEnrolmentBranch(bvnResult.getEnrolmentBranch());
                profile.setFormattedRegistrationDate(bvnResult.getFormattedRegistrationDate());
                profile.setLevelOfAccount(bvnResult.getLevelOfAccount());
                profile.setNin(bvnResult.getNin());
                profile.setWatchlisted(bvnResult.getWatchlisted());
                profile.setBvnVerificationStatus(bvnResult.getVerificationStatus());
                profile.setFirstName(bvnResult.getFirstName());
                profile.setMiddleName(bvnResult.getMiddleName());
                profile.setLastName(bvnResult.getLastName());
                profile.setGender(bvnResult.getGender());
                profile.setDateOfBirth(bvnResult.getDateOfBirth());
                profile.setStateOfOrigin(bvnResult.getStateOfOrigin());
                profile.setLgaOfOrigin(bvnResult.getLgaOfOrigin());
                profile.setNationality(bvnResult.getNationality());
                profile.setMaritalStatus(bvnResult.getMaritalStatus());
                profile.setStateOfResidence(bvnResult.getStateOfResidence());
                profile.setLgaOfResidence(bvnResult.getLgaOfResidence());
                profile.setResidentialAddress(bvnResult.getResidentialAddress());
                kycProfileRepository.save(profile);
                logger.info("KycProfile created from pre-verified BVN for user {}", email);
            } catch (Exception e) {
                // Never block account creation because of a KYC save failure
                logger.error("Failed to persist pre-verified KycProfile for {} — manual remediation required: {}",
                        email, e.getMessage());
            }
        }

        // 5. Clean up Redis ────────────────────────────────────────────────────────
        registrationCacheService.delete(email);

        logger.info("User {} created from pending registration — OTP verified successfully.", email);
        return savedUser;
    }

    public Optional<User> findByEmailOrPhone(String input) {
        // We pass the input twice because we are saying: "Find where Email is X OR Phone is X"
        return userRepository.findByEmailOrPhone(input, input);
    }

    /**
     * Check if the user has a PIN set.
     */
    public boolean hasPin(User user) {
        return user.getTransactionPin() != null && !user.getTransactionPin().isEmpty();
    }

    /**
     * Create or Update the Transaction PIN.
     */
    @Transactional
    public void createTransactionPin(User user, String pin, String confirmPin) {
        validateNewPin(pin, confirmPin);
        user.setTransactionPin(passwordEncoder.encode(pin));
        userRepository.save(user);
    }

    /**
     * Validate a PIN before a transfer (Returns true/false).
     */
    public boolean verifyTransactionPin(User user, String rawPin) {
        if (!hasPin(user)) {
            throw new IllegalStateException("User has not set a transaction PIN");
        }
        return passwordEncoder.matches(rawPin, user.getTransactionPin());
    }

    @Transactional
    public void changeTransactionPin(User user, String currentPin, String newPin) {
        if (!hasPin(user)) {
            throw new IllegalStateException("User has not set a transaction PIN");
        }
        if (currentPin == null || currentPin.isBlank()) {
            throw new IllegalArgumentException("Current PIN is required");
        }
        if (!passwordEncoder.matches(currentPin, user.getTransactionPin())) {
            throw new IllegalArgumentException("Current PIN is incorrect");
        }
        validatePin(newPin);
        if (passwordEncoder.matches(newPin, user.getTransactionPin())) {
            throw new IllegalArgumentException("New PIN must be different from current PIN");
        }

        user.setTransactionPin(passwordEncoder.encode(newPin));
        userRepository.save(user);
    }

    @Transactional
    public void resetTransactionPin(User user, String newPin, String confirmNewPin) {
        validateNewPin(newPin, confirmNewPin);
        if (hasPin(user) && passwordEncoder.matches(newPin, user.getTransactionPin())) {
            throw new IllegalArgumentException("New PIN must be different from current PIN");
        }

        user.setTransactionPin(passwordEncoder.encode(newPin));
        userRepository.save(user);
    }

    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword, String confirmNewPassword) {
        User user = findByEmail(email);

        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("Current password is required");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("New password is required");
        }
        if (!newPassword.equals(confirmNewPassword)) {
            throw new IllegalArgumentException("New passwords do not match");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new IllegalArgumentException("New password must be different from current password");
        }
        if (newPassword.length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    /** Validates a single PIN value — format only, no confirmation. */
    private void validatePin(String pin) {
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("PIN is required");
        }
        if (pin.length() != 4 || !pin.matches("\\d+")) {
            throw new IllegalArgumentException("PIN must be exactly 4 digits");
        }
    }

    /**
     * Validates a new PIN + its confirmation (used by create and forgot-reset
     * flows where the frontend sends confirmPin for an extra server-side check).
     */
    private void validateNewPin(String pin, String confirmPin) {
        validatePin(pin);
        if (confirmPin == null || confirmPin.isBlank()) {
            throw new IllegalArgumentException("PIN confirmation is required");
        }
        if (!pin.equals(confirmPin)) {
            throw new IllegalArgumentException("PINs do not match");
        }
    }

    //=======================FIREBASE RECIEVE TOKEN=================
    @Transactional
    public void updateFcmToken(String email, String token) {
        User user = findByEmail(email);
        String normalizedToken = normalizeFcmToken(token);
        if (normalizedToken != null) {
            userRepository.clearFcmTokenByToken(normalizedToken);
        }
        user.setFcmToken(normalizedToken);
        userRepository.save(user);
    }

    @Transactional
    public void clearFcmTokenIfMatches(String email, String token) {
        String normalizedToken = normalizeFcmToken(token);
        if (normalizedToken == null) {
            return;
        }
        userRepository.clearFcmTokenByEmailAndToken(email, normalizedToken);
    }

    private String normalizeFcmToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return token.trim();
    }
    //==============================================================

    // ==============================================================
    // âœ… SAFE SEARCH (Fixes Memory Crash)
//     ==============================================================
    /** Called by controller without a provider filter (backward-compatible). */
    public List<UserSummaryResponse> searchUsers(String query, String currentEmail) {
        return searchUsers(query, currentEmail, null);
    }

    /**
     * Search users, optionally filtered to a specific PSP provider.
     * When {@code provider} is non-blank (e.g. "RUBIES"), only users whose wallet
     * is on that provider are returned — ensuring P2P transfers stay within the
     * same rails.
     */
    public List<UserSummaryResponse> searchUsers(String query, String currentEmail, String provider) {
        String normalizedQuery = normalizeSearchQuery(query);
        if (normalizedQuery.length() < P2P_SEARCH_MIN_LENGTH) {
            return Collections.emptyList();
        }

        // Include provider in cache key so filtered + unfiltered results are stored separately
        String cacheKey = p2pSearchCacheKey(normalizedQuery, currentEmail)
                + (provider != null && !provider.isBlank() ? ":" + provider.toUpperCase() : "");
        List<UserSummaryResponse> cached = readCachedP2pSearch(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Fetch up to the search limit at the DB level to save RAM.
        List<UserSummary> results = userRepository.searchUsers(
                normalizedQuery,
                PageRequest.of(0, P2P_SEARCH_LIMIT)
        );

        final String filterProvider = (provider != null && !provider.isBlank())
                ? provider.toUpperCase() : null;

        List<UserSummaryResponse> response = results.stream()
                .filter(u -> currentEmail == null || !u.getEmail().equalsIgnoreCase(currentEmail))
                .filter(u -> u.getBvn() != null && !u.getBvn().isBlank())
                // PSP filter: skip users whose wallet is on a different provider
                .filter(u -> {
                    if (filterProvider == null) return true;
                    String wp = u.getWalletProviderName();
                    return wp != null && filterProvider.equalsIgnoreCase(wp.trim());
                })
                .map(u -> {
                    String handle = (u.getUserTag() != null && !u.getUserTag().isEmpty())
                            ? u.getUserTag()
                            : "@" + u.getEmail().split("@")[0];

                    String displayName = "Unknown";
                    if (u.getFirstName() != null && !u.getFirstName().isEmpty()) {
                        displayName = (u.getFirstName() + " " + Objects.toString(u.getLastName(), "")).trim();
                    } else {
                        String cleanName = handle.startsWith("@") ? handle.substring(1) : handle;
                        displayName = cleanName.substring(0, 1).toUpperCase() + cleanName.substring(1);
                    }

                    return new UserSummaryResponse(
                            displayName,
                            handle,
                            u.getProfileImageUrl(),
                            u.getEmail(),
                            UserSummaryResponse.WalletMetadata.of(
                                    u.getWalletAccountNumber(),
                                    u.getWalletBankName(),
                                    u.getWalletStatus(),
                                    u.getWalletProviderName()
                            )
                    );
                })
                .collect(Collectors.toList());
        cacheP2pSearch(cacheKey, response);
        return response;
    }

    private String normalizeSearchQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.trim().replaceAll("\\s+", " ");
        if (normalized.length() > P2P_SEARCH_MAX_LENGTH) {
            normalized = normalized.substring(0, P2P_SEARCH_MAX_LENGTH);
        }
        return normalized;
    }

    private String p2pSearchCacheKey(String query, String currentEmail) {
        String email = currentEmail == null ? "unknown" : currentEmail.trim().toLowerCase(Locale.ROOT);
        return P2P_SEARCH_CACHE_PREFIX + email + ":" + query.toLowerCase(Locale.ROOT);
    }

    private List<UserSummaryResponse> readCachedP2pSearch(String cacheKey) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json == null) {
                return null;
            }
            JavaType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, UserSummaryResponse.class);
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            logger.warn("P2P search cache read failed: {}", e.getMessage());
            return null;
        }
    }

    private void cacheP2pSearch(String cacheKey, List<UserSummaryResponse> response) {
        try {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(response),
                    P2P_SEARCH_CACHE_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (JsonProcessingException e) {
            logger.warn("P2P search cache serialization failed: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("P2P search cache write failed: {}", e.getMessage());
        }
    }

//     In UserService.java


    public User login(String emailOrPhone, String password) {
        User user = userRepository.findFirstByEmailOrderByCreatedAtAsc(emailOrPhone)
                .orElseGet(() -> userRepository.findByPhone(emailOrPhone)
                        .orElseThrow(() -> new RuntimeException("User not found")));

        // Validate the password first, then apply account-state guards.
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        // Closure is permanent and one-way. A closed account can NEVER be signed
        // back into — being let in would mean the account was never really
        // deleted, only hidden. Any balance left behind in a closed wallet is
        // recovered through support, never by logging in.
        if (user.isDeleted()) {
            throw new RuntimeException("This account has been permanently closed.");
        }

        // Check new isVerified column
        if (!user.isVerified()) {
            throw new RuntimeException("OTP verification required");
        }

        // Update lastLogin timestamp
        user.setLastLogin(Instant.now());
        userRepository.save(user); // Save to database

        return user;
    }

    public User findOrCreateOAuthUser(String email, String name) {
        // 1. ðŸ” SEARCH GLOBALLY (Active AND Deleted users)
        // We use the custom method to find users hidden by the @Where clause
        Optional<User> existingUserOpt = userRepository.findGlobalByEmail(email);

        if (existingUserOpt.isPresent()) {
            User user = existingUserOpt.get();

            // 2. ðŸ§Ÿ REACTIVATION CHECK
            // If the user exists but was "Soft Deleted", we bring them back.
            if (user.isDeleted()) {
                logger.info("â™»ï¸ Reactivating returning user: " + email);
                // Closure is permanent — a closed account is NEVER reactivated,
                // not by password login and not through this legacy OAuth path.
                // (Google sign-in is gone from the app, but the /auth/google
                // endpoint still exists, so it must honour finality too instead
                // of silently resurrecting a closed account.)
                throw new IllegalArgumentException("This account has been permanently closed.");
            }

            // If user is already active, just return them.
            return user;
        }

        // 3. ðŸ†• CREATE FRESH USER
        // Only runs if the email has TRULY never been seen before.
        User newUser = new User();
        newUser.setEmail(email);
        newUser.setPassword(passwordEncoder.encode("GOOGLE_AUTH_USER_" + UUID.randomUUID().toString()));
        newUser.setRole(Role.USER);
        newUser.setVerified(true);
        newUser.setCreatedAt(LocalDateTime.now());

        // Default to not deleted
        newUser.setDeleted(false);

        Map<String, Object> profile = new HashMap<>();
        profile.put("name", (name != null && !name.isEmpty()) ? name : "MonieWise User");
        profile.put("auth_provider", "google");

        newUser.setProfileData(profile);

        return userRepository.save(newUser);
    }

    @Transactional
    public User updateProfile(String email, ProfileRequest request) {
        User user = findByEmail(email);

        // ============================================================
        // ðŸ“± 1. GOOGLE SIGNUP PHONE NUMBER CATCHER
        // ============================================================
        if (user.getPhone() == null || user.getPhone().trim().isEmpty()) {
            String newPhone = request.getPhone();

            if (newPhone == null || newPhone.trim().isEmpty()) {
                throw new IllegalArgumentException("Phone number is required to complete your profile.");
            }

            if (userRepository.findByPhone(newPhone).isPresent()) {
                throw new IllegalArgumentException("An account with the phone number '" + newPhone + "' already exists.");
            }

            user.setPhone(newPhone);
        }

        // ============================================================
        // ðŸ¦ 2. KYC DATA FOR SECUREWAVE
        // ============================================================

        // Only set the BVN if it's currently empty.
        // For later profile edits, a blank BVN means "leave the existing BVN unchanged".
        String incomingBvn = request.getBvn() != null ? request.getBvn().trim() : null;
        if (user.getBvn() == null || user.getBvn().trim().isEmpty()) {
            if (incomingBvn == null || incomingBvn.isEmpty()) {
                throw new IllegalArgumentException("BVN is required to complete your profile.");
            }
            Optional<User> bvnOwner = userRepository.findGlobalByBvn(incomingBvn);
            if (bvnOwner.isPresent() && !bvnOwner.get().getId().equals(user.getId())) {
                if (bvnOwner.get().isDeleted()) {
                    throw new IllegalArgumentException("This BVN is linked to a permanently closed account and can't be used to sign up again. Please contact support if you need help.");
                }
                throw new IllegalArgumentException("This BVN is already linked to another account.");
            }
            user.setBvn(incomingBvn);
        } else if (incomingBvn != null && !incomingBvn.isEmpty() && !user.getBvn().equals(incomingBvn)) {
            // If they try to send a different BVN later, reject it.
            throw new IllegalArgumentException("BVN cannot be modified after initial setup. Contact support.");
        }

        // Gender — required on first setup, updatable on edits.
        Gender incomingGender = request.getGender();
        if (incomingGender != null) {
            user.setGender(incomingGender); // Always allow updating gender
        } else if (user.getGender() == null) {
            throw new IllegalArgumentException("Gender is required to complete your profile.");
        }

        Map<String, Object> profileData = user.getProfileData();
        if (profileData == null) profileData = new HashMap<>();

        // ── Name handling — separate KYC (regulatory) from profile (display) ──
        // On FIRST profile setup (wallet doesn't exist yet), the user-provided
        // firstName/lastName are the BVN-verified values from the form.
        // On subsequent EDIT calls, we do NOT overwrite the name — it stays as
        // whatever was set during initial setup (which matches the KYC table).
        // Regulators read from kyc_profiles; profile_data.name is only for display.
        boolean isFirstSetup = !walletRepository.existsByUser(user);
        if (isFirstSetup) {
            profileData.put("name", request.getFirstName() + " " + request.getLastName());
            profileData.put("firstName", request.getFirstName());
            profileData.put("lastName", request.getLastName());
        }
        // Always update user-preference fields (these are never KYC data)
        profileData.put("monthlyIncome", request.getMonthlyIncome());
        profileData.put("mainExpense", request.getMainExpense());
        profileData.put("savingsGoal", request.getSavingsGoal());
        profileData.put("occupation", request.getOccupation());

        if (request.getReferralSource() != null && !request.getReferralSource().isBlank()) {
            user.setReferralSource(request.getReferralSource().trim());
        }
        referralService.attach(user, request.getReferralCode());

//        if (request.getDob() != null) {
//            // Keep the raw list for backward-compatibility with the frontend model.
//            profileData.put("dob", request.getDob());
//
//            // Also store a YYYY-MM-DD string under "dateOfBirth" so that
//            // RubiesGateway.createVirtualAccount() can read it directly.
//            // Rubies validates DOB against the BVN record and needs this exact format.
//            List<?> dob = request.getDob();
//            if (dob.size() >= 3) {
//                String dateOfBirth = String.format("%04d-%02d-%02d",
//                        ((Number) dob.get(0)).intValue(),
//                        ((Number) dob.get(1)).intValue(),
//                        ((Number) dob.get(2)).intValue());
//                profileData.put("dateOfBirth", dateOfBirth);
//            }
//        }

        if (request.getDob() != null) {
            LocalDate dob = request.getDob();

            // Keep backward-compatible frontend format: [year, month, day]
            profileData.put("dob", List.of(
                    dob.getYear(),
                    dob.getMonthValue(),
                    dob.getDayOfMonth()
            ));

            // Store provider-friendly format: YYYY-MM-DD
            profileData.put("dateOfBirth", dob.toString());
        }

        // Prefer the authoritative DOB from BVN verification (already in YYYY-MM-DD
        // format as returned by SecureWave).  This overwrites the self-reported DOB
        // above if the user's KycProfile has been verified, ensuring Rubies gets the
        // exact same DOB that matched their NIBSS BVN record.
//        kycProfileRepository.findByUserId(user.getId()).ifPresent(kyc -> {
//            if (kyc.getDateOfBirth() != null) {
//                profileData.put("dateOfBirth", kyc.getDateOfBirth().toString());
//            }
//            // Also cache BVN-verified name parts for providers (e.g. Rubies) that
//            // require names to match the BVN record within a similarity threshold.
//            if (kyc.getFirstName() != null && !kyc.getFirstName().isBlank()) {
//                profileData.put("bvnFirstName", kyc.getFirstName());
//            }
//            if (kyc.getLastName() != null && !kyc.getLastName().isBlank()) {
//                profileData.put("bvnLastName", kyc.getLastName());
//            }
//        });
//
//        user.setProfileData(profileData);

        final Map<String, Object> finalProfileData = profileData;

        kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId()).ifPresent(kyc -> {
            if (kyc.getDateOfBirth() != null) {
                finalProfileData.put("dateOfBirth", kyc.getDateOfBirth().toString());
            }

            if (kyc.getFirstName() != null && !kyc.getFirstName().isBlank()) {
                finalProfileData.put("bvnFirstName", kyc.getFirstName());
            }

            if (kyc.getLastName() != null && !kyc.getLastName().isBlank()) {
                finalProfileData.put("bvnLastName", kyc.getLastName());
            }
        });

        user.setProfileData(finalProfileData);

        // Save the user entity so it's ready for WalletService
        User savedUser = userRepository.save(user);
        syncProvidusCustomerProfileIfEnabled(savedUser, request);

        // ============================================================
        // âš¡ 3. CHECK & CREATE WALLET (Synchronous)
        // ============================================================
        if (!walletRepository.existsByUser(savedUser)) {
            // Emergency off-switch: comment out this one invocation to skip the
            // new BVN-provider fallback path during onboarding wallet creation.
            ensureVerifiedKycBeforeWalletCreation(savedUser, request);

            logger.info("âš¡ Profile complete. Creating Wallet for: {}", savedUser.getEmail());

            // We do this synchronously. If SecureWave fails, it throws an error to the frontend!
            Wallet newWallet = walletService.createWalletForUser(savedUser);

            // Activation journey off-switch: comment out this one invocation to
            // stop the post-wallet-created fund-wallet push/email.
            activationJourneyNudgeService.nudgeAfterWalletCreated(savedUser.getId());

            // We can keep the EMAIL sending asynchronous, because we don't want the user
            // to wait on an SMTP server to finish loading.
            // Extract first name from profileData for personalised welcome email
            String welcomeFirstName = "";
            try {
                if (savedUser.getProfileData() != null) {
                    Object nameObj = savedUser.getProfileData().get("name");
                    if (nameObj != null) {
                        String fullName = nameObj.toString().trim();
                        welcomeFirstName = fullName.contains(" ")
                                ? fullName.substring(0, fullName.indexOf(" "))
                                : fullName;
                    }
                }
            } catch (Exception ignored) {}
            final String finalFirstName = welcomeFirstName;
            CompletableFuture.runAsync(() -> {
                try {
                    notificationService.sendWelcomeEmail(
                            savedUser.getEmail(),
                            finalFirstName,
                            newWallet.getAccountNumber(),
                            newWallet.getBankName(),
                            newWallet.getBalance()
                    );
                } catch (Exception e) {
                    logger.error("Failed to send welcome email to {}: {}", savedUser.getEmail(), e.getMessage());
                }
            });
        }

        return savedUser;
    }

    private void ensureVerifiedKycBeforeWalletCreation(User user, ProfileRequest request) {
        if (user == null || user.getId() == null || user.getBvn() == null || user.getBvn().isBlank()) {
            return;
        }

        BvnVerificationResultDto result = kycService.verifyBvnWithProvider(user.getId(), user.getBvn());

        Map<String, Object> profileData = user.getProfileData() != null
                ? new HashMap<>(user.getProfileData())
                : new HashMap<>();

        String firstName = firstNonBlank(
                result.getFirstName(),
                request != null ? request.getFirstName() : null,
                stringValue(profileData.get("firstName")));
        String lastName = firstNonBlank(
                result.getLastName(),
                request != null ? request.getLastName() : null,
                stringValue(profileData.get("lastName")));
        String dob = firstNonBlank(
                result.getDateOfBirth(),
                request != null && request.getDob() != null ? request.getDob().toString() : null,
                stringValue(profileData.get("dateOfBirth")));

        putIfPresent(profileData, "bvnFirstName", firstName);
        putIfPresent(profileData, "bvnLastName", lastName);
        putIfPresent(profileData, "dateOfBirth", dob);

        user.setProfileData(profileData);
        userRepository.save(user);
    }

    private void syncProvidusCustomerProfileIfEnabled(User user, ProfileRequest request) {
        if (!providusExpressGateway.isEnabled()) {
            return;
        }

        walletRepository.findByUser(user).ifPresent(wallet -> {
            if (wallet.getProviderName() == null
                    || !wallet.getProviderName().equalsIgnoreCase(ProvidusExpressGateway.PROVIDER_NAME)) {
                return;
            }

            if (wallet.getProviderCustomerRef() == null || wallet.getProviderCustomerRef().isBlank()) {
                logger.warn("Skipping Providus profile sync for {} because providerCustomerRef is missing", user.getEmail());
                return;
            }

            Map<String, Object> updates = new LinkedHashMap<>();
            putIfPresent(updates, "firstName", request.getFirstName());
            putIfPresent(updates, "lastName", request.getLastName());
            putIfPresent(updates, "phoneNumber", user.getPhone());
            if (request.getDob() != null) {
                updates.put("dateOfBirth", request.getDob().toString());
            }

            if (!updates.isEmpty()) {
                providusExpressGateway.updateCustomerProfile(wallet.getProviderCustomerRef(), updates);
            }
        });
    }

    private void putIfPresent(Map<String, Object> updates, String key, String value) {
        if (value != null && !value.isBlank()) {
            updates.put(key, value.trim());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String clean = stringValue(value);
            if (clean != null) {
                return clean;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }
//    @Transactional
//    public User updateProfile(String email, ProfileRequest request) {
//        User user = findByEmail(email);
//
//        // Save BVN directly to the entity column
//        user.setBvn(request.getBvn());
//
//        // 1. Save Profile Data
//        Map<String, Object> profileData = user.getProfileData();
//        if (profileData == null) profileData = new HashMap<>();
//
//        profileData.put("name", request.getFirstName() + " " + request.getLastName());
//        profileData.put("firstName", request.getFirstName());
//        profileData.put("lastName", request.getLastName());
//        profileData.put("monthlyIncome", request.getMonthlyIncome());
//        profileData.put("mainExpense", request.getMainExpense());
//        profileData.put("savingsGoal", request.getSavingsGoal());
//        profileData.put("occupation", request.getOccupation());
//        if(request.getDob() != null){
//            profileData.put("dob", request.getDob());
//        }
//        user.setProfileData(profileData);
//
//        // Save first so the user entity has the name ready for the WalletService
//        User savedUser = userRepository.save(user);
//
//        // ============================================================
//        // 2. âš¡ CHECK & CREATE WALLET (The Missing Piece)
//        // ============================================================
//        Optional<Wallet> existingWallet = walletRepository.findByUser(user);
//
//        if (!walletRepository.existsByUser(savedUser)) {
//            CompletableFuture.runAsync(() -> {
//                try {
//                logger.info("âš¡ Profile complete. Creating Wallet for: " + user.getEmail());
//
//                // This creates the wallet using the name we just saved!
//                Wallet newWallet = walletService.createWalletForUser(savedUser);
//
//                // 3. ðŸ“§ Send the Welcome Email (Now that we have bank details)
//                CompletableFuture.runAsync(() -> {
//                notificationService.sendWelcomeEmail(
//                        savedUser.getEmail(),
//                        newWallet.getAccountNumber(),
//                        newWallet.getBankName(),
//                        newWallet.getBalance()
//                );
//                });
//
//                } catch (Exception e) {
//                    logger.error("âŒ Background Wallet Creation Failed for {}: {}", savedUser.getEmail(), e.getMessage());
//                    // Optional: Add logic to retry later or flag user as "Wallet Failed"
//                }
//            });
//        }
//
//        return savedUser;
//    }
    public User findByEmail(String email) {
        User user = userRepository.findFirstByEmailOrderByCreatedAtAsc(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));

        if (user.isDeleted()) {
            throw new RuntimeException("User account is deactivated");
        }

        return user;
    }

    public Long getRequiredUserIdByEmail(String email) {
        return findByEmail(email).getId();
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findFirstByEmailOrderByCreatedAtAsc(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + username));

        // 2. ðŸ›¡ï¸ CRITICAL FIX: Handle NULL passwords
        // If the user came from Google, their password might be null in the DB.
        // We give Spring a "dummy" password just to keep it happy.
        // (This doesn't change the DB, just the in-memory object).
        String password = user.getPassword();
        if (password == null || password.isEmpty()) {
            password = "GOOGLE_LOGIN_NO_PASSWORD_NEEDED";
        }

        // 3. Return the Spring User
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                password,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }

    /**
     * Converts an already-loaded {@link User} entity into a Spring {@link UserDetails}
     * object without touching the database.  Use this in the JWT filter after
     * {@link #findByEmail} so the filter makes exactly ONE DB query (not two).
     */
    public UserDetails buildUserDetails(User user) {
        String password = user.getPassword();
        if (password == null || password.isEmpty()) {
            password = "GOOGLE_LOGIN_NO_PASSWORD_NEEDED";
        }
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                password,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }

    public void acceptTnc(String email, boolean accepted) {
        User user = findByEmail(email);
        Map<String, Object> profileData = user.getProfileData();
        profileData.put("acceptedTncVersion", "1.0");
        user.setProfileData(profileData);
        user.setTncAccepted(accepted);
        userRepository.save(user);
    }

    public void updateUserPassword(String token, String newPassword) {
        // 1. Fetch token from DB
        Optional<PasswordResetToken> tokenOpt = passwordResetTokenRepository.findByToken(token);

        if (tokenOpt.isEmpty()) {
            throw new IllegalArgumentException("Invalid token");
        }

        PasswordResetToken resetToken = tokenOpt.get();

        // 2. Check if token is expired or used
        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now()) || resetToken.isUsed()) {
            throw new IllegalArgumentException("Token has expired or already used");
        }

        // 3. Get user by email stored in the token
        String email = resetToken.getEmail();
//        Optional<User> userOpt = userRepository.findFirstByEmailOrderByCreatedAtAsc(email);
        // Correct usage
        Optional<User> userOpt = userRepository.findFirstByEmailOrderByCreatedAtAsc(resetToken.getEmail());


        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("User not found for token email");
        }

        // 4. Update user password
        User user = userOpt.get();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // 5. Mark token as used
        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);
    }

    // 1. UPLOAD IMAGE TO FIREBASE — permanent public URL, no expiry
    public String uploadProfileImage(Long userId, MultipartFile file) {
        validateProfileImage(file);
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            String originalFilename = file.getOriginalFilename();
            String extension = "jpg";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
            }

            String fileName = String.format("profile_images/%d_%d.%s",
                    userId, System.currentTimeMillis(), extension);

            Bucket bucket = StorageClient.getInstance().bucket();

            // Embed a Firebase download token in the object's custom metadata.
            // Firebase Storage honours ?token=<uuid> as a permanent authenticated
            // download key — no ACL change, no bucket policy change, works even
            // with Uniform Bucket-Level Access enabled.
            String downloadToken = java.util.UUID.randomUUID().toString();
            com.google.cloud.storage.BlobInfo blobInfo = com.google.cloud.storage.BlobInfo
                    .newBuilder(bucket.getName(), fileName)
                    .setContentType(file.getContentType())
                    .setMetadata(java.util.Map.of("firebaseStorageDownloadTokens", downloadToken))
                    .build();

            // Upload via the underlying GCS client so we can set metadata at creation time
            Blob blob = StorageClient.getInstance().bucket().getStorage()
                    .create(blobInfo, file.getBytes());

            // Firebase Storage public download URL — permanent, never expires
            String bucketName = bucket.getName();
            String encodedPath = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
            String publicUrl = String.format(
                    "https://firebasestorage.googleapis.com/v0/b/%s/o/%s?alt=media&token=%s",
                    bucketName, encodedPath, downloadToken);

            // Persist URL in both the dedicated column AND in profileData['imageUrl']
            // so the Flutter ProfileData model (which reads profile_data['imageUrl']) picks it up.
            user.setProfileImageUrl(publicUrl);
            user.setProfileImageBlobName(fileName);
            user.setProfileImage(null); // clear legacy byte blob if present

            Map<String, Object> pd = user.getProfileData();
            if (pd == null) pd = new HashMap<>();
            pd.put("imageUrl", publicUrl);
            user.setProfileData(pd);

            userRepository.save(user);
            logger.info("[Image] Uploaded profile image for user {} → {}", userId, publicUrl);
            return publicUrl;

        } catch (IOException e) {
            throw new RuntimeException("Failed to upload image to Firebase Storage: " + e.getMessage(), e);
        }
    }

    // 2. DELETE IMAGE FROM FIREBASE
    public void deleteProfileImage(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String blobName = user.getProfileImageBlobName();

        if (blobName != null && !blobName.isEmpty()) {
            try {
                Bucket bucket = StorageClient.getInstance().bucket();
                Blob blob = bucket.get(blobName);
                if (blob != null) {
                    blob.delete();
                }
            } catch (Exception e) {
                logger.error("Error deleting file from Firebase", e);
            }
        }

        user.setProfileImageBlobName(null);
        user.setProfileImageUrl(null);
        userRepository.save(user);
    }


    private void validateProfileImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose an image to upload");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("Profile images must be 5MB or smaller");
        }
        String contentType = file.getContentType();
        if (contentType == null || !Set.of("image/jpeg", "image/png", "image/webp").contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Only JPG, PNG, and WEBP images are allowed");
        }
    }
    /**
     * Updates the user's session ID to enforce Single Device Login.
     * Called by SecurityConfig on successful OAuth2 login.
     */
    @Transactional
    public void updateUserSession(User user) {
        userRepository.save(user);
    }

    /**
     * @deprecated Superseded by {@link AccountDeletionService#deleteAccount}, which
     * is the only path wired to {@code DELETE /auth/delete}. That service dissolves
     * budgets, rakes matured savings, and requires the wallet to be withdrawn
     * before closing — this older guard (which simply blocks on any held funds)
     * is no longer used. Do not call it; it will be removed.
     */
    @Deprecated
    @Transactional
    public void deleteUserAccount(String email) {
        User user = findByEmail(email);

        // Wisemonie is a commitment layer: money locked in an active budget or a
        // still-held savings pot cannot be reclaimed early, and account deletion
        // must never strand it. Block deletion while the user holds any locked or
        // unwithdrawn funds — active budgets, savings pots still ACTIVE (immature)
        // or MATURED-but-unwithdrawn, or a positive wallet balance — so the
        // discipline stays intact and no money is silently lost on deletion.
        List<String> blockers = new ArrayList<>();

        int activeBudgets = budgetRepository
                .findByUserIdAndStatus(user.getId(), BudgetStatus.ACTIVE).size();
        int scheduledBudgets = budgetRepository
                .findByUserIdAndStatus(user.getId(), BudgetStatus.SCHEDULED).size();
        int fundedBudgets = activeBudgets + scheduledBudgets;
        if (fundedBudgets > 0) {
            blockers.add(fundedBudgets + " active or scheduled budget" + (fundedBudgets > 1 ? "s" : ""));
        }

        int heldSavings = savingsGoalRepository
                .findByUserIdAndStatus(user.getId(), SavingsStatus.ACTIVE).size()
                + savingsGoalRepository
                .findByUserIdAndStatus(user.getId(), SavingsStatus.MATURED).size();
        if (heldSavings > 0) {
            blockers.add(heldSavings + " savings pot" + (heldSavings > 1 ? "s" : ""));
        }

        // Wallet: only block on a WITHDRAWABLE balance (>= the ₦100 minimum). The
        // user must move that out via the in-app withdraw-to-bank flow first — it
        // needs their PIN and chosen bank, so it can't be swept automatically here,
        // and it settles asynchronously (a post-delete failure would strand it).
        // A sub-₦100 residual is un-withdrawable dust: it does NOT block deletion,
        // stays in the soft-deleted wallet, and is recoverable if the user
        // reactivates — so nothing is lost either way.
        BigDecimal walletBalance = BigDecimal.ZERO;
        try {
            walletBalance = walletService.checkBalance(user.getId());
        } catch (Exception ignored) {
            // No wallet / lookup issue — treat as no balance; never block on this.
        }
        if (walletBalance != null
                && walletBalance.compareTo(MIN_WITHDRAWABLE_BALANCE) >= 0) {
            blockers.add(String.format("a wallet balance of ₦%,.2f", walletBalance));
        }

        if (!blockers.isEmpty()) {
            throw new IllegalStateException(
                    "You still have " + String.join(", ", blockers)
                    + ". To protect your money, let your active budgets finish, wait for any "
                    + "savings to mature and withdraw them, and empty your wallet before "
                    + "deleting your account."
            );
        }

        // ðŸ›¡ï¸ SOFT DELETE: Don't remove the row. Just hide it.
        user.setDeleted(true);

        // Optional: Clear sensitive data if required by law (GDPR),
        // but KEEP the ID and Wallet link.
        // user.setPassword("");

        userRepository.save(user);
        logger.info("âŒ Soft-deleted user account: " + email);
    }

    // ==============================================================
    // âœ… GET MOST RECENT BUDGETS (Active & Completed)
    // ==============================================================
    @Transactional(readOnly = true)
    public Map<String, Object> getMostRecentBudgets(String email) {
        User user = findByEmail(email);

        // 1. Fetch Most Recent Active
        Optional<Budget> activeOpt = budgetRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), BudgetStatus.ACTIVE);
        Optional<Budget> scheduledOpt = budgetRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), BudgetStatus.SCHEDULED);

        // 2. Fetch Most Recent Completed
        Optional<Budget> completedOpt = budgetRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), BudgetStatus.COMPLETED);

        // 3. Construct Response
        Map<String, Object> response = new HashMap<>();

        // We map manually or use a helper to avoid Infinite Recursion (User -> Budget -> User)
        response.put("active", activeOpt.map(this::mapBudgetToSummary).orElse(null));
        response.put("scheduled", scheduledOpt.map(this::mapBudgetToSummary).orElse(null));
        response.put("completed", completedOpt.map(this::mapBudgetToSummary).orElse(null));

        return response;
    }

    // Helper to prevent infinite JSON recursion / loading heavy relationships
    private Map<String, Object> mapBudgetToSummary(Budget budget) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", budget.getId());
        summary.put("name", budget.getName());
        summary.put("totalAmount", budget.getTotalAmount());
        summary.put("allocatedAmount", budget.getAllocatedAmount());
        summary.put("startDate", budget.getStartDate());
        summary.put("endDate", budget.getEndDate());
        summary.put("status", budget.getStatus());
        summary.put("createdAt", budget.getCreatedAt());
        // Add envelope count or summary if needed, but keep it light
        summary.put("envelopeCount", budget.getEnvelopes() != null ? budget.getEnvelopes().size() : 0);
        return summary;
    }


}
