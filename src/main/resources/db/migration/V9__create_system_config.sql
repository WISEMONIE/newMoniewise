-- V9: Create system_config table for runtime DB-driven configuration
-- All fees, feature flags, PSP routing, and plan prices live here.
-- Redis caches each key for 5 minutes; evicted on every update.

CREATE TABLE IF NOT EXISTS system_config (
    id           BIGSERIAL    PRIMARY KEY,
    config_key   VARCHAR(255) NOT NULL UNIQUE,
    config_value TEXT,
    description  VARCHAR(500),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_system_config_key ON system_config (config_key);

-- ── Trigger: auto-update updated_at on every row change ──────────────────────
CREATE OR REPLACE FUNCTION update_system_config_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_system_config_updated_at ON system_config;
CREATE TRIGGER trg_system_config_updated_at
    BEFORE UPDATE ON system_config
    FOR EACH ROW EXECUTE FUNCTION update_system_config_updated_at();

-- ── Seed defaults ─────────────────────────────────────────────────────────────

-- PSP routing
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('psp.active',   'SECUREWAVE', 'Active PSP: PROVIDUS | SECUREWAVE | RUBIES')
ON CONFLICT (config_key) DO NOTHING;

-- Rubies stage
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('rubies.stage', 'dev', 'Rubies API stage path param: dev | prod')
ON CONFLICT (config_key) DO NOTHING;

-- Transfer markup tiers (NGN)
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('transfer.markup.tier1.max_amount', '5000',  'Tier 1 upper bound (NGN) — transfers ≤ this use tier1 fee'),
    ('transfer.markup.tier1.fee',        '50',    'Markup fee (NGN) for tier 1 transfers'),
    ('transfer.markup.tier2.max_amount', '50000', 'Tier 2 upper bound (NGN) — transfers ≤ this use tier2 fee'),
    ('transfer.markup.tier2.fee',        '75',    'Markup fee (NGN) for tier 2 transfers'),
    ('transfer.markup.tier3.fee',        '120',   'Markup fee (NGN) for tier 3 transfers (above tier2 max)')
ON CONFLICT (config_key) DO NOTHING;

-- Budget creation fee — set to 0 (fee removed per product decision)
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('budget.creation.fee', '0', 'Budget creation fee per 30-day interval in NGN. 0 = disabled.')
ON CONFLICT (config_key) DO NOTHING;

-- Premium subscription
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('premium.monthly.price', '2000',                               'Monthly premium subscription price (NGN)'),
    ('premium.features',      'UNLIMITED_TRANSFERS,AI_INSIGHTS',    'Comma-separated PremiumFeature values for premium plan')
ON CONFLICT (config_key) DO NOTHING;
