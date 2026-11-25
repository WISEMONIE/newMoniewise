-- AML controls from the Rubies BaaS partner guide (November 2026):
-- every credit with its sender, review holds on large inflows, and the
-- CBN first-day limit. No foreign keys to users on purpose: these are
-- compliance records and must outlive an account deletion.

ALTER TABLE wallets ADD COLUMN IF NOT EXISTS opened_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS aml_holds (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT         NOT NULL,
    reason                VARCHAR(20)    NOT NULL,
    status                VARCHAR(20)    NOT NULL,
    outflow_cap           NUMERIC(19, 2) NOT NULL,
    outflow_used          NUMERIC(19, 2) NOT NULL DEFAULT 0,
    trigger_reference     VARCHAR(120),
    trigger_amount        NUMERIC(19, 2),
    inflow_total_24h      NUMERIC(19, 2),
    threshold_used        NUMERIC(19, 2),
    name_check            VARCHAR(20),
    started_at            TIMESTAMP      NOT NULL,
    ends_at               TIMESTAMP,
    customer_contacted_at TIMESTAMP,
    escalated_at          TIMESTAMP,
    released_at           TIMESTAMP,
    released_by           VARCHAR(200),
    release_reason        VARCHAR(1000),
    notes                 TEXT,
    version               BIGINT
);

CREATE INDEX IF NOT EXISTS idx_aml_holds_user_status ON aml_holds (user_id, status);
CREATE INDEX IF NOT EXISTS idx_aml_holds_status_started ON aml_holds (status, started_at);

CREATE TABLE IF NOT EXISTS aml_inflows (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT         NOT NULL,
    reference      VARCHAR(120)   NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    sender_name    VARCHAR(200),
    sender_bank    VARCHAR(120),
    sender_account VARCHAR(20),
    internal       BOOLEAN        NOT NULL DEFAULT FALSE,
    name_check     VARCHAR(20),
    hold_id        BIGINT,
    received_at    TIMESTAMP      NOT NULL,
    CONSTRAINT uk_aml_inflows_user_ref UNIQUE (user_id, reference)
);

CREATE INDEX IF NOT EXISTS idx_aml_inflows_user_received ON aml_inflows (user_id, received_at);
CREATE INDEX IF NOT EXISTS idx_aml_inflows_hold ON aml_inflows (hold_id);
