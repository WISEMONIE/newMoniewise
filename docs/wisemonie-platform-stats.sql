-- ============================================================
-- Wisemonie Platform Statistics
-- Run against Render PostgreSQL
-- ============================================================

-- DIAGNOSTIC: see what transaction types exist and their totals
-- (run this first to understand your data)
SELECT
    transaction_type,
    status,
    COUNT(*)                          AS tx_count,
    SUM(amount)                       AS raw_total,
    SUM(ABS(amount))                  AS abs_total
FROM transaction_logs
WHERE status IN ('COMPLETED', 'SUCCESS')
GROUP BY transaction_type, status
ORDER BY transaction_type;

-- ============================================================
-- ACTUAL STATS
-- ============================================================

-- 1. TOTAL DEPOSITS (money coming IN from outside)
SELECT
    COUNT(*)                          AS total_deposit_count,
    COALESCE(SUM(ABS(amount)), 0)     AS total_deposit_amount
FROM transaction_logs
WHERE transaction_type = 'wallet_deposit'
  AND status IN ('COMPLETED', 'SUCCESS');

-- 2. TOTAL WITHDRAWALS (money going OUT to external banks/users)
SELECT
    COUNT(*)                          AS total_withdrawal_count,
    COALESCE(SUM(ABS(amount)), 0)     AS total_withdrawal_amount
FROM transaction_logs
WHERE transaction_type IN (
    'wallet_withdrawal',
    'envelope_to_external',
    'wallet_to_external'
)
AND status IN ('COMPLETED', 'SUCCESS');

-- 3. TOTAL USER-FACING TRANSACTIONS (deposits + withdrawals + transfers + VAS, NOT internal moves)
SELECT
    COUNT(*)                          AS total_transaction_count,
    COALESCE(SUM(ABS(amount)), 0)     AS total_transaction_volume
FROM transaction_logs
WHERE transaction_type IN (
    'wallet_deposit',
    'wallet_withdrawal',
    'envelope_to_external',
    'wallet_to_external',
    'wallet_to_user',
    'envelope_to_user',
    'user_to_user',
    'user_to_envelope',
    'vas_purchase'
)
AND status IN ('COMPLETED', 'SUCCESS');

-- 4. CURRENT FUNDS HELD / MANAGED
--    4a. User wallet balances
SELECT
    COUNT(*)                          AS total_user_wallets,
    COALESCE(SUM(balance), 0)         AS total_wallet_balance
FROM wallets
WHERE is_revenue_wallet = false
  AND status = 'ACTIVE';

--    4b. Active budget funds
SELECT
    COUNT(*)                          AS active_budget_count,
    COALESCE(SUM(total_amount), 0)    AS total_budgeted_amount
FROM budgets
WHERE status = 'ACTIVE';

--    4c. Envelope balances (active budgets only)
SELECT
    COUNT(*)                                              AS total_envelopes,
    COALESCE(SUM(e.amount + e.remaining_amount), 0)       AS total_in_envelopes
FROM envelopes e
JOIN budgets b ON e.budget_id = b.id
WHERE e.deleted_at IS NULL
  AND b.status = 'ACTIVE';

--    4d. Savings balances
SELECT
    COUNT(*)                          AS active_savings_goals,
    COALESCE(SUM(current_balance), 0) AS total_savings_balance
FROM savings_goals
WHERE status = 'ACTIVE';

--    4e. GRAND TOTAL funds under management
SELECT
    COALESCE(w.wallet_total, 0)
      + COALESCE(env.envelope_total, 0)
      + COALESCE(s.savings_total, 0)  AS total_funds_under_management
FROM
    (SELECT SUM(balance) AS wallet_total FROM wallets WHERE is_revenue_wallet = false AND status = 'ACTIVE') w,
    (SELECT SUM(e.amount + e.remaining_amount) AS envelope_total FROM envelopes e JOIN budgets b ON e.budget_id = b.id WHERE e.deleted_at IS NULL AND b.status = 'ACTIVE') env,
    (SELECT SUM(current_balance) AS savings_total FROM savings_goals WHERE status = 'ACTIVE') s;

-- 5. REVENUE EARNED
SELECT
    COALESCE(SUM(amount), 0)          AS total_revenue
FROM revenue_logs;

-- ============================================================
-- ALL-IN-ONE SUMMARY
-- ============================================================
SELECT
    dep.total_deposit_count,
    dep.total_deposit_amount,
    wd.total_withdrawal_count,
    wd.total_withdrawal_amount,
    txn.total_transaction_count,
    txn.total_transaction_volume,
    funds.total_funds_under_management,
    rev.total_revenue
FROM
    (SELECT COUNT(*) AS total_deposit_count, COALESCE(SUM(ABS(amount)),0) AS total_deposit_amount
     FROM transaction_logs WHERE transaction_type = 'wallet_deposit' AND status IN ('COMPLETED','SUCCESS')) dep,

    (SELECT COUNT(*) AS total_withdrawal_count, COALESCE(SUM(ABS(amount)),0) AS total_withdrawal_amount
     FROM transaction_logs WHERE transaction_type IN ('wallet_withdrawal','envelope_to_external','wallet_to_external') AND status IN ('COMPLETED','SUCCESS')) wd,

    (SELECT COUNT(*) AS total_transaction_count, COALESCE(SUM(ABS(amount)),0) AS total_transaction_volume
     FROM transaction_logs WHERE transaction_type IN ('wallet_deposit','wallet_withdrawal','envelope_to_external','wallet_to_external','wallet_to_user','envelope_to_user','user_to_user','user_to_envelope','vas_purchase') AND status IN ('COMPLETED','SUCCESS')) txn,

    (SELECT COALESCE(w.wt,0) + COALESCE(e.et,0) + COALESCE(s.st,0) AS total_funds_under_management
     FROM (SELECT SUM(balance) AS wt FROM wallets WHERE is_revenue_wallet = false AND status = 'ACTIVE') w,
          (SELECT SUM(e.amount + e.remaining_amount) AS et FROM envelopes e JOIN budgets b ON e.budget_id = b.id WHERE e.deleted_at IS NULL AND b.status = 'ACTIVE') e,
          (SELECT SUM(current_balance) AS st FROM savings_goals WHERE status = 'ACTIVE') s) funds,

    (SELECT COALESCE(SUM(amount),0) AS total_revenue FROM revenue_logs) rev;
