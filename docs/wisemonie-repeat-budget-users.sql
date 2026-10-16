-- Users who have created more than one budget, with their latest budget and wallet balance.
-- Run against the Wisemonie PostgreSQL database.

WITH repeat_budget_users AS (
    SELECT
        user_id,
        COUNT(*) AS budget_count
    FROM budgets
    GROUP BY user_id
    HAVING COUNT(*) > 1
), latest_budget AS (
    SELECT DISTINCT ON (b.user_id)
        b.user_id,
        b.name AS recent_budget,
        b.total_amount AS recent_budget_amount,
        b.created_at AS recent_budget_created_at
    FROM budgets b
    JOIN repeat_budget_users rbu ON rbu.user_id = b.user_id
    ORDER BY b.user_id, b.created_at DESC NULLS LAST, b.id DESC
), wallet_balances AS (
    SELECT
        w.user_id,
        SUM(w.balance) AS wallet_balance
    FROM wallets w
    WHERE w.status = 'ACTIVE'
      AND COALESCE(w.is_revenue_wallet, false) = false
    GROUP BY w.user_id
)
SELECT
    COALESCE(
        NULLIF(BTRIM(u.profile_data ->> 'name'), ''),
        NULLIF(BTRIM(CONCAT_WS(' ',
            NULLIF(BTRIM(u.profile_data ->> 'firstName'), ''),
            NULLIF(BTRIM(u.profile_data ->> 'lastName'), '')
        )), ''),
        'Name not provided'
    ) AS name,
    u.phone AS phone_number,
    rbu.budget_count,
    lb.recent_budget,
    lb.recent_budget_amount,
    lb.recent_budget_created_at,
    COALESCE(wb.wallet_balance, 0) AS wallet_balance
FROM repeat_budget_users rbu
JOIN users u ON u.id = rbu.user_id
JOIN latest_budget lb ON lb.user_id = rbu.user_id
LEFT JOIN wallet_balances wb ON wb.user_id = rbu.user_id
ORDER BY lb.recent_budget_created_at DESC NULLS LAST, u.id;