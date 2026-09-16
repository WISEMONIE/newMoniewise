-- ============================================================
-- Wisemonie Revenue Breakdown
-- Run against Render PostgreSQL
-- ============================================================

-- 1. Revenue by type
SELECT
    type,
    COUNT(*)                      AS transaction_count,
    COALESCE(SUM(amount), 0)      AS total_amount
FROM revenue_logs
GROUP BY type
ORDER BY total_amount DESC;

-- 2. Monthly revenue breakdown
SELECT
    TO_CHAR(created_at, 'YYYY-MM')    AS month,
    TRIM(TO_CHAR(created_at, 'Month')) AS month_name,
    COUNT(*)                          AS transactions,
    COALESCE(SUM(amount), 0)          AS revenue
FROM revenue_logs
GROUP BY TO_CHAR(created_at, 'YYYY-MM'), TO_CHAR(created_at, 'Month')
ORDER BY month;

-- 3. ALL-IN-ONE summary
SELECT
    COALESCE(SUM(amount) FILTER (WHERE type = 'budget_creation'), 0)                AS budget_creation_revenue,
    COALESCE(SUM(amount) FILTER (WHERE type = 'budget_creation_fee'), 0)             AS budget_creation_fees,
    COALESCE(SUM(amount) FILTER (WHERE type = 'movement_fee'), 0)                    AS movement_fees,
    COALESCE(SUM(amount) FILTER (WHERE type = 'emergency_fee'), 0)                   AS emergency_fees,
    COALESCE(SUM(amount) FILTER (WHERE type = 'envelope_transfer_fee'), 0)            AS envelope_transfer_fees,
    COALESCE(SUM(amount) FILTER (WHERE type = 'envelope_external_transfer_fee'), 0)   AS external_transfer_fees,
    COALESCE(SUM(amount) FILTER (WHERE type = 'transfer_markup_fee'), 0)              AS transfer_markup_fees,
    COALESCE(SUM(amount) FILTER (WHERE type = 'vas_markup'), 0)                       AS vas_markup_revenue,
    COALESCE(SUM(amount) FILTER (WHERE type IN ('movement_fee','emergency_fee','envelope_transfer_fee','envelope_external_transfer_fee','budget_creation_fee','transfer_markup_fee','vas_markup')), 0) AS total_transaction_fees,
    COALESCE(SUM(amount), 0)                                                          AS grand_total_revenue
FROM revenue_logs;
