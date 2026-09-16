-- ============================================================
-- Wisemonie — Users who actually created budgets/plans/envelopes
-- Run against Render PostgreSQL
-- ============================================================

-- 1. Users who created at least one budget
SELECT COUNT(DISTINCT user_id) AS users_with_budgets
FROM budgets;

-- 2. Users who created at least one envelope
SELECT COUNT(DISTINCT b.user_id) AS users_with_envelopes
FROM envelopes e
JOIN budgets b ON e.budget_id = b.id
WHERE e.deleted_at IS NULL;

-- 3. Users who created a savings goal
SELECT COUNT(DISTINCT user_id) AS users_with_savings
FROM savings_goals;

-- 4. Combined: users who did ANY of the above
SELECT COUNT(DISTINCT user_id) AS users_who_created_anything
FROM (
    SELECT user_id FROM budgets
    UNION
    SELECT b.user_id FROM envelopes e JOIN budgets b ON e.budget_id = b.id WHERE e.deleted_at IS NULL
    UNION
    SELECT user_id FROM savings_goals
) active;

-- 5. ALL-IN-ONE summary row
SELECT
    (SELECT COUNT(DISTINCT user_id) FROM budgets)                AS users_with_budgets,
    (SELECT COUNT(DISTINCT b.user_id) FROM envelopes e JOIN budgets b ON e.budget_id = b.id WHERE e.deleted_at IS NULL) AS users_with_envelopes,
    (SELECT COUNT(DISTINCT user_id) FROM savings_goals)          AS users_with_savings,
    (SELECT COUNT(DISTINCT user_id) FROM (
        SELECT user_id FROM budgets
        UNION
        SELECT b.user_id FROM envelopes e JOIN budgets b ON e.budget_id = b.id WHERE e.deleted_at IS NULL
        UNION
        SELECT user_id FROM savings_goals
    ) a)                                                          AS users_who_created_anything,
    (SELECT COUNT(*) FROM users)                                  AS total_registered_users;
