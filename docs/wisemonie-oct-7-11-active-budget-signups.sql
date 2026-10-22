-- Wisemonie users who joined October 7-11, 2026 and have an active budget.
-- Channel groups: Facebook, Instagram, and Others (including blank/unknown sources).
-- Run against the Wisemonie PostgreSQL database.

-- 1. User totals by acquisition channel.
WITH eligible_users AS (
    SELECT
        u.id AS user_id,
        CASE
            WHEN u.referral_source ILIKE '%facebook%' THEN 'Facebook'
            WHEN u.referral_source ILIKE '%instagram%' THEN 'Instagram'
            ELSE 'Others'
        END AS channel
    FROM users u
    WHERE u.created_at >= TIMESTAMP '2026-10-07 00:00:00'
      AND u.created_at <  TIMESTAMP '2026-10-12 00:00:00'
      AND EXISTS (
          SELECT 1
          FROM budgets b
          WHERE b.user_id = u.id
            AND b.status = 'ACTIVE'
      )
)
SELECT
    channel,
    COUNT(*) AS users_with_active_budget
FROM eligible_users
GROUP BY channel
ORDER BY CASE channel
    WHEN 'Facebook' THEN 1
    WHEN 'Instagram' THEN 2
    ELSE 3
END;

-- 2. Matching users and each of their active budgets.
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
    u.created_at AS joined_at,
    u.referral_source AS referral_source,
    CASE
        WHEN u.referral_source ILIKE '%facebook%' THEN 'Facebook'
        WHEN u.referral_source ILIKE '%instagram%' THEN 'Instagram'
        ELSE 'Others'
    END AS channel,
    b.name AS active_budget,
    b.total_amount AS budget_amount,
    b.created_at AS budget_created_at
FROM users u
JOIN budgets b ON b.user_id = u.id
WHERE u.created_at >= TIMESTAMP '2026-10-07 00:00:00'
  AND u.created_at <  TIMESTAMP '2026-10-12 00:00:00'
  AND b.status = 'ACTIVE'
ORDER BY channel, u.created_at, u.id, b.created_at;