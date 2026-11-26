CREATE TABLE IF NOT EXISTS auth_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    session_id VARCHAR(255) NOT NULL UNIQUE,
    fcm_token TEXT,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP,
    revoked_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_auth_session_user_revoked
    ON auth_sessions (user_id, revoked);

CREATE INDEX IF NOT EXISTS idx_auth_session_session_id
    ON auth_sessions (session_id);
