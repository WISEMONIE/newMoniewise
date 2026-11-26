package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.KycProfileRequestDto;
import com.moniewise.moniewise_backend.dto.KycProfileResponseDto;
import com.moniewise.moniewise_backend.dto.response.BvnVerificationResultDto;
import com.moniewise.moniewise_backend.entity.KycProfile;
import com.moniewise.moniewise_backend.exception.BvnFallbackIdentityRequiredException;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.KycProfileRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class KycService {

    private final KycProfileRepository kycProfileRepository;
    private final UserRepository userRepository;
    private final SecureWavePaymentProvider secureWavePaymentProvider;
    private final RubiesGateway rubiesGateway;

    @Value("${moniewise.kyc.rubies-fallback.enabled:true}")
    private boolean rubiesBvnFallbackEnabled;

    public KycService(
            KycProfileRepository kycProfileRepository,
            UserRepository userRepository,
            SecureWavePaymentProvider secureWavePaymentProvider,
            RubiesGateway rubiesGateway
    ) {
        this.kycProfileRepository = kycProfileRepository;
        this.userRepository = userRepository;
        this.secureWavePaymentProvider = secureWavePaymentProvider;
        this.rubiesGateway = rubiesGateway;
    }

    // ── Profile CRUD ──────────────────────────────────────────────────────────

    @Transactional
    public KycProfileResponseDto createOrUpdateProfile(Long userId, KycProfileRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Optional<KycProfile> existing = kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        KycProfile profile = existing.orElse(new KycProfile());
        profile.setUser(user);
        profile.setBvn(request.getBvn());
        profile.setSourceOfFunds(request.getSourceOfFunds());
        profile.setSourceOfWealth(request.getSourceOfWealth());
        KycProfile saved = kycProfileRepository.save(profile);
        return mapToResponse(saved);
    }

    // ── BVN Verification via SecureWave (authenticated / post-signup) ─────────

    /**
     * Calls the SecureWave BVN verification API, persists the full result to
     * {@code kyc_profiles}, marks the user's BVN on the {@code users} table,
     * and returns the structured result DTO.
     *
     * <p>Idempotent — if the BVN is already marked verified in our DB, we skip
     * the external call and return the stored data immediately.
     *
     * @param userId the ID of the authenticated user
     * @param bvn    the 11-digit BVN submitted by the client
     * @return a {@link BvnVerificationResultDto} populated with identity data
     * @throws RuntimeException if SecureWave rejects the BVN or returns an error
     */
    /**
     * Verifies the BVN via SecureWave, persists all returned identity data,
     * and marks the profile as VERIFIED.
     *
     * <p>The email and phone sent to SecureWave come from the account. The
     * client sends the BVN first. First name, last name, and date of birth
     * are used only when SecureWave is unavailable and Rubies has to check
     * the BVN against that name.
     */
    @Transactional
    public BvnVerificationResultDto verifyBvnWithProvider(Long userId,
                                                         String bvn,
                                                         String suppliedFirstName,
                                                         String suppliedLastName,
                                                         String suppliedDob) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // ── Guard 1: idempotency — already fully verified for THIS user ──────────
        // Skip the SecureWave round-trip and just return what we already know.
        // This also prevents a second call from inserting a duplicate kyc_profiles row.
        Optional<KycProfile> existing = kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(userId);
        if (existing.isPresent()
                && Boolean.TRUE.equals(existing.get().isBvnVerified())
                && KycProfile.KycStatus.VERIFIED.equals(existing.get().getKycStatus())
                && bvn.equals(existing.get().getBvn())) {
            log.info("[KYC] BVN already verified for userId={} — returning cached result", userId);
            BvnVerificationResultDto cached = buildDtoFromProfile(existing.get());
            cached.setKycStatus(KycProfile.KycStatus.VERIFIED);
            cached.setBvnVerified(true);
            return cached;
        }

        // ── Guard 2: this BVN is already on an account ─────────────────────────
        // Check our own records before SecureWave. A known BVN is not sent out.
        rejectIfBvnOwnedBySomeoneElse(bvn, userId);

        // Delegate to SecureWave first. Rubies fallback needs name details, so
        // pass whatever verified/profile identity data we already have.
        KycProfile stored = existing.orElse(null);
        BvnVerificationResultDto result = verifyBvnWithSecureWaveFallback(
                user.getEmail(),
                user.getPhone(),
                bvn,
                firstNonBlank(suppliedFirstName, identityFirstName(user, stored)),
                firstNonBlank(suppliedLastName, identityLastName(user, stored)),
                firstNonBlank(suppliedDob, identityDob(user, stored)));

        // ── Upsert: update existing row if one exists, never insert a second one ──
        KycProfile profile = existing.orElse(new KycProfile());

        profile.setUser(user);
        profile.setBvn(bvn);
        profile.setBvnVerified(true);
        profile.setKycStatus(KycProfile.KycStatus.VERIFIED);

        // Top-level identity fields
        profile.setNameOnCard(result.getNameOnCard());
        profile.setEnrolmentBank(result.getEnrolmentBank());
        profile.setEnrolmentBranch(result.getEnrolmentBranch());
        profile.setFormattedRegistrationDate(result.getFormattedRegistrationDate());
        profile.setLevelOfAccount(result.getLevelOfAccount());
        profile.setNin(result.getNin());
        profile.setWatchlisted(result.getWatchlisted());
        profile.setBvnVerificationStatus(result.getVerificationStatus());

        // personal_info fields
        profile.setFirstName(result.getFirstName());
        profile.setMiddleName(result.getMiddleName());
        profile.setLastName(result.getLastName());
        profile.setGender(result.getGender());
        profile.setDateOfBirth(result.getDateOfBirth());
        profile.setStateOfOrigin(result.getStateOfOrigin());
        profile.setLgaOfOrigin(result.getLgaOfOrigin());
        profile.setNationality(result.getNationality());
        profile.setMaritalStatus(result.getMaritalStatus());

        // residential_info fields
        profile.setStateOfResidence(result.getStateOfResidence());
        profile.setLgaOfResidence(result.getLgaOfResidence());
        profile.setResidentialAddress(result.getResidentialAddress());

        kycProfileRepository.save(profile);

        // Mirror the BVN onto the users table so createVirtualAccount() can use it
        user.setBvn(bvn);
        userRepository.save(user);

        log.info("[KYC] BVN verification persisted for userId={}", userId);

        // Stamp internal status onto the result DTO before returning
        result.setKycStatus(KycProfile.KycStatus.VERIFIED);
        result.setBvnVerified(true);
        return result;
    }

    // ── Other helpers ─────────────────────────────────────────────────────────

    public Optional<KycProfileResponseDto> getProfile(Long userId) {
        return kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(userId).map(this::mapToResponse);
    }

    public boolean isUserVerified(Long userId) {
        return kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(userId)
                .map(profile -> profile.getKycStatus() == KycProfile.KycStatus.VERIFIED)
                .orElse(false);
    }

    @Transactional
    public void markApproved(Long userId) {
        kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(userId).ifPresent(profile -> {
            profile.setKycStatus(KycProfile.KycStatus.VERIFIED);
            kycProfileRepository.save(profile);
        });
    }

    @Transactional
    public void markRejected(Long userId, String reason) {
        kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(userId).ifPresent(profile -> {
            profile.setKycStatus(KycProfile.KycStatus.REJECTED);
            kycProfileRepository.save(profile);
        });
    }

    /**
     * A BVN already stored on another account stops here. SecureWave is not called.
     */
    private void rejectIfBvnOwnedBySomeoneElse(String bvn, Long userId) {
        Optional<User> owner = userRepository.findGlobalByBvn(bvn);
        if (owner.isPresent()
                && owner.get().getId() != null
                && !owner.get().getId().equals(userId)) {
            log.warn("[KYC] BVN already stored on another account — skipping provider for userId={}", userId);
            if (owner.get().isDeleted()) {
                throw new IllegalArgumentException(
                        "This BVN is linked to a permanently closed account and can't be used to sign up again. Please contact support if you need help.");
            }
            throw new IllegalArgumentException("This BVN is already linked to another account.");
        }
        if (kycProfileRepository.existsByBvnAndUserIdNot(bvn, userId)) {
            log.warn("[KYC] BVN already stored on another KYC profile — skipping provider for userId={}", userId);
            throw new IllegalArgumentException("This BVN is already linked to another account.");
        }
    }

    // ── Provider fallback ────────────────────────────────────────────────────

    private BvnVerificationResultDto verifyBvnWithSecureWaveFallback(String email,
                                                                     String phone,
                                                                     String bvn,
                                                                     String firstName,
                                                                     String lastName,
                                                                     String dob) {
        try {
            return secureWavePaymentProvider.verifyBvn(email, phone, bvn);
        } catch (RuntimeException primaryError) {
            if (!isSecureWaveUnavailable(primaryError)) {
                throw primaryError;
            }

            log.warn("[KYC] SecureWave BVN verification appears unavailable; trying Rubies fallback. reason={}",
                    primaryError.getMessage());

            // Emergency off-switch: comment out this single invocation and
            // uncomment the throw below to return to SecureWave-only behaviour.
            return verifyBvnWithRubiesFallback(bvn, firstName, lastName, dob, primaryError);
            // throw primaryError;
        }
    }

    private BvnVerificationResultDto verifyBvnWithRubiesFallback(String bvn,
                                                                 String firstName,
                                                                 String lastName,
                                                                 String dob,
                                                                 RuntimeException primaryError) {
        if (!rubiesBvnFallbackEnabled) {
            throw primaryError;
        }

        String cleanFirstName = clean(firstName);
        String cleanLastName = clean(lastName);
        String cleanDob = clean(dob);

        if (cleanFirstName == null || cleanLastName == null) {
            log.warn("[KYC] Rubies BVN fallback is waiting for the name on the BVN.");
            throw new BvnFallbackIdentityRequiredException();
        }

        try {
            String reference = rubiesBvnReference();
            BvnVerificationResultDto result = rubiesGateway.verifyBvn(
                    bvn,
                    cleanFirstName,
                    cleanLastName,
                    cleanDob,
                    reference);
            if (clean(result.getFirstName()) == null) {
                result.setFirstName(cleanFirstName);
            }
            if (clean(result.getLastName()) == null) {
                result.setLastName(cleanLastName);
            }
            if (clean(result.getDateOfBirth()) == null && cleanDob != null) {
                result.setDateOfBirth(cleanDob);
            }
            log.info("[KYC] Rubies BVN fallback succeeded for reference={}", reference);
            return result;
        } catch (IllegalArgumentException rubiesRejection) {
            throw rubiesRejection;
        } catch (RuntimeException fallbackError) {
            primaryError.addSuppressed(fallbackError);
            log.error("[KYC] Rubies BVN fallback also failed: {}", fallbackError.getMessage());
            throw primaryError;
        }
    }

    private boolean isSecureWaveUnavailable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ResourceAccessException) {
                return true;
            }
            if (current instanceof RestClientResponseException) {
                int status = ((RestClientResponseException) current).getRawStatusCode();
                return status == 429 || status >= 500;
            }
            current = current.getCause();
        }

        String message = error.getMessage() != null
                ? error.getMessage().toLowerCase(Locale.ROOT)
                : "";
        return message.contains("timed out")
                || message.contains("timeout")
                || message.contains("connection refused")
                || message.contains("i/o error")
                || message.contains("service unavailable")
                || message.contains("bad gateway")
                || message.contains("gateway timeout")
                || message.contains("503")
                || message.contains("502")
                || message.contains("504");
    }

    private String identityFirstName(User user, KycProfile profile) {
        return firstNonBlank(
                profile != null ? profile.getFirstName() : null,
                profileValue(user, "bvnFirstName", "bvnFirst", "firstName"));
    }

    private String identityLastName(User user, KycProfile profile) {
        return firstNonBlank(
                profile != null ? profile.getLastName() : null,
                profileValue(user, "bvnLastName", "bvnLast", "lastName"));
    }

    private String identityDob(User user, KycProfile profile) {
        return firstNonBlank(
                profile != null ? profile.getDateOfBirth() : null,
                profileValue(user, "dateOfBirth", "dob"));
    }

    private String profileValue(User user, String... keys) {
        Map<String, Object> profile = user != null ? user.getProfileData() : null;
        if (profile == null || profile.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = profile.get(key);
            if (value == null) {
                continue;
            }
            String text = clean(value.toString());
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String clean = clean(value);
            if (clean != null) {
                return clean;
            }
        }
        return null;
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isBlank() ? null : text;
    }

    private String rubiesBvnReference() {
        return "MW-BVN-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private KycProfileResponseDto mapToResponse(KycProfile profile) {
        return new KycProfileResponseDto(
                profile.getId(),
                profile.getBvn(),
                profile.isBvnVerified(),
                profile.getSourceOfFunds(),
                profile.getSourceOfWealth(),
                profile.getKycStatus(),
                profile.getRiskLevel(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }

    /** Rebuilds a {@link BvnVerificationResultDto} from an already-persisted profile. */
    private BvnVerificationResultDto buildDtoFromProfile(KycProfile p) {
        BvnVerificationResultDto dto = new BvnVerificationResultDto();
        dto.setBvnNumber(p.getBvn());
        dto.setNameOnCard(p.getNameOnCard());
        dto.setEnrolmentBank(p.getEnrolmentBank());
        dto.setEnrolmentBranch(p.getEnrolmentBranch());
        dto.setFormattedRegistrationDate(p.getFormattedRegistrationDate());
        dto.setLevelOfAccount(p.getLevelOfAccount());
        dto.setNin(p.getNin());
        dto.setWatchlisted(p.getWatchlisted());
        dto.setVerificationStatus(p.getBvnVerificationStatus());
        dto.setFirstName(p.getFirstName());
        dto.setMiddleName(p.getMiddleName());
        dto.setLastName(p.getLastName());
        dto.setGender(p.getGender());
        dto.setDateOfBirth(p.getDateOfBirth());
        dto.setStateOfOrigin(p.getStateOfOrigin());
        dto.setLgaOfOrigin(p.getLgaOfOrigin());
        dto.setNationality(p.getNationality());
        dto.setMaritalStatus(p.getMaritalStatus());
        dto.setStateOfResidence(p.getStateOfResidence());
        dto.setLgaOfResidence(p.getLgaOfResidence());
        dto.setResidentialAddress(p.getResidentialAddress());
        dto.setKycStatus(p.getKycStatus());
        dto.setBvnVerified(p.isBvnVerified());
        return dto;
    }
}
