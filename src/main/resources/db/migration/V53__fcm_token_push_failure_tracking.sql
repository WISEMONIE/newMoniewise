-- Track consecutive push failures so dead-token cleanup uses a grace period
-- instead of deactivating on the first Firebase rejection.
ALTER TABLE user_device_tokens
    ADD COLUMN push_failure_count   INTEGER       NOT NULL DEFAULT 0,
    ADD COLUMN push_last_failure_at TIMESTAMP,
    ADD COLUMN push_last_failure_code VARCHAR(40);

-- Re-activate tokens that were deactivated by a single INVALID_ARGUMENT error
-- (a payload bug, not a dead token). They were killed incorrectly.
UPDATE user_device_tokens
SET active = TRUE,
    deactivated_at = NULL,
    deactivation_reason = NULL,
    push_failure_count = 0
WHERE active = FALSE
  AND deactivation_reason = 'FIREBASE_DEAD_TOKEN'
  AND deactivated_at > NOW() - INTERVAL '7 days';
