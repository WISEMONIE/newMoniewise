-- Link each queued push to the admin broadcast that created it, so the
-- admin history can show what reached a phone, what is still waiting,
-- and what failed.
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS broadcast_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_outbox_events_broadcast
    ON outbox_events (broadcast_id, status)
    WHERE broadcast_id IS NOT NULL;
