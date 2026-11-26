-- Every budget an admin cancels from the website: who, which budget, why,
-- and how much went back to the customer's wallet. One row per budget, so
-- a repeated request cannot cancel or refund twice.
CREATE TABLE IF NOT EXISTS admin_budget_cancellations (
    id              BIGSERIAL PRIMARY KEY,
    budget_id       BIGINT        NOT NULL,
    user_id         BIGINT        NOT NULL,
    budget_name     VARCHAR(255),
    admin_email     VARCHAR(200)  NOT NULL,
    reason          VARCHAR(500)  NOT NULL,
    refunded_amount NUMERIC(19,2) NOT NULL,
    created_at      TIMESTAMP     NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_admin_budget_cancellations_budget
    ON admin_budget_cancellations (budget_id);
CREATE INDEX IF NOT EXISTS idx_admin_budget_cancellations_user
    ON admin_budget_cancellations (user_id, created_at);
