-- ============================================================
-- Delete (soft-cancel) budget 92 for user 25
-- Mirrors BudgetService.deleteBudget + refundUnusedBudgetBalance
-- ============================================================
-- Run inside a single transaction so it's all-or-nothing.

BEGIN;

-- ── 1. Verify the budget exists, belongs to user 25, and is not already cancelled ──
DO $$
DECLARE
    v_budget_id      BIGINT := 92;
    v_user_id        BIGINT := 25;
    v_budget_status  TEXT;
    v_budget_owner   BIGINT;
    v_budget_name    TEXT;
BEGIN
    SELECT b.status, b.user_id, b.name
      INTO v_budget_status, v_budget_owner, v_budget_name
      FROM budgets b
     WHERE b.id = v_budget_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Budget % not found', v_budget_id;
    END IF;
    IF v_budget_owner != v_user_id THEN
        RAISE EXCEPTION 'Budget % belongs to user %, not user %',
            v_budget_id, v_budget_owner, v_user_id;
    END IF;
    IF v_budget_status = 'CANCELLED' THEN
        RAISE NOTICE 'Budget % is already CANCELLED — nothing to do.', v_budget_id;
        RETURN;
    END IF;

    RAISE NOTICE 'Dissolving budget % (%) — status: %, owner: %',
        v_budget_id, v_budget_name, v_budget_status, v_user_id;
END $$;

-- ── 2. Delete scheduled tasks for all envelopes of this budget ──
DELETE FROM scheduled_tasks
 WHERE envelope_id IN (
     SELECT id FROM envelopes WHERE budget_id = 92
 );

-- ── 3. Refund unused envelope balances to the wallet ──
-- Only for ACTIVE / SCHEDULED budgets (funded). DRAFT was never debited,
-- COMPLETED was already refunded at natural completion.
DO $$
DECLARE
    v_budget_id     BIGINT := 92;
    v_user_id       BIGINT := 25;
    v_budget_status TEXT;
    v_total_refund  NUMERIC := 0;
    v_env           RECORD;
    v_stored_avail  NUMERIC;
    v_base_alloc    NUMERIC;
    v_moved_out     NUMERIC;
    v_moved_in      NUMERIC;
    v_left_budget   NUMERIC;
    v_ledger_avail  NUMERIC;
    v_refundable    NUMERIC;
    v_now           TIMESTAMP := NOW();
BEGIN
    SELECT status INTO v_budget_status
      FROM budgets WHERE id = v_budget_id;

    -- Only refund if funded (ACTIVE or SCHEDULED)
    IF v_budget_status NOT IN ('ACTIVE', 'SCHEDULED') THEN
        RAISE NOTICE 'Budget status is % — skipping refund (not funded).', v_budget_status;
    ELSE
        FOR v_env IN
            SELECT id, total_remaining_amount, held_amount, amount
              FROM envelopes
             WHERE budget_id = v_budget_id
        LOOP
            -- Mirrors calculateSafeRefundableAmount exactly:

            -- storedAvailable = max(0, totalRemaining - held)
            v_stored_avail := GREATEST(0,
                COALESCE(v_env.total_remaining_amount, 0) -
                COALESCE(v_env.held_amount, 0)
            );

            v_base_alloc := COALESCE(v_env.amount, 0);

            -- Money moved OUT to other envelopes (ENVELOPE_TO_ENVELOPE as source)
            SELECT COALESCE(SUM(ABS(amount)), 0) INTO v_moved_out
              FROM transaction_logs
             WHERE source_envelope_id = v_env.id
               AND transaction_type = 'ENVELOPE_TO_ENVELOPE'
               AND status IN ('COMPLETED', 'PROCESSING', 'PENDING');

            -- Money moved IN from other envelopes (ENVELOPE_TO_ENVELOPE as target)
            SELECT COALESCE(SUM(ABS(amount)), 0) INTO v_moved_in
              FROM transaction_logs
             WHERE target_envelope_id = v_env.id
               AND transaction_type = 'ENVELOPE_TO_ENVELOPE'
               AND status IN ('COMPLETED', 'PROCESSING', 'PENDING');

            -- Money that left the budget entirely (external transfers + to-user)
            SELECT COALESCE(SUM(ABS(amount)), 0) INTO v_left_budget
              FROM transaction_logs
             WHERE source_envelope_id = v_env.id
               AND transaction_type IN ('ENVELOPE_TO_EXTERNAL', 'ENVELOPE_TO_USER')
               AND status IN ('COMPLETED', 'PROCESSING', 'PENDING');

            -- ledgerAvailable = max(0, base + movedIn - movedOut - leftBudget)
            v_ledger_avail := GREATEST(0,
                v_base_alloc + v_moved_in - v_moved_out - v_left_budget
            );

            -- refundable = min(storedAvailable, ledgerAvailable)
            v_refundable := LEAST(v_stored_avail, v_ledger_avail);

            RAISE NOTICE 'Envelope %: stored_avail=%, ledger_avail=%, refundable=%',
                v_env.id, v_stored_avail, v_ledger_avail, v_refundable;

            IF v_refundable > 0 THEN
                INSERT INTO transaction_logs (
                    user_id, budget_id, source_envelope_id,
                    amount, transaction_type, status,
                    created_at, reference, description
                ) VALUES (
                    v_user_id, v_budget_id, v_env.id,
                    v_refundable, 'BUDGET_COMPLETION_REFUND', 'COMPLETED',
                    v_now,
                    'MW-REF-' || gen_random_uuid(),
                    'Unused envelope balance refunded on budget deletion'
                );
                v_total_refund := v_total_refund + v_refundable;
            END IF;
        END LOOP;

        -- Zero out all envelope balances (like natural completion)
        UPDATE envelopes
           SET remaining_amount = 0,
               total_remaining_amount = 0,
               held_amount = 0,
               next_disbursement_at = NULL,
               has_matured = TRUE
         WHERE budget_id = v_budget_id;

        -- Credit the wallet
        IF v_total_refund > 0 THEN
            UPDATE wallets
               SET balance = balance + v_total_refund
             WHERE user_id = v_user_id;

            RAISE NOTICE 'Refunded ₦% to user % wallet.', v_total_refund, v_user_id;
        ELSE
            RAISE NOTICE 'No refundable balance — envelopes already empty.';
        END IF;
    END IF;
END $$;

-- ── 4. Soft-delete the budget ──
UPDATE budgets
   SET status = 'CANCELLED',
       remaining_amount = 0
 WHERE id = 92;

-- ── 5. Release fee reserve for this budget (mirrors FeeReserveService.releaseForBudget) ──
DO $$
DECLARE
    v_reserve_id    BIGINT;
    v_reserve_bal   NUMERIC;
    v_estimate_sum  NUMERIC;
    v_release_amt   NUMERIC;
    v_new_balance   NUMERIC;
    v_new_est_total NUMERIC;
BEGIN
    -- Check if fee_reserve_estimates table exists (feature may not be deployed)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
         WHERE table_name = 'fee_reserve_estimates'
    ) THEN
        RAISE NOTICE 'fee_reserve_estimates table not found — skipping reserve release.';
        RETURN;
    END IF;

    -- Find the user's reserve
    SELECT id, balance INTO v_reserve_id, v_reserve_bal
      FROM fee_reserves WHERE user_id = 25;

    IF NOT FOUND THEN
        RAISE NOTICE 'No fee reserve for user 25 — nothing to release.';
        RETURN;
    END IF;

    -- Sum active estimates for budget 92
    SELECT COALESCE(SUM(estimated_total), 0) INTO v_estimate_sum
      FROM fee_reserve_estimates
     WHERE budget_id = 92 AND active = TRUE;

    -- Deactivate estimates for this budget
    UPDATE fee_reserve_estimates
       SET active = FALSE
     WHERE budget_id = 92 AND active = TRUE;

    -- Release = min(estimate, reserve balance)
    v_release_amt := LEAST(v_estimate_sum, v_reserve_bal);

    IF v_release_amt > 0 THEN
        -- Deduct from reserve
        UPDATE fee_reserves
           SET balance = balance - v_release_amt,
               updated_at = NOW()
         WHERE id = v_reserve_id;

        -- Credit wallet
        UPDATE wallets
           SET balance = balance + v_release_amt
         WHERE user_id = 25;

        -- Record the release transaction
        INSERT INTO fee_reserve_transactions (
            fee_reserve_id, type, amount, balance_after, reference, description, created_at
        ) VALUES (
            v_reserve_id, 'RELEASE', v_release_amt,
            v_reserve_bal - v_release_amt,
            'budget:92',
            'Budget #92 ended — released to wallet',
            NOW()
        );

        RAISE NOTICE 'Released ₦% from fee reserve to wallet.', v_release_amt;
    ELSE
        RAISE NOTICE 'No fee reserve to release (estimate=%, balance=%).', v_estimate_sum, v_reserve_bal;
    END IF;

    -- Recalculate reserve totals from remaining active estimates
    SELECT COALESCE(SUM(estimated_total), 0) INTO v_new_est_total
      FROM fee_reserve_estimates
     WHERE fee_reserve_id = v_reserve_id AND active = TRUE;

    v_new_balance := v_reserve_bal - v_release_amt;

    UPDATE fee_reserves
       SET estimated_total_fees = v_new_est_total,
           shortfall = GREATEST(0, v_new_est_total - v_new_balance),
           updated_at = NOW()
     WHERE id = v_reserve_id;
END $$;

-- ── 6. Verify ──
SELECT 'BUDGET' AS entity, id, name, status, remaining_amount::TEXT
  FROM budgets WHERE id = 92
UNION ALL
SELECT 'WALLET', user_id, 'balance', '', balance::TEXT
  FROM wallets WHERE user_id = 25;

COMMIT;
