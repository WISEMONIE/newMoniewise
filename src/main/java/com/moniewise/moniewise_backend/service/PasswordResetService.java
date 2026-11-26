package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.PasswordResetToken;
import com.moniewise.moniewise_backend.repository.PasswordResetTokenRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
public class PasswordResetService {

    @Autowired
    public PasswordResetTokenRepository tokenRepository;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    public PasswordResetService(UserRepository userRepository, PasswordEncoder passwordEncoder, JavaMailSender mailSender) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
    }

    @Transactional
    public PasswordResetToken createResetToken(String email) {
        // ✅ REFACTOR: Generate 6-Digit OTP
        SecureRandom secureRandom = new SecureRandom();
        int otpCode = 100000 + secureRandom.nextInt(900000);
        String token = String.valueOf(otpCode);

        tokenRepository.deleteByEmail(email);

        // Save to DB
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setEmail(email);
        resetToken.setToken(token); // Stores "123456"
        resetToken.setExpiresAt(LocalDateTime.now().plusMinutes(10)); // 10 min expiry

        return tokenRepository.save(resetToken);
    }

    public boolean isValidToken(String email, String token) {
        return findValidToken(email, token).isPresent();
    }

    public void markTokenAsUsed(String email, String token) {
        PasswordResetToken resetToken = findValidToken(email, token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired OTP"));
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);
    }

    public void updateUserPassword(String email, String token, String newPassword) {
        PasswordResetToken resetToken = findValidToken(email, token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired OTP"));

        userRepository.findFirstByEmailOrderByCreatedAtAsc(resetToken.getEmail()).ifPresent(user -> {
            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);
        });
    }

    private java.util.Optional<PasswordResetToken> findValidToken(String email, String token) {
        return tokenRepository.findByEmailAndToken(email, token)
                .filter(t -> !t.isUsed() && t.getExpiresAt().isAfter(LocalDateTime.now()));
    }
}
