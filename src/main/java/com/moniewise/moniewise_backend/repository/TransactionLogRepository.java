package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {

    // KEEP ALL THESE — THEY ARE PERFECT
    @Query("SELECT t FROM TransactionLog t WHERE t.sourceEnvelopeId = :envelopeId AND t.createdAt >= :startTime AND t.createdAt < :endTime")
    List<TransactionLog> findBySourceEnvelopeIdAndTimeRange(Long envelopeId, LocalDateTime startTime, LocalDateTime endTime);

    Page<TransactionLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Page<TransactionLog> findByBudgetIdOrderByCreatedAtDesc(Long budgetId, Pageable pageable);

    @Query("""
        SELECT t FROM TransactionLog t
        WHERE (t.sourceEnvelopeId = :envelopeId OR t.targetEnvelopeId = :envelopeId)
          AND t.userId = :userId
        ORDER BY t.createdAt DESC
        """)
    Page<TransactionLog> findByEnvelopeId(
            @Param("userId") Long userId,
            @Param("envelopeId") Long envelopeId,
            Pageable pageable);

    // Same as findByEnvelopeId but restricted to a caller-supplied set of
    // user-visible types (mirrors findUserVisibleTransactions) — used for the
    // envelope-scoped transaction history screen, which must not surface
    // internal-only transaction types.
    @Query("""
        SELECT t FROM TransactionLog t
        WHERE (t.sourceEnvelopeId = :envelopeId OR t.targetEnvelopeId = :envelopeId)
          AND t.userId = :userId
          AND t.transactionType IN :types
        ORDER BY t.createdAt DESC
        """)
    Page<TransactionLog> findByEnvelopeIdAndTransactionTypeIn(
            @Param("userId") Long userId,
            @Param("envelopeId") Long envelopeId,
            @Param("types") Set<TransactionType> types,
            Pageable pageable);

    Page<TransactionLog> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long userId, LocalDateTime start, LocalDateTime end, Pageable pageable);

    // Only keep ONE of these (this is the best)
    List<TransactionLog> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    // Keep your old ones if you use them elsewhere
    List<TransactionLog> findByUserId(Long userId);
    List<TransactionLog> findByBudgetId(Long budgetId);

    @Query("""
        SELECT t FROM TransactionLog t
        WHERE t.userId = :userId
          AND (
              t.budgetId = :budgetId
              OR t.sourceEnvelopeId IN (
                  SELECT e.id FROM Envelope e WHERE e.budget.id = :budgetId
              )
              OR t.targetEnvelopeId IN (
                  SELECT e.id FROM Envelope e WHERE e.budget.id = :budgetId
              )
          )
        """)
    List<TransactionLog> findBudgetActivityLogs(
            @Param("userId") Long userId,
            @Param("budgetId") Long budgetId);

    List<TransactionLog> findByBudgetIdAndTransactionTypeOrderByCreatedAtAsc(
            Long budgetId,
            TransactionType transactionType);

    @Query("SELECT t FROM TransactionLog t WHERE t.userId = :userId " +
            "AND t.transactionType IN :types " +
            "ORDER BY t.createdAt DESC")
    Page<TransactionLog> findUserVisibleTransactions(
            @Param("userId") Long userId,
            @Param("types") Set<TransactionType> types,
            Pageable pageable);

    @Query("""
        SELECT t FROM TransactionLog t
        WHERE t.userId = :userId
        AND (:types IS NULL OR t.transactionType IN :types)
        ORDER BY t.createdAt DESC
    """)
    List<TransactionLog> findUserTransactions(
            @Param("userId") Long userId,
            @Param("types") Set<TransactionType> types
    );

    Optional<TransactionLog> findByReference(String reference);

    boolean existsByReference(String transactionReference);

    boolean existsByUserIdAndTransactionTypeInAndStatusIn(
            Long userId,
            Set<TransactionType> transactionTypes,
            Set<TransactionStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TransactionLog t WHERE t.reference = :reference")
    Optional<TransactionLog> findByReferenceForUpdate(@Param("reference") String reference);

    boolean existsBySourceEnvelopeIdAndTransactionTypeAndStatusInAndReferenceStartingWith(
            Long sourceEnvelopeId,
            TransactionType transactionType,
            List<TransactionStatus> statuses,
            String referencePrefix);

    // 👇 ADD THIS NUCLEAR METHOD 👇
    // 👇 FIX: Use ABS() to handle both negative and positive log entries correctly
    @Query("""
    SELECT COALESCE(SUM(ABS(t.amount)), 0)
    FROM TransactionLog t
    WHERE t.sourceEnvelopeId = :envelopeId
      AND t.createdAt >= :startDate
      AND t.transactionType IN :types
      AND t.status IN :statuses
""")
    BigDecimal calculateTotalSpent(
            @Param("envelopeId") Long envelopeId,
            @Param("startDate") LocalDateTime startDate,
            @Param("types") List<TransactionType> types,
            @Param("statuses") List<TransactionStatus> statuses
    );

    @Query("""
        SELECT COALESCE(SUM(ABS(t.amount)), 0) FROM TransactionLog t
        WHERE t.userId = :userId
          AND t.createdAt >= :start
          AND t.createdAt < :end
          AND t.transactionType IN :types
          AND t.status IN :statuses
    """)
    BigDecimal sumAbsoluteAmountByUserAndDateRangeAndTypes(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("types") Set<TransactionType> types,
            @Param("statuses") Set<TransactionStatus> statuses
    );

    @Query("""
        SELECT COALESCE(SUM(COALESCE(t.fee, 0)), 0) FROM TransactionLog t
        WHERE t.userId = :userId
          AND t.createdAt >= :start
          AND t.createdAt < :end
          AND t.transactionType IN :types
          AND t.status IN :statuses
    """)
    BigDecimal sumFeesByUserAndDateRangeAndTypes(
            @Param("userId") Long userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("types") Set<TransactionType> types,
            @Param("statuses") Set<TransactionStatus> statuses
    );

    Optional<TransactionLog> findByProviderReference(String providerReference);

    boolean existsByProviderNameAndProviderReference(String providerName, String providerReference);

    /**
     * Finds an envelope external transfer (sourceEnvelopeId IS NOT NULL) by provider reference.
     *
     * <p>Used by {@code settleExternalTransferIfExists} as a safe fallback when the Rubies
     * DR webhook carries the NIP session ID as {@code paymentReference}.  The companion
     * {@code -FEE} TransactionLog previously shared the same {@code provider_reference},
     * which caused {@link #findByProviderReference} to throw
     * {@code IncorrectResultSizeDataAccessException} (2 rows, 1 expected).
     * This query filters to rows that have a {@code sourceEnvelopeId}, which uniquely
     * identifies the main EXT- record and skips the FEE companion.
     */
    @Query("""
        SELECT t FROM TransactionLog t
        WHERE t.providerReference = :ref
          AND t.sourceEnvelopeId IS NOT NULL
        ORDER BY t.createdAt ASC
        """)
    Optional<TransactionLog> findEnvelopeTransferByProviderReference(@Param("ref") String ref);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT t FROM TransactionLog t
        WHERE t.providerReference = :ref
          AND t.sourceEnvelopeId IS NOT NULL
          AND t.transactionType = com.moniewise.moniewise_backend.enums.TransactionType.ENVELOPE_TO_EXTERNAL
        ORDER BY t.createdAt ASC
        """)
    Optional<TransactionLog> findEnvelopeTransferByProviderReferenceForUpdate(@Param("ref") String ref);

    Optional<TransactionLog> findFirstByUserIdAndTransactionTypeAndReferenceInOrderByCreatedAtDesc(
            Long userId,
            TransactionType transactionType,
            Set<String> references);

    Optional<TransactionLog> findFirstByUserIdAndTransactionTypeAndProviderReferenceOrderByCreatedAtDesc(
            Long userId,
            TransactionType transactionType,
            String providerReference);

    /** Used by AiInsightService to detect the user's habitual transfer day/time pattern. */
    @Query("""
        SELECT t FROM TransactionLog t
        WHERE t.userId = :userId
          AND t.createdAt >= :since
          AND t.transactionType IN (
              com.moniewise.moniewise_backend.enums.TransactionType.ENVELOPE_TO_EXTERNAL,
              com.moniewise.moniewise_backend.enums.TransactionType.WALLET_TO_EXTERNAL,
              com.moniewise.moniewise_backend.enums.TransactionType.USER_TO_USER,
              com.moniewise.moniewise_backend.enums.TransactionType.WALLET_TO_USER,
              com.moniewise.moniewise_backend.enums.TransactionType.ENVELOPE_TO_USER,
              com.moniewise.moniewise_backend.enums.TransactionType.WALLET_WITHDRAWAL
          )
        ORDER BY t.createdAt DESC
    """)
    List<TransactionLog> findRecentOutgoingTransfers(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since
    );


    @Query("""
       SELECT COALESCE(SUM(ABS(t.amount)), 0)
       FROM TransactionLog t
       WHERE t.sourceEnvelopeId = :envelopeId
       AND t.transactionType IN :types
       AND t.status IN :statuses
       """)
    BigDecimal sumAbsAmountBySourceEnvelopeAndTypesAndStatuses(
            @Param("envelopeId") Long envelopeId,
            @Param("types") List<TransactionType> types,
            @Param("statuses") List<TransactionStatus> statuses
    );

    @Query("""
       SELECT COALESCE(SUM(ABS(t.amount)), 0)
       FROM TransactionLog t
       WHERE t.targetEnvelopeId = :envelopeId
       AND t.transactionType IN :types
       AND t.status IN :statuses
       """)
    BigDecimal sumAbsAmountByTargetEnvelopeAndTypesAndStatuses(
            @Param("envelopeId") Long envelopeId,
            @Param("types") List<TransactionType> types,
            @Param("statuses") List<TransactionStatus> statuses
    );

    /**
     * Recent envelope-to-bank transfers for a given user, newest first.
     * Includes both COMPLETED and PROCESSING records — PROCESSING means the Rubies
     * NIP transfer went through but our webhook hasn't confirmed it yet; the account
     * number was verified at initiation time, so it is still a valid auto-suggest entry.
     * FAILED / REVERSED records are excluded to avoid surfacing mistyped accounts.
     *
     * <p>Used by {@code WalletService.getRecentRecipients()} to power the "transferred
     * before" auto-suggest dropdown — supplement to the Withdrawal-table query so
     * EXT- envelope transfers also appear as suggestions.
     */
    @Query("""
        SELECT t FROM TransactionLog t
        WHERE t.userId = :userId
          AND t.status IN (
              com.moniewise.moniewise_backend.enums.TransactionStatus.COMPLETED,
              com.moniewise.moniewise_backend.enums.TransactionStatus.PROCESSING
          )
          AND t.sourceEnvelopeId IS NOT NULL
          AND t.externalAccountNumber IS NOT NULL
        ORDER BY t.createdAt DESC
        """)
    List<TransactionLog> findRecentCompletedEnvelopeExternalTransfers(
            @Param("userId") Long userId,
            org.springframework.data.domain.Pageable pageable
    );

    /**
     * Finds envelope-to-external transfers that have been stuck in PROCESSING
     * longer than the given cutoff time.  Used by the TSQ recovery scheduler to
     * poll Rubies for a final status when the DR webhook is delayed or missing.
     *
     * <p>Only the main transfer log (ENVELOPE_TO_EXTERNAL, not the FEE companion)
     * is returned, and only rows that have a {@code providerReference} (i.e. the
     * Rubies NIP session ID) so the TSQ call has something to query against.
     */
    @Query("""
        SELECT t FROM TransactionLog t
        WHERE t.status = com.moniewise.moniewise_backend.enums.TransactionStatus.PROCESSING
          AND t.transactionType = com.moniewise.moniewise_backend.enums.TransactionType.ENVELOPE_TO_EXTERNAL
          AND t.sourceEnvelopeId IS NOT NULL
          AND t.providerReference IS NOT NULL
          AND t.createdAt < :cutoff
        ORDER BY t.createdAt ASC
        """)
    List<TransactionLog> findProcessingEnvelopeExternalTransfers(
            @Param("cutoff") java.time.LocalDateTime cutoff
    );

    @Query("""
        SELECT t FROM TransactionLog t
        WHERE t.status = com.moniewise.moniewise_backend.enums.TransactionStatus.COMPLETED
          AND t.sourceEnvelopeId IS NOT NULL
          AND t.fee IS NOT NULL
          AND t.fee > 0
          AND t.reference NOT LIKE '%-FEE'
        """)
    List<TransactionLog> findCompletedTransfersWithFees();

}
