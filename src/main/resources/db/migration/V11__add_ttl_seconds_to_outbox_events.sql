-- Add per-event TTL support to the notification outbox.
-- This lets the worker skip stale transient events (e.g. PRE_DISBURSEMENT delivered 2 h late)
-- instead of pushing an out-of-context notification to the user's phone.
ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS ttl_seconds BIGINT;
