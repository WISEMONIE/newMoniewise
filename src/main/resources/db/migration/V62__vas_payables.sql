-- Cost of airtime/data that has landed in the Rubies revenue account and
-- has not been sent back to PayeeLord. The revenue wallet balance stays
-- "ours" (fees and markup). This table does not change that balance.

CREATE TABLE IF NOT EXISTS vas_payables (
    id                    BIGSERIAL PRIMARY KEY,
    purchase_reference    VARCHAR(80)    NOT NULL,
    collection_reference  VARCHAR(120)   NOT NULL,
    user_id               BIGINT         NOT NULL,
    selling_amount        NUMERIC(12, 2) NOT NULL,
    cost_amount           NUMERIC(12, 2) NOT NULL,
    margin_amount         NUMERIC(12, 2) NOT NULL DEFAULT 0,
    margin_credited       BOOLEAN        NOT NULL DEFAULT FALSE,
    swept                 BOOLEAN        NOT NULL DEFAULT FALSE,
    swept_at              TIMESTAMPTZ,
    sweep_reference       VARCHAR(120),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_vas_payables_purchase_reference UNIQUE (purchase_reference)
);

CREATE INDEX IF NOT EXISTS idx_vas_payables_unswept
    ON vas_payables (swept)
    WHERE swept = FALSE;
