-- ============================================================
-- Wisemonie — User Demographics
-- Run against Render PostgreSQL
-- ============================================================

-- 1. GENDER SPLIT
SELECT
    COALESCE(UPPER(k.gender), 'NOT PROVIDED') AS gender,
    COUNT(*)                                  AS users,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS percent
FROM users u
LEFT JOIN kyc_profiles k ON k.user_id = u.id
GROUP BY UPPER(k.gender)
ORDER BY users DESC;

-- 2. AGE DISTRIBUTION
SELECT
    CASE
        WHEN age < 18 THEN 'Under 18'
        WHEN age BETWEEN 18 AND 24 THEN '18-24'
        WHEN age BETWEEN 25 AND 34 THEN '25-34'
        WHEN age BETWEEN 35 AND 44 THEN '35-44'
        WHEN age >= 45 THEN '45+'
        ELSE 'Unknown'
    END AS age_group,
    COUNT(*) AS users,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS percent
FROM (
    SELECT
        CASE
            WHEN u.profile_data->>'dateOfBirth' IS NOT NULL
                 AND u.profile_data->>'dateOfBirth' != ''
            THEN EXTRACT(YEAR FROM AGE(NOW(),
                 TO_DATE(u.profile_data->>'dateOfBirth', 'Mon DD, YYYY')))
            ELSE NULL
        END AS age
    FROM users u
    WHERE u.profile_data IS NOT NULL
) ages
WHERE age IS NOT NULL
GROUP BY 1
ORDER BY MIN(age) NULLS LAST;

-- 3. AVERAGE AGE
SELECT
    ROUND(AVG(age), 1)  AS average_age,
    MIN(age)::INT       AS youngest,
    MAX(age)::INT       AS oldest
FROM (
    SELECT EXTRACT(YEAR FROM AGE(NOW(),
           TO_DATE(u.profile_data->>'dateOfBirth', 'Mon DD, YYYY'))) AS age
    FROM users u
    WHERE u.profile_data->>'dateOfBirth' IS NOT NULL
      AND u.profile_data->>'dateOfBirth' != ''
) ages;

-- 4. OCCUPATION
SELECT
    INITCAP(u.profile_data->>'occupation') AS occupation,
    COUNT(*)                               AS users,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS percent
FROM users u
WHERE u.profile_data->>'occupation' IS NOT NULL
  AND u.profile_data->>'occupation' != ''
GROUP BY INITCAP(u.profile_data->>'occupation')
ORDER BY users DESC;

-- 5. LOCATION — STATE OF RESIDENCE
SELECT
    COALESCE(INITCAP(k.state_of_residence), 'Not provided') AS state,
    COUNT(*)                                                 AS users,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1)      AS percent
FROM users u
LEFT JOIN kyc_profiles k ON k.user_id = u.id
GROUP BY INITCAP(k.state_of_residence)
ORDER BY users DESC;

-- 6. MAIN EXPENSE CATEGORY
SELECT
    INITCAP(u.profile_data->>'mainExpense') AS main_expense,
    COUNT(*)                                AS users,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS percent
FROM users u
WHERE u.profile_data->>'mainExpense' IS NOT NULL
  AND u.profile_data->>'mainExpense' != ''
GROUP BY INITCAP(u.profile_data->>'mainExpense')
ORDER BY users DESC;

-- 7. ACQUISITION CHANNEL
SELECT
    COALESCE(u.referral_source, 'Unknown') AS channel,
    COUNT(*)                               AS users,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS percent
FROM users u
GROUP BY u.referral_source
ORDER BY users DESC;

-- 8. MARITAL STATUS
SELECT
    COALESCE(INITCAP(k.marital_status), 'Not provided') AS marital_status,
    COUNT(*)                                             AS users,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1)  AS percent
FROM users u
LEFT JOIN kyc_profiles k ON k.user_id = u.id
GROUP BY INITCAP(k.marital_status)
ORDER BY users DESC;

-- 9. KYC COMPLETION RATE
SELECT
    COUNT(DISTINCT u.id)                                          AS total_users,
    COUNT(DISTINCT k.user_id)                                     AS kyc_completed,
    COUNT(DISTINCT u.id) - COUNT(DISTINCT k.user_id)              AS kyc_pending,
    ROUND(COUNT(DISTINCT k.user_id) * 100.0 / COUNT(DISTINCT u.id), 1) AS kyc_completion_pct
FROM users u
LEFT JOIN kyc_profiles k ON k.user_id = u.id AND k.bvn_verified = true;
