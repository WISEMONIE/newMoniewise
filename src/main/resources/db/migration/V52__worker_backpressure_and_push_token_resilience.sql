-- Outbox retry backoff: failed events should wait before becoming claimable again.
ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_outbox_events_claim_due
    ON outbox_events (status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'PROCESSING');

UPDATE outbox_events
SET status = 'STALE',
    processed_at = COALESCE(processed_at, CURRENT_TIMESTAMP),
    next_attempt_at = NULL,
    locked_at = NULL,
    locked_by = NULL
WHERE status = 'FAILED'
  AND event_type = 'ADMIN_RECONCILIATION_ALERT'
  AND last_error = 'moniewise.reconciliation.admin-alert-email is not configured';

-- VAS stale purchases that were already escalated should leave the PENDING queue.
ALTER TABLE payeelord_vas_transactions
    ALTER COLUMN status TYPE VARCHAR(30);

UPDATE payeelord_vas_transactions
SET status = 'MANUAL_REVIEW',
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'PENDING'
  AND failure_reason LIKE '%[RECOVERY_ESCALATED]%';

-- FCM tokens belong to devices, not login sessions. Bring back tokens that were
-- disabled only because a session was revoked/cleared. Firebase-dead tokens stay off.
ALTER TABLE users
    ALTER COLUMN fcm_token TYPE VARCHAR(512);

UPDATE user_device_tokens
SET active = TRUE,
    deactivated_at = NULL,
    deactivation_reason = NULL,
    last_seen_at = COALESCE(last_seen_at, created_at)
WHERE active = FALSE
  AND deactivation_reason IN ('SESSION_REVOKED', 'SESSION_TOKEN_REMOVED', 'CLIENT_REMOVED_TOKEN')
  AND fcm_token IS NOT NULL
  AND btrim(fcm_token) <> '';
