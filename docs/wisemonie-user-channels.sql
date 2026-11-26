-- ============================================================
-- Wisemonie — User Acquisition Channels
-- Run against Render PostgreSQL
-- ============================================================

SELECT
    COALESCE(referral_source, 'unknown')  AS channel,
    COUNT(*)                              AS users,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS percent
FROM users
GROUP BY referral_source
ORDER BY users DESC;
