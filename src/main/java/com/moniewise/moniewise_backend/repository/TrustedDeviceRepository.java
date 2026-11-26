package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.TrustedDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {

    boolean existsByUserIdAndDeviceId(Long userId, String deviceId);

    Optional<TrustedDevice> findByUserIdAndDeviceId(Long userId, String deviceId);
}
