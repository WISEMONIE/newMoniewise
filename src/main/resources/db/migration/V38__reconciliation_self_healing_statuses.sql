ALTER TABLE reconciliation_items
    ALTER COLUMN status TYPE VARCHAR(40);

CREATE INDEX IF NOT EXISTS idx_reconciliation_items_run_status
    ON reconciliation_items (reconciliation_run_id, status);

CREATE INDEX IF NOT EXISTS idx_transaction_logs_provider_name_reference
    ON transaction_logs (provider_name, provider_reference)
    WHERE provider_reference IS NOT NULL;
