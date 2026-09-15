package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.UserSummary;
import com.moniewise.moniewise_backend.enums.Role;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.transaction.Transactional;
import javax.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // Returns the OLDEST user with this email — safe if duplicates somehow exist.
    // Using findFirst avoids NonUniqueResultException which would otherwise crash
    // every request for any user who has a duplicate row (e.g. from a retried signup).
    Optional<User> findFirstByEmailOrderByCreatedAtAsc(String email);

    Optional<User> findByPhone(String phone);
    Optional<User> findByEmailOrPhone(String email, String phone);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    // Custom query to find ANY user (Active or Deleted) — uses LIMIT 1 so it
    // never throws NonUniqueResultException even if a duplicate slipped through.
    @Query(value = "SELECT * FROM users WHERE email = :email ORDER BY created_at ASC LIMIT 1", nativeQuery = true)
    Optional<User> findGlobalByEmail(@Param("email") String email);

    @Query(value = "SELECT * FROM users WHERE phone = :phone ORDER BY created_at ASC LIMIT 1", nativeQuery = true)
    Optional<User> findGlobalByPhone(@Param("phone") String phone);

    @Query(value = "SELECT * FROM users WHERE bvn = :bvn ORDER BY created_at ASC LIMIT 1", nativeQuery = true)
    Optional<User> findGlobalByBvn(@Param("bvn") String bvn);

//     ✅ FIXED: Native Query (Bypasses Hibernate HQL parser errors)
//     Uses Postgres JSON operator (->>) to extract text directly.
//     Note: We provide a countQuery to ensure pagination works efficiently.
// Privacy: name-based search removed — only email, phone, and userTag are
// searchable via the API.  Name search is handled client-side against the
// user's own beneficiary list.
@Query(value = "SELECT " +
        "u.id AS id, " +
        "u.profile_data ->> 'firstName' AS firstName, " +
        "u.profile_data ->> 'lastName' AS lastName, " +
        "u.email AS email, " +
        "u.profile_data ->> 'userTag' AS userTag, " +
        "u.profile_image_url AS profileImageUrl, " +
        "w.account_number AS walletAccountNumber, " +
        "w.bank_name AS walletBankName, " +
        "w.status AS walletStatus, " +
        "w.provider_name AS walletProviderName, " +
        "u.bvn AS bvn " +
        "FROM users u " +
        "LEFT JOIN wallets w ON w.user_id = u.id " +
        "WHERE (" +
        "   lower(coalesce(u.email, '')) LIKE lower(concat('%', :query, '%')) " +
        "   OR coalesce(u.phone, '') LIKE concat('%', :query, '%') " +
        "   OR lower(coalesce(u.profile_data ->> 'userTag', '')) LIKE lower(concat('%', :query, '%'))" +
        ") AND coalesce(u.is_deleted, false) = false",
        countQuery = "SELECT count(*) FROM users u WHERE (" +
                "   lower(coalesce(u.email, '')) LIKE lower(concat('%', :query, '%')) " +
                "   OR coalesce(u.phone, '') LIKE concat('%', :query, '%') " +
                "   OR lower(coalesce(u.profile_data ->> 'userTag', '')) LIKE lower(concat('%', :query, '%'))" +
                ") AND coalesce(u.is_deleted, false) = false",
        nativeQuery = true)
List<UserSummary> searchUsers(@Param("query") String query, Pageable pageable);

//    @Query(value = "SELECT " +
//            "u.id AS id, " +
//            "u.profile_data ->> 'firstName' AS firstName, " +
//            "u.profile_data ->> 'lastName' AS lastName, " +
//            "u.email AS email, " +
//            "u.profile_data ->> 'userTag' AS userTag, " +
//            "u.profile_image_url AS profileImageUrl " +
//            "FROM users u " +
//            "WHERE (" +
//            "   LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) " +
//            "   OR u.phone LIKE CONCAT('%', :query, '%') " +  // 👈 Search Phone
//            "   OR LOWER(u.profile_data ->> 'firstName') LIKE LOWER(CONCAT('%', :query, '%')) " +
//            "   OR LOWER(u.profile_data ->> 'lastName') LIKE LOWER(CONCAT('%', :query, '%')) " +
//            "   OR LOWER(u.profile_data ->> 'userTag') LIKE LOWER(CONCAT('%', :query, '%'))" + // 👈 Search Tag
//            ") " +
//            "AND u.email NOT IN (:excludedEmails) " + // 👈 Exclude Self & Revenue
//            "AND u.deleted = false",
//
//            // Count Query is mandatory for Pageable in Native Queries
//            countQuery = "SELECT count(*) FROM users u WHERE (" +
//                    "   LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) " +
//                    "   OR u.phone LIKE CONCAT('%', :query, '%') " +
//                    "   OR LOWER(u.profile_data ->> 'firstName') LIKE LOWER(CONCAT('%', :query, '%')) " +
//                    "   OR LOWER(u.profile_data ->> 'lastName') LIKE LOWER(CONCAT('%', :query, '%')) " +
//                    "   OR LOWER(u.profile_data ->> 'userTag') LIKE LOWER(CONCAT('%', :query, '%'))" +
//                    ") AND u.email NOT IN (:excludedEmails) AND u.deleted = false",
//            nativeQuery = true)
//    List<UserSummary> searchUsers(
//            @Param("query") String query,
//            @Param("excludedEmails") List<String> excludedEmails,
//            Pageable pageable
//    );

    // In UserRepository.java

    // ✅ FIXED: Uses COALESCE to handle NULLs and removes the List parameter complexity
//    @Query(value = "SELECT " +
//            "u.id AS id, " +
//            "u.profile_data ->> 'firstName' AS firstName, " +
//            "u.profile_data ->> 'lastName' AS lastName, " +
//            "u.email AS email, " +
//            "u.profile_data ->> 'userTag' AS userTag, " +
//            "u.profile_image_url AS profileImageUrl " +
//            "FROM users u " +
//            "WHERE (" +
//            "   LOWER(COALESCE(u.email, '')) LIKE :pattern " + // Handle NULL email
//            "   OR COALESCE(u.phone, '') LIKE :pattern " +     // Handle NULL phone
//            "   OR LOWER(COALESCE(u.profile_data ->> 'firstName', '')) LIKE :pattern " +
//            "   OR LOWER(COALESCE(u.profile_data ->> 'lastName', '')) LIKE :pattern " +
//            "   OR LOWER(COALESCE(u.profile_data ->> 'userTag', '')) LIKE :pattern" +
//            ") " +
//            "AND u.deleted = false",
//
//            countQuery = "SELECT count(*) FROM users u WHERE (" +
//                    "   LOWER(COALESCE(u.email, '')) LIKE :pattern " +
//                    "   OR COALESCE(u.phone, '') LIKE :pattern " +
//                    "   OR LOWER(COALESCE(u.profile_data ->> 'firstName', '')) LIKE :pattern " +
//                    "   OR LOWER(COALESCE(u.profile_data ->> 'lastName', '')) LIKE :pattern " +
//                    "   OR LOWER(COALESCE(u.profile_data ->> 'userTag', '')) LIKE :pattern" +
//                    ") AND u.deleted = false",
//            nativeQuery = true)
//    List<UserSummary> searchUsers(
//            @Param("pattern") String pattern, // We pass "%query%" from Java
//            Pageable pageable
//    );

    // ✅ OPTIMIZED: Fetch only the token string (JPQL is fine here)
    @Query("SELECT u.fcmToken FROM User u WHERE u.id = :id")
    String findFcmTokenById(@Param("id") Long id);

    // ✅ NEW: Clear dead tokens
    @Modifying
    @Transactional // ✅ Uses Spring Transactional now
    @Query("UPDATE User u SET u.fcmToken = NULL WHERE u.id = :id")
    void clearFcmToken(@Param("id") Long id);

    @Modifying
    @Query("UPDATE User u SET u.fcmToken = NULL WHERE u.fcmToken = :token")
    void clearFcmTokenByToken(@Param("token") String token);

    @Modifying
    @Query("UPDATE User u SET u.fcmToken = NULL WHERE u.email = :email AND u.fcmToken = :token")
    int clearFcmTokenByEmailAndToken(@Param("email") String email, @Param("token") String token);

    /**
     * Finds "abandoned signups" — users who registered but never finished
     * onboarding, i.e. they have NEITHER a wallet NOR a KYC profile.
     * (Wallet creation requires verified KYC data, so "no wallet + no KYC"
     * reliably identifies someone who dropped off before completing their
     * profile — as opposed to, say, a user mid-KYC whose wallet creation
     * merely failed.)
     * <p>
     * Used by {@code IncompleteSignupLifecycleManager} to drive the
     * "complete your profile" nudge-email cadence and, eventually, the
     * 30-day purge of registrations that never went anywhere.
     */
    @Query(value = """
            SELECT u.*
            FROM users u
            LEFT JOIN wallets w ON w.user_id = u.id
            LEFT JOIN kyc_profiles k ON k.user_id = u.id
            WHERE w.id IS NULL
              AND k.id IS NULL
              AND u.is_deleted = false
            ORDER BY u.created_at ASC
            """, nativeQuery = true)
    List<User> findIncompleteSignups();

    /**
     * Verified users with a ready wallet, no active budget, and an empty wallet.
     * This is the gentle "your wallet is ready, start with a plan" audience.
     */
    @Query(value = """
            SELECT u.*
            FROM users u
            JOIN wallets w ON w.user_id = u.id
            WHERE u.is_deleted = false
              AND u.is_verified = true
              AND coalesce(u.test_account, false) = false
              AND w.status = 'ACTIVE'
              AND coalesce(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND coalesce(w.balance, 0) <= 0
              AND coalesce(w.updated_at, u.created_at) <= :eligibleBefore
              AND NOT EXISTS (
                    SELECT 1
                    FROM budgets b_active
                    WHERE b_active.user_id = u.id
                      AND b_active.status = 'ACTIVE'
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM budgets b_completed
                    WHERE b_completed.user_id = u.id
                      AND b_completed.status = 'COMPLETED'
              )
            ORDER BY coalesce(w.updated_at, u.created_at) ASC, u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findWalletReadyUsersWithoutActiveBudgetAndEmptyWallet(
            @Param("eligibleBefore") LocalDateTime eligibleBefore,
            @Param("limit") int limit);

    /**
     * Recent users with a ready wallet who may need the "How to use Wisemonie"
     * guide because they have not funded the wallet and have no active budget.
     */
    @Query(value = """
            SELECT u.*
            FROM users u
            JOIN wallets w ON w.user_id = u.id
            WHERE u.is_deleted = false
              AND u.id > :afterUserId
              AND u.is_verified = true
              AND coalesce(u.test_account, false) = false
              AND w.status = 'ACTIVE'
              AND coalesce(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND coalesce(w.balance, 0) <= 0
              AND u.email IS NOT NULL
              AND btrim(u.email) <> ''
              AND u.created_at >= :createdAfter
              AND u.created_at <= :createdBefore
              AND NOT EXISTS (
                    SELECT 1
                    FROM budgets b_active
                    WHERE b_active.user_id = u.id
                      AND b_active.status = 'ACTIVE'
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM notifications read_guide
                    WHERE read_guide.user_id = u.id
                      AND read_guide.type = 'HOW_TO_USE_WISEMONIE'
                      AND read_guide.is_read = true
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM engagement_nudge_notifications n
                    WHERE n.user_id = u.id
                      AND n.campaign = 'HOW_TO_USE_WISEMONIE'
                      AND n.sent_at > :sentAfter
              )
              AND (
                    SELECT COUNT(DISTINCT all_n.sent_date)
                    FROM engagement_nudge_notifications all_n
                    WHERE all_n.user_id = u.id
                      AND all_n.campaign = 'HOW_TO_USE_WISEMONIE'
              ) < :maxSendDays
            ORDER BY u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findRecentWalletUsersNeedingHowToUseNudgeAfter(
            @Param("afterUserId") Long afterUserId,
            @Param("createdAfter") LocalDateTime createdAfter,
            @Param("createdBefore") LocalDateTime createdBefore,
            @Param("sentAfter") LocalDateTime sentAfter,
            @Param("maxSendDays") int maxSendDays,
            @Param("limit") int limit);

    /**
     * Users who already have money in the wallet but still do not have an
     * active budget. The caller supplies the "funded before" cutoff so the
     * first reminder waits a couple of days after the wallet balance appears.
     */
    @Query(value = """
            SELECT u.*
            FROM users u
            JOIN wallets w ON w.user_id = u.id
            WHERE u.is_deleted = false
              AND u.is_verified = true
              AND coalesce(u.test_account, false) = false
              AND w.status = 'ACTIVE'
              AND coalesce(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND coalesce(w.balance, 0) > 0
              AND coalesce(w.updated_at, u.created_at) <= :fundedBefore
              AND NOT EXISTS (
                    SELECT 1
                    FROM budgets b_active
                    WHERE b_active.user_id = u.id
                      AND b_active.status = 'ACTIVE'
              )
            ORDER BY coalesce(w.updated_at, u.created_at) ASC, u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findFundedWalletUsersWithoutActiveBudget(
            @Param("fundedBefore") LocalDateTime fundedBefore,
            @Param("limit") int limit);

    /**
     * Users whose latest completed budget ended at least a few days ago and who
     * have not started another active budget since.
     */
    @Query(value = """
            SELECT u.*
            FROM users u
            JOIN wallets w ON w.user_id = u.id
            WHERE u.is_deleted = false
              AND u.is_verified = true
              AND coalesce(u.test_account, false) = false
              AND w.status = 'ACTIVE'
              AND coalesce(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND NOT EXISTS (
                    SELECT 1
                    FROM budgets b_active
                    WHERE b_active.user_id = u.id
                      AND b_active.status = 'ACTIVE'
              )
              AND (
                    SELECT max(b_completed.end_date)
                    FROM budgets b_completed
                    WHERE b_completed.user_id = u.id
                      AND b_completed.status = 'COMPLETED'
              ) <= :completedBefore
            ORDER BY (
                    SELECT max(b_completed.end_date)
                    FROM budgets b_completed
                    WHERE b_completed.user_id = u.id
                      AND b_completed.status = 'COMPLETED'
              ) ASC, u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findUsersDormantAfterCompletedBudget(
            @Param("completedBefore") LocalDate completedBefore,
            @Param("limit") int limit);

    /**
     * Users who have a successfully provisioned wallet and at least one active
     * app session capable of receiving FCM push. Used for salary-period nudges:
     * push only, no email fallback.
     */
    @Query(value = """
            SELECT DISTINCT u.id
            FROM users u
            JOIN wallets w ON w.user_id = u.id
            JOIN auth_sessions s ON s.user_id = u.id
            WHERE u.is_deleted = false
              AND u.id > :afterUserId
              AND u.is_verified = true
              AND coalesce(u.test_account, false) = false
              AND w.status = 'ACTIVE'
              AND coalesce(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND s.revoked = false
              AND s.fcm_token IS NOT NULL
              AND btrim(s.fcm_token) <> ''
            ORDER BY u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findPushEligibleWalletUserIdsAfter(
            @Param("afterUserId") Long afterUserId,
            @Param("limit") int limit);

    @Query(value = """
            SELECT DISTINCT u.*
            FROM users u
            JOIN wallets w ON w.user_id = u.id
            JOIN budgets b ON b.user_id = u.id
            WHERE u.is_deleted = false
              AND u.id > :afterUserId
              AND u.is_verified = true
              AND coalesce(u.test_account, false) = false
              AND w.status = 'ACTIVE'
              AND coalesce(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND b.status = 'ACTIVE'
            ORDER BY u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findActiveBudgetEngagementUsersAfter(
            @Param("afterUserId") Long afterUserId,
            @Param("limit") int limit);

    @Query(value = """
            SELECT DISTINCT u.*
            FROM users u
            JOIN wallets w ON w.user_id = u.id
            WHERE u.is_deleted = false
              AND u.id > :afterUserId
              AND u.is_verified = true
              AND coalesce(u.test_account, false) = false
              AND w.status = 'ACTIVE'
              AND coalesce(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND NOT EXISTS (
                    SELECT 1
                    FROM budgets b_active
                    WHERE b_active.user_id = u.id
                      AND b_active.status = 'ACTIVE'
              )
            ORDER BY u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findNoActiveBudgetEngagementUsersAfter(
            @Param("afterUserId") Long afterUserId,
            @Param("limit") int limit);

    @Query(value = """
            SELECT DISTINCT u.*
            FROM users u
            JOIN wallets w ON w.user_id = u.id
            WHERE u.is_deleted = false
              AND u.id > :afterUserId
              AND u.is_verified = true
              AND coalesce(u.test_account, false) = false
              AND w.status = 'ACTIVE'
              AND coalesce(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND u.email IS NOT NULL
              AND btrim(u.email) <> ''
              AND substring(u.profile_data ->> 'dateOfBirth' from 6 for 5) = :monthDay
            ORDER BY u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findBirthdayWalletUsersAfter(
            @Param("monthDay") String monthDay,
            @Param("afterUserId") Long afterUserId,
            @Param("limit") int limit);

    /**
     * Users who verified signup at least 48h ago and have not returned after
     * the initial signup session grace window.
     */
    @Query(value = """
            SELECT u.*
            FROM users u
            WHERE u.is_deleted = false
              AND u.id > :afterUserId
              AND u.is_verified = true
              AND coalesce(u.test_account, false) = false
              AND u.email IS NOT NULL
              AND btrim(u.email) <> ''
              AND u.created_at >= :createdAfter
              AND u.created_at <= :eligibleBefore
              AND (
                    u.last_login IS NULL
                    OR u.last_login <= u.created_at + (:returnGraceMinutes * INTERVAL '1 minute')
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM auth_sessions s
                    WHERE s.user_id = u.id
                      AND coalesce(s.last_seen_at, s.created_at)
                          > u.created_at + (:returnGraceMinutes * INTERVAL '1 minute')
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM engagement_nudge_notifications n
                    WHERE n.user_id = u.id
                      AND n.campaign = 'SIGNUP_RETURN_48H'
              )
            ORDER BY u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findSignupUsersInactiveAfter48Hours(
            @Param("afterUserId") Long afterUserId,
            @Param("createdAfter") LocalDateTime createdAfter,
            @Param("eligibleBefore") LocalDateTime eligibleBefore,
            @Param("returnGraceMinutes") int returnGraceMinutes,
            @Param("limit") int limit);

    @Query(value = """
            SELECT u.*
            FROM users u
            LEFT JOIN wallets w ON w.user_id = u.id
            WHERE w.id IS NULL
              AND u.is_deleted = false
              AND coalesce(u.test_account, false) = false
              AND u.email IS NOT NULL
              AND btrim(u.email) <> ''
              AND u.created_at <= :inactiveBefore
              AND (u.last_login IS NULL OR u.last_login <= :inactiveBeforeInstant)
              AND coalesce((
                    SELECT max(coalesce(s.last_seen_at, s.created_at))
                    FROM auth_sessions s
                    WHERE s.user_id = u.id
              ), u.created_at) <= :inactiveBefore
              AND (
                    u.last_onboarding_reminder_at IS NULL
                    OR u.last_onboarding_reminder_at <= :recentOnboardingBefore
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM engagement_nudge_notifications n
                    WHERE n.user_id = u.id
                      AND n.campaign = 'SETUP_RECOVERY_15D'
              )
            ORDER BY u.created_at ASC, u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findDormantUsersWithoutWalletForRecovery(
            @Param("inactiveBefore") LocalDateTime inactiveBefore,
            @Param("inactiveBeforeInstant") Instant inactiveBeforeInstant,
            @Param("recentOnboardingBefore") LocalDateTime recentOnboardingBefore,
            @Param("limit") int limit);

    @Query(value = """
            SELECT u.*
            FROM users u
            JOIN wallets w ON w.user_id = u.id
            WHERE u.is_deleted = false
              AND coalesce(u.test_account, false) = false
              AND u.email IS NOT NULL
              AND btrim(u.email) <> ''
              AND w.status = 'ACTIVE'
              AND coalesce(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND coalesce(w.balance, 0) <= 0
              AND u.created_at <= :inactiveBefore
              AND (u.last_login IS NULL OR u.last_login <= :inactiveBeforeInstant)
              AND coalesce((
                    SELECT max(coalesce(s.last_seen_at, s.created_at))
                    FROM auth_sessions s
                    WHERE s.user_id = u.id
              ), u.created_at) <= :inactiveBefore
              AND NOT EXISTS (
                    SELECT 1
                    FROM budgets b
                    WHERE b.user_id = u.id
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM savings_goals sg
                    WHERE sg.user_id = u.id
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM engagement_nudge_notifications n
                    WHERE n.user_id = u.id
                      AND n.campaign = 'SETUP_RECOVERY_15D'
              )
            ORDER BY coalesce(w.updated_at, u.created_at) ASC, u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findDormantWalletUsersNotFundedForRecovery(
            @Param("inactiveBefore") LocalDateTime inactiveBefore,
            @Param("inactiveBeforeInstant") Instant inactiveBeforeInstant,
            @Param("limit") int limit);

    @Query(value = """
            SELECT u.*
            FROM users u
            JOIN wallets w ON w.user_id = u.id
            WHERE u.is_deleted = false
              AND coalesce(u.test_account, false) = false
              AND u.email IS NOT NULL
              AND btrim(u.email) <> ''
              AND w.status = 'ACTIVE'
              AND coalesce(w.is_revenue_wallet, false) = false
              AND w.account_number IS NOT NULL
              AND btrim(w.account_number) <> ''
              AND coalesce(w.balance, 0) > 0
              AND u.created_at <= :inactiveBefore
              AND (u.last_login IS NULL OR u.last_login <= :inactiveBeforeInstant)
              AND coalesce((
                    SELECT max(coalesce(s.last_seen_at, s.created_at))
                    FROM auth_sessions s
                    WHERE s.user_id = u.id
              ), u.created_at) <= :inactiveBefore
              AND NOT EXISTS (
                    SELECT 1
                    FROM budgets b
                    WHERE b.user_id = u.id
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM savings_goals sg
                    WHERE sg.user_id = u.id
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM engagement_nudge_notifications n
                    WHERE n.user_id = u.id
                      AND n.campaign = 'SETUP_RECOVERY_15D'
              )
            ORDER BY coalesce(w.updated_at, u.created_at) ASC, u.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findDormantFundedWalletUsersWithoutPlanForRecovery(
            @Param("inactiveBefore") LocalDateTime inactiveBefore,
            @Param("inactiveBeforeInstant") Instant inactiveBeforeInstant,
            @Param("limit") int limit);

    Optional<User> findByEmail(String email);

    @Query("""
            SELECT u
            FROM User u
            WHERE u.isDeleted = false
              AND (:includeTestAccounts = true OR u.testAccount = false)
            ORDER BY u.id ASC
            """)
    Slice<User> findBroadcastRecipients(@Param("includeTestAccounts") boolean includeTestAccounts,
                                        Pageable pageable);

    @Query("""
            SELECT u
            FROM User u
            WHERE u.isDeleted = false
              AND (:includeTestAccounts = true OR u.testAccount = false)
              AND u.id IN :ids
            ORDER BY u.id ASC
            """)
    List<User> findBroadcastRecipientsByIds(@Param("ids") Collection<Long> ids,
                                            @Param("includeTestAccounts") boolean includeTestAccounts);

    @Query("""
            SELECT u
            FROM User u
            WHERE u.isDeleted = false
              AND (:includeTestAccounts = true OR u.testAccount = false)
              AND lower(u.email) IN :emails
            ORDER BY u.id ASC
            """)
    List<User> findBroadcastRecipientsByEmails(@Param("emails") Collection<String> emails,
                                               @Param("includeTestAccounts") boolean includeTestAccounts);

    /**
     * Accounts where the user asked to close but a withdrawal still had to
     * happen first. Polled by AccountClosureFinalizerJob so closure completes
     * on its own once the wallet empties — the user must never be left with
     * dissolved budgets, broken savings and a still-open account.
     */
    @Query("SELECT u FROM User u WHERE u.closureRequestedAt IS NOT NULL AND u.isDeleted = false")
    List<User> findPendingClosures();

    /** All users holding a given role — used to target admins for ops alerts. */
    List<User> findByRole(Role role);
}
