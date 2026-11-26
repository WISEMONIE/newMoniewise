CREATE INDEX IF NOT EXISTS idx_users_signup_return_eligible
    ON users (created_at, id)
    WHERE is_deleted = false
      AND is_verified = true
      AND coalesce(test_account, false) = false;

CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_seen
    ON auth_sessions (user_id, last_seen_at, created_at);
