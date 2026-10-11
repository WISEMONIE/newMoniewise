package com.moniewise.moniewise_backend.service;


import com.moniewise.moniewise_backend.entity.Otp;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.exception.OtpVerificationException;
import com.moniewise.moniewise_backend.repository.OtpRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class OtpService {

    private final OtpRepository otpRepository;
    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRY_MINUTES = 5;

    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public OtpService(OtpRepository otpRepository, UserRepository userRepository, NotificationService notificationService) {
        this.otpRepository = otpRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public String generateOtp(Long userId) {
        // Generate 6-digit OTP
        SecureRandom random = new SecureRandom();
        String otpCode = String.valueOf(random.nextInt(900000) + 100000);

        // Delete any existing OTP for the user
        otpRepository.deleteByUserId(userId);

        // Fetch existing user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + userId));

        // Set isVerified to false
        user.setVerified(false);
        userRepository.save(user); // Update existing user

        // Create and save new OTP
        LocalDateTime now = LocalDateTime.now();
        Otp otp = new Otp(userId, otpCode, now, now.plusMinutes(OTP_EXPIRY_MINUTES));
        otpRepository.save(otp);

        // 4. SEND THE EMAIL (This was missing!) 🚀
        try {
            CompletableFuture.runAsync(() -> {
                notificationService.sendOtpEmail(user.getEmail(), otpCode);
            });
        } catch (Exception e) {
            // Log error but don't fail the transaction
            System.err.println("Failed to trigger OTP email: " + e.getMessage());
        }

        return otpCode;
    }

    /**
     * Generates + emails a 6-digit login OTP for first-device 2FA. Identical to
     * {@link #generateOtp} but deliberately does NOT flip {@code user.isVerified}
     * to false — that flag is signup-verification state, and a returning, already
     * verified user going through device 2FA must stay verified.
     */
    @Transactional
    public String generateLoginOtp(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + userId));

        Optional<Otp> existing = otpRepository.findByUserId(userId);
        if (existing.isPresent() && existing.get().getExpiresAt().isAfter(LocalDateTime.now())) {
            String existingCode = existing.get().getOtpCode();
            System.out.println("[OTP-GEN] Reusing existing OTP for userId=" + userId + " code=" + existingCode);
            try {
                CompletableFuture.runAsync(() -> notificationService.sendOtpEmail(user.getEmail(), existingCode));
            } catch (Exception e) {
                System.err.println("Failed to resend login OTP email: " + e.getMessage());
            }
            return existingCode;
        }
        System.out.println("[OTP-GEN] Creating NEW OTP for userId=" + userId);

        SecureRandom random = new SecureRandom();
        String otpCode = String.valueOf(random.nextInt(900000) + 100000);

        otpRepository.deleteByUserId(userId);

        LocalDateTime now = LocalDateTime.now();
        Otp otp = new Otp(userId, otpCode, now, now.plusMinutes(OTP_EXPIRY_MINUTES));
        otpRepository.save(otp);

        try {
            CompletableFuture.runAsync(() -> notificationService.sendOtpEmail(user.getEmail(), otpCode));
        } catch (Exception e) {
            System.err.println("Failed to trigger login OTP email: " + e.getMessage());
        }

        return otpCode;
    }

    @Transactional(readOnly = true)
    public boolean verifyOtp(Long userId, String otpCode) {
        System.out.println("[OTP-VERIFY] userId=" + userId + " codeReceived='" + otpCode + "' len=" + (otpCode == null ? 0 : otpCode.length()));
        Optional<Otp> existing = otpRepository.findByUserId(userId);
        if (existing.isPresent()) {
            Otp stored = existing.get();
            System.out.println("[OTP-VERIFY] storedCode='" + stored.getOtpCode() + "' expires=" + stored.getExpiresAt() + " match=" + stored.getOtpCode().equals(otpCode));
        } else {
            System.out.println("[OTP-VERIFY] NO OTP found in DB for userId=" + userId);
        }

        Optional<Otp> otpOptional = otpRepository.findByUserIdAndOtpCode(userId, otpCode);

        if (otpOptional.isEmpty()) {
            throw new OtpVerificationException("Invalid OTP code");
        }

        Otp otp = otpOptional.get();
        if (otp.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new OtpVerificationException("OTP has expired");
        }

        return true;
    }

    @Transactional
    public void clearOtp(Long userId) {
        otpRepository.deleteByUserId(userId);
    }
}
