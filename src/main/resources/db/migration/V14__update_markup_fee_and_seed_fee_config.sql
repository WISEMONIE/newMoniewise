-- V14: Adjust transfer markup fee tier 1 to ₦2.25
--       Total user cost for transfers ≤ ₦5,000: NIP ₦10.75 + markup ₦2.25 = ₦13.00
--
-- Also seeds NIP fee tiers and revenue-account placeholders into system_config
-- for admin dashboard visibility. These already have code-level defaults;
-- adding them here makes them editable without touching application code.

-- ── Markup tier 1 fee: ₦50 → ₦2.25 ──────────────────────────────────────────
UPDATE system_config
SET    config_value  = '2.25',
       description   = 'Markup fee (NGN) for tier 1 transfers (≤ ₦5,000). NIP fee ₦10.75 + this = ₦13.00 total.'
WHERE  config_key    = 'transfer.markup.tier1.fee';

-- ── NIBSS NIP interbank fee tiers (charged by Rubies — NOT Moniewise revenue) ──
-- These match the current NIBSS published schedule.
-- Seeded here so admins can update them if NIBSS revises the schedule.
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('transfer.nip.tier1.max_amount', '5000',
     'NIP tier 1 upper bound (NGN) — transfers at or below this use the tier 1 NIP fee'),
    ('transfer.nip.tier1.fee',        '10.75',
     'NIBSS NIP bank fee (NGN) for transfers ≤ ₦5,000 — deducted by Rubies automatically'),
    ('transfer.nip.tier2.max_amount', '50000',
     'NIP tier 2 upper bound (NGN) — transfers at or below this use the tier 2 NIP fee'),
    ('transfer.nip.tier2.fee',        '26.88',
     'NIBSS NIP bank fee (NGN) for transfers ₦5,001–₦50,000 — deducted by Rubies automatically'),
    ('transfer.nip.tier3.fee',        '53.75',
     'NIBSS NIP bank fee (NGN) for transfers above ₦50,000 — deducted by Rubies automatically')
ON CONFLICT (config_key) DO NOTHING;

-- ── Rubies revenue wallet placeholders ────────────────────────────────────────
-- Empty until an admin calls POST /admin/rubies/register-revenue-wallet
-- (or POST /admin/rubies/setup-revenue-wallet for a brand-new wallet).
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('rubies.revenue.account.number', '',
     'Moniewise Rubies MFB revenue wallet account number. Set via POST /admin/rubies/register-revenue-wallet.'),
    ('rubies.revenue.account.name',   '',
     'Display name for the Moniewise Rubies revenue wallet (shown in transfer narrations).')
ON CONFLICT (config_key) DO NOTHING;
