package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.UserSubscription;
import com.moniewise.moniewise_backend.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    Optional<UserSubscription> findTopByUserIdAndStatusOrderByEndDateDesc(
            Long userId, SubscriptionStatus status);

    List<UserSubscription> findByStatusAndEndDateBefore(
            SubscriptionStatus status, LocalDate date);
}
