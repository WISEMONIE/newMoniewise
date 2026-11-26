-- V10: Subscription plans + user subscriptions for premium tier

-- ── subscription_plans ────────────────────────────────────────────────────────
-- Defines what plans exist (Free, Premium, etc.) and what features they unlock.
-- Features are a comma-separated string matching PremiumFeature enum values.

CREATE TABLE IF NOT EXISTS subscription_plans (
    id             BIGSERIAL    PRIMARY KEY,
    plan_name      VARCHAR(100) NOT NULL UNIQUE,
    price          NUMERIC(19,4) NOT NULL DEFAULT 0,
    billing_cycle  VARCHAR(20)  NOT NULL DEFAULT 'MONTHLY',  -- MONTHLY | QUARTERLY | ANNUALLY
    features       TEXT         NOT NULL DEFAULT '',            -- e.g. 'UNLIMITED_TRANSFERS,AI_INSIGHTS'
    description    VARCHAR(500),
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_subscription_plans_active ON subscription_plans (active);

-- ── user_subscriptions ────────────────────────────────────────────────────────
-- Tracks which plan each user is on and when it expires.
-- Multiple rows per user are allowed (history); the active one is found via
-- findTopByUserIdAndStatusOrderByEndDateDesc.

CREATE TABLE IF NOT EXISTS user_subscriptions (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_id             BIGINT       NOT NULL REFERENCES subscription_plans(id),
    start_date          DATE         NOT NULL,
    end_date            DATE         NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | EXPIRED | CANCELLED | PENDING
    payment_reference   VARCHAR(255),
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_subscriptions_user_id   ON user_subscriptions (user_id);
CREATE INDEX IF NOT EXISTS idx_user_subscriptions_status    ON user_subscriptions (status);
CREATE INDEX IF NOT EXISTS idx_user_subscriptions_end_date  ON user_subscriptions (end_date);
-- Composite index for the most common query (find active sub for a user)
CREATE INDEX IF NOT EXISTS idx_user_subscriptions_user_status_end
    ON user_subscriptions (user_id, status, end_date DESC);

-- ── Trigger: auto-update updated_at ──────────────────────────────────────────

CREATE OR REPLACE FUNCTION update_subscription_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_subscription_plans_updated_at ON subscription_plans;
CREATE TRIGGER trg_subscription_plans_updated_at
    BEFORE UPDATE ON subscription_plans
    FOR EACH ROW EXECUTE FUNCTION update_subscription_updated_at();

DROP TRIGGER IF EXISTS trg_user_subscriptions_updated_at ON user_subscriptions;
CREATE TRIGGER trg_user_subscriptions_updated_at
    BEFORE UPDATE ON user_subscriptions
    FOR EACH ROW EXECUTE FUNCTION update_subscription_updated_at();

-- ── Seed plans ────────────────────────────────────────────────────────────────

INSERT INTO subscription_plans (plan_name, price, billing_cycle, features, description, active) VALUES
    ('FREE',    0,    'MONTHLY', '',                                       'Free tier — standard markup on transfers', TRUE),
    ('PREMIUM', 2000, 'MONTHLY', 'UNLIMITED_TRANSFERS,AI_INSIGHTS',        'Premium — zero markup on transfers + AI insights', TRUE)
ON CONFLICT (plan_name) DO NOTHING;
