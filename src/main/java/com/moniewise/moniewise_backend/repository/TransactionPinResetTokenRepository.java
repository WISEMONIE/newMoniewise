package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.TransactionPinResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransactionPinResetTokenRepository extends JpaRepository<TransactionPinResetToken, Long> {

    Optional<TransactionPinResetToken> findByEmailAndToken(String email, String token);

    void deleteByEmail(String email);
}
