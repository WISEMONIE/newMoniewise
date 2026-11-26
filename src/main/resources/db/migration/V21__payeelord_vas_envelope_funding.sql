-- V21: Payeelord VAS purchases are funded from a budget envelope (not the main
-- wallet ledger). Record which envelope each purchase debited.

ALTER TABLE payeelord_vas_transactions
    ADD COLUMN IF NOT EXISTS envelope_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_payeelord_txn_envelope
    ON payeelord_vas_transactions (envelope_id);
