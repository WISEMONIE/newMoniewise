package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.enums.WithdrawalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {
    Optional<Withdrawal> findByClientReference(String clientReference);
    Optional<Withdrawal> findByProviderReference(String providerReference);
    List<Withdrawal> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    /** True if the user has an in-flight (INITIATED/PROCESSING) withdrawal created
     *  after the given cutoff — used by account deletion to wait for a genuinely
     *  recent transfer to settle, while ignoring stuck/stale ones so they can't
     *  permanently block closure. */
    boolean existsByUserIdAndStatusInAndCreatedAtAfter(
            Long userId, Collection<WithdrawalStatus> statuses, LocalDateTime createdAt);

    /**
     * Feeds the "transferred before" auto-suggest dropdown on the Transfer to
     * Bank screen — pulls the user's most recent successfully-completed
     * withdrawals so {@code WalletService.getRecentRecipients} can dedupe them
     * by destination (bankCode + accountNumber) and surface the most-recent
     * accounts the user has actually sent money to. Capped at 50 so the dedupe
     * pass has enough history to find ~10 distinct destinations without
     * scanning the user's entire withdrawal history.
     */
    List<Withdrawal> findTop50ByUserIdAndStatusOrderByCreatedAtDesc(Long userId, WithdrawalStatus status);

    Optional<Withdrawal> findTopByUserIdAndAmountAndStatusOrderByCompletedAtDesc(
            Long userId, BigDecimal amount, WithdrawalStatus status);
}
