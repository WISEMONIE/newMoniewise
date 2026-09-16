-- ============================================================
-- Wisemonie User Signups — July, August, September 2026
-- Run against Render PostgreSQL
-- ============================================================

SELECT
    COUNT(*) FILTER (WHERE created_at >= '2026-07-01' AND created_at < '2026-08-01') AS july,
    COUNT(*) FILTER (WHERE created_at >= '2026-08-01' AND created_at < '2026-09-01') AS august,
    COUNT(*) FILTER (WHERE created_at >= '2026-09-01' AND created_at < '2026-10-01') AS september,
    COUNT(*) FILTER (WHERE created_at >= '2026-07-01' AND created_at < '2026-10-01') AS total
FROM users
WHERE created_at >= '2026-07-01'
  AND created_at <  '2026-10-01';
