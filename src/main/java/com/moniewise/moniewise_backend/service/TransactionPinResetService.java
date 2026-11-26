package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.TransactionPinResetToken;
import com.moniewise.moniewise_backend.repository.TransactionPinResetTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class TransactionPinResetService {

    private final TransactionPinResetTokenRepository tokenRepository;

    public TransactionPinResetService(TransactionPinResetTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Transactional
    public TransactionPinResetToken createResetToken(String email) {
        SecureRandom secureRandom = new SecureRandom();
        int otpCode = 100000 + secureRandom.nextInt(900000);
        String token = String.valueOf(otpCode);

        tokenRepository.deleteByEmail(email);

        TransactionPinResetToken resetToken = new TransactionPinResetToken();
        resetToken.setEmail(email);
        resetToken.setToken(token);
        resetToken.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return tokenRepository.save(resetToken);
    }

    public boolean isValidToken(String email, String token) {
        return findValidToken(email, token).isPresent();
    }

    @Transactional
    public void markTokenAsUsed(String email, String token) {
        TransactionPinResetToken resetToken = findValidToken(email, token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired OTP"));
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);
    }

    private Optional<TransactionPinResetToken> findValidToken(String email, String token) {
        return tokenRepository.findByEmailAndToken(email, token)
                .filter(value -> !value.isUsed() && value.getExpiresAt().isAfter(LocalDateTime.now()));
    }
}
