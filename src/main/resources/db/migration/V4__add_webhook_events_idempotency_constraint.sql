-- Adds the unique constraint on (provider_name, idempotency_key) that
-- prevents double-processing of duplicate webhook deliveries.
--
-- The constraint is added conditionally so this migration is re-runnable
-- in environments that may have already applied it via ddl-auto=update.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM   pg_constraint
        WHERE  conname = 'uq_webhook_events_provider_idempotency'
    ) THEN
        ALTER TABLE webhook_events
            ADD CONSTRAINT uq_webhook_events_provider_idempotency
            UNIQUE (provider_name, idempotency_key);
    END IF;
END;
$$;
