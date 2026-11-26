-- V15: Replace per-tier markup fees with a single flat fee of ₦2.25.
--
-- The old tier keys (tier1/tier2/tier3) remain in the DB for historical reference
-- and admin-dashboard display, but MarkupCalculatorService now reads only
-- transfer.markup.flat_fee and ignores them.

-- ── Insert the new flat-fee key ──────────────────────────────────────────────
INSERT INTO system_config (config_key, config_value, description)
VALUES (
    'transfer.markup.flat_fee',
    '2.25',
    'Moniewise markup fee (NGN) charged on every external transfer, regardless of amount. Default ₦2.25.'
)
ON CONFLICT (config_key) DO UPDATE
    SET config_value = '2.25',
        description  = EXCLUDED.description;

-- ── Align old tier-fee rows to the same value for consistency ────────────────
-- These keys are no longer read by the application but are visible in the admin
-- dashboard; setting them to 2.25 prevents confusion.
UPDATE system_config SET config_value = '2.25'
WHERE  config_key IN (
    'transfer.markup.tier1.fee',
    'transfer.markup.tier2.fee',
    'transfer.markup.tier3.fee'
);
