package com.moniewise.moniewise_backend.repository;


import com.moniewise.moniewise_backend.entity.Otp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OtpRepository extends JpaRepository<Otp, Long> {
    Optional<Otp> findByUserIdAndOtpCode(Long userId, String otpCode);
    Optional<Otp> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}