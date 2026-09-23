-- ============================================================
-- Fee Reserve: pre-funded pool to cover transfer fees
-- ============================================================

CREATE TABLE fee_reserves (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL UNIQUE REFERENCES users(id),
    balance         NUMERIC(19,2)   NOT NULL DEFAULT 0,
    initial_amount  NUMERIC(19,2)   NOT NULL DEFAULT 0,
    estimated_total_fees NUMERIC(19,2) NOT NULL DEFAULT 0,
    shortfall       NUMERIC(19,2)   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP       NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE INDEX idx_fee_reserves_user_id ON fee_reserves(user_id);

CREATE TABLE fee_reserve_transactions (
    id              BIGSERIAL       PRIMARY KEY,
    fee_reserve_id  BIGINT          NOT NULL REFERENCES fee_reserves(id),
    type            VARCHAR(20)     NOT NULL CHECK (type IN ('FUND','DEBIT','TOP_UP','RELEASE')),
    amount          NUMERIC(19,2)   NOT NULL,
    balance_after   NUMERIC(19,2)   NOT NULL,
    reference       VARCHAR(255),
    description     VARCHAR(500),
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE INDEX idx_fee_reserve_txn_reserve ON fee_reserve_transactions(fee_reserve_id);

CREATE TABLE fee_reserve_estimates (
    id                      BIGSERIAL       PRIMARY KEY,
    fee_reserve_id          BIGINT          NOT NULL REFERENCES fee_reserves(id),
    budget_id               BIGINT          NOT NULL REFERENCES budgets(id),
    envelope_id             BIGINT          NOT NULL REFERENCES envelopes(id),
    envelope_amount         NUMERIC(19,2)   NOT NULL,
    estimated_nip_fee       NUMERIC(19,2)   NOT NULL DEFAULT 0,
    estimated_stamp_duty    NUMERIC(19,2)   NOT NULL DEFAULT 0,
    estimated_markup        NUMERIC(19,2)   NOT NULL DEFAULT 0,
    estimated_total         NUMERIC(19,2)   NOT NULL DEFAULT 0,
    active                  BOOLEAN         NOT NULL DEFAULT true,
    created_at              TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE INDEX idx_fee_reserve_est_reserve ON fee_reserve_estimates(fee_reserve_id);
CREATE INDEX idx_fee_reserve_est_budget  ON fee_reserve_estimates(budget_id);
