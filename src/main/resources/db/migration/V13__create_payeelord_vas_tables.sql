-- V13: Payeelord VAS (Airtime + Data) integration
--
-- Two tables:
--   payeelord_data_plans      → the data-bundle catalog (cost_price is owned by the
--                               periodic scraper job; markup_amount is owned by admins —
--                               never let the scraper touch markup_amount)
--   payeelord_vas_transactions → ledger/audit record for every airtime + data purchase,
--                               synchronous result + later webhook confirmation both land here

-- ── Data plan catalog ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payeelord_data_plans (
    id              BIGSERIAL     PRIMARY KEY,
    network_id      VARCHAR(10)   NOT NULL,           -- Payeelord's networkId, e.g. "1" = MTN
    data_id         VARCHAR(20)   NOT NULL UNIQUE,    -- Payeelord's dataId, e.g. "101"
    network_name    VARCHAR(20)   NOT NULL,           -- MTN | GLO | AIRTEL | 9MOBILE
    plan_type       VARCHAR(40),                      -- SME, GIFTING, DATASHARE, COOPERATE GIFTING, ...
    plan_name       VARCHAR(120)  NOT NULL,           -- e.g. "1GB SME"
    size_label      VARCHAR(20),                      -- e.g. "1GB", "500MB"
    validity_label  VARCHAR(20),                      -- e.g. "30 days"
    cost_price      NUMERIC(12,2) NOT NULL,           -- Payeelord's price — OWNED BY THE SCRAPER
    markup_amount   NUMERIC(12,2) NOT NULL DEFAULT 0, -- your margin on top — OWNED BY ADMINS, scraper never writes this
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    last_synced_at  TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payeelord_plans_network   ON payeelord_data_plans (network_id);
CREATE INDEX IF NOT EXISTS idx_payeelord_plans_active    ON payeelord_data_plans (is_active);

-- ── Trigger: auto-update updated_at on every row change ──────────────────────
CREATE OR REPLACE FUNCTION update_payeelord_data_plans_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_payeelord_data_plans_updated_at ON payeelord_data_plans;
CREATE TRIGGER trg_payeelord_data_plans_updated_at
    BEFORE UPDATE ON payeelord_data_plans
    FOR EACH ROW EXECUTE FUNCTION update_payeelord_data_plans_updated_at();

-- ── VAS transaction ledger (airtime + data) ──────────────────────────────────
CREATE TABLE IF NOT EXISTS payeelord_vas_transactions (
    id                       BIGSERIAL     PRIMARY KEY,
    user_id                  BIGINT        NOT NULL,
    wallet_id                BIGINT        NOT NULL,
    type                     VARCHAR(20)   NOT NULL,   -- AIRTIME | DATA
    status                   VARCHAR(20)   NOT NULL,   -- PENDING | SUCCESSFUL | FAILED | REVERSED

    reference                VARCHAR(60)   NOT NULL UNIQUE,  -- our internal client reference
    payeelord_transaction_id VARCHAR(80),                    -- Payeelord's transaction_id (sync response / webhook)

    network                  VARCHAR(20)   NOT NULL,   -- MTN | GLO | AIRTEL | 9MOBILE
    mobile_number            VARCHAR(20)   NOT NULL,

    data_plan_id             BIGINT REFERENCES payeelord_data_plans(id),  -- NULL for airtime

    face_amount              NUMERIC(12,2) NOT NULL,   -- airtime/data value actually delivered to the recipient
    cost_amount              NUMERIC(12,2) NOT NULL,   -- what Payeelord charged your float
    selling_amount           NUMERIC(12,2) NOT NULL,   -- what the user's wallet was debited
    margin_amount            NUMERIC(12,2) NOT NULL DEFAULT 0,  -- selling_amount - cost_amount

    balance_before           NUMERIC(12,2),            -- Payeelord float balance before (from sync response, audit only)
    balance_after            NUMERIC(12,2),            -- Payeelord float balance after  (from sync response, audit only)

    failure_reason           TEXT,
    raw_response             TEXT,                     -- full JSON response from Payeelord, for support/debugging

    webhook_confirmed_at     TIMESTAMP,                -- set when the async webhook lands (audit trail only)

    created_at               TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payeelord_txn_user_created ON payeelord_vas_transactions (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_payeelord_txn_status       ON payeelord_vas_transactions (status);
CREATE INDEX IF NOT EXISTS idx_payeelord_txn_reference    ON payeelord_vas_transactions (reference);
CREATE INDEX IF NOT EXISTS idx_payeelord_txn_provider_id  ON payeelord_vas_transactions (payeelord_transaction_id);

DROP TRIGGER IF EXISTS trg_payeelord_vas_transactions_updated_at ON payeelord_vas_transactions;
CREATE TRIGGER trg_payeelord_vas_transactions_updated_at
    BEFORE UPDATE ON payeelord_vas_transactions
    FOR EACH ROW EXECUTE FUNCTION update_payeelord_data_plans_updated_at();

-- ── Seed system_config defaults ───────────────────────────────────────────────
-- Airtime has no catalog — its cost is computed live as `amount × discount_rate`.
-- Payeelord confirmed: they charge your float 97.5% of face value (a 2.5% wholesale
-- discount), so the default rate below reflects that. Adjust here if they ever revise it.
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('payeelord.airtime.discount_rate', '0.975',
     'Fraction of face value Payeelord charges your float for airtime (0.975 = pay 97.5%, i.e. a 2.5% wholesale discount). Drives cost_amount = amount × this value.'),
    ('payeelord.airtime.markup_amount', '0',
     'Extra flat markup (NGN) added on top of face value for airtime. 0 = sell at exact face value (Option A — the wholesale spread is your entire margin).'),
    ('payeelord.api.base_url', 'https://payeelord.com/api',
     'Payeelord API base URL'),
    ('payeelord.catalog.sync.enabled', 'false',
     'Enable the periodic data-plan catalog sync job (set true once the scrape target is finalized)')
ON CONFLICT (config_key) DO NOTHING;
