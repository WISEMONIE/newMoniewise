-- Auto-transfer recipient for envelope disbursements.
-- One destination per envelope; toggled via envelopes.is_automated.
CREATE TABLE envelope_auto_transfers (
    id              BIGSERIAL     PRIMARY KEY,
    envelope_id     BIGINT        NOT NULL UNIQUE REFERENCES envelopes(id) ON DELETE CASCADE,
    user_id         BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bank_code       VARCHAR(10)   NOT NULL,
    bank_name       VARCHAR(100)  NOT NULL,
    account_number  VARCHAR(10)   NOT NULL,
    account_name    VARCHAR(150)  NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_envelope_auto_transfers_envelope_id ON envelope_auto_transfers(envelope_id);
CREATE INDEX idx_envelope_auto_transfers_user_id ON envelope_auto_transfers(user_id);

ALTER TABLE envelopes ADD COLUMN is_automated BOOLEAN NOT NULL DEFAULT FALSE;
