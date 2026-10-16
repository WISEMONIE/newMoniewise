package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByUserId(Long userId);

    Optional<Wallet> findFirstByUserIdOrderByUpdatedAtDesc(Long userId);

    boolean existsByUserId(Long userId);

    @Query("""
            SELECT CASE WHEN COUNT(w) > 0 THEN true ELSE false END
            FROM Wallet w
            WHERE w.user.id = :userId
              AND w.revenueWallet = false
              AND w.balance > 0
            """)
    boolean existsFundedUserWallet(@Param("userId") Long userId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM wallets w
                WHERE w.user_id = :userId
                  AND w.status = 'ACTIVE'
                  AND coalesce(w.is_revenue_wallet, false) = false
                  AND w.account_number IS NOT NULL
                  AND btrim(w.account_number) <> ''
            )
            """, nativeQuery = true)
    boolean existsReadyUserWallet(@Param("userId") Long userId);

    Optional<Wallet> findByUser(User user);

    boolean existsByUser(User user); // Add this method

    Optional<Wallet> findByRevenueWalletTrue();

    /**
     * Customer Rubies wallets whose opening fee was never written, either as
     * WALLET-OPEN-{userId} or as the older KYC-BVN-{userId}- row.
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM wallets w
            WHERE COALESCE(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND (
                    upper(w.provider_name) = 'RUBIES'
                 OR w.bank_name = 'Rubies MFB'
              )
              AND NOT EXISTS (
                    SELECT 1 FROM transaction_logs t
                    WHERE t.transaction_type IN ('kyc_bvn_fee', 'wallet_open_fee')
                      AND (
                            t.reference = 'WALLET-OPEN-' || w.user_id::text
                         OR t.reference LIKE 'KYC-BVN-' || w.user_id::text || '-%'
                      )
              )
            """, nativeQuery = true)
    long countRubiesWalletsMissingOpeningFee();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.user.id = :userId")
    Optional<Wallet> findByUserIdForUpdate(@Param("userId") Long userId);

    Optional<Wallet> findByProviderWalletRef(String providerWalletRef);
    Optional<Wallet> findBySubWalletRef(String subWalletRef);
    Optional<Wallet> findByProviderCustomerRef(String providerCustomerRef);
    Optional<Wallet> findByAccountNumber(String accountNumber);
    List<Wallet> findByProviderName(String providerName);
}
