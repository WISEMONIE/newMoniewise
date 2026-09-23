package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.FeeReserve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import javax.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface FeeReserveRepository extends JpaRepository<FeeReserve, Long> {

    Optional<FeeReserve> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM FeeReserve r WHERE r.user.id = :userId")
    Optional<FeeReserve> findByUserIdForUpdate(Long userId);
}
