-- Idempotency state for the notification outbox so a retry never duplicates work:
--   inbox_saved      — the in-app inbox row was already written for this event
--   delivered_tokens — device tokens already pushed (retry skips them), "||"-joined
ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS inbox_saved BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS delivered_tokens TEXT;
