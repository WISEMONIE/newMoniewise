CREATE TABLE IF NOT EXISTS user_device_tokens (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    fcm_token           VARCHAR(512) NOT NULL,
    device_platform     VARCHAR(20),
    device_id           VARCHAR(128),
    session_id          VARCHAR(255),
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMP    NOT NULL DEFAULT now(),
    deactivated_at      TIMESTAMP,
    deactivation_reason VARCHAR(80),
    CONSTRAINT uq_user_device_tokens_fcm_token UNIQUE (fcm_token)
);

CREATE INDEX IF NOT EXISTS idx_user_device_tokens_user_active
    ON user_device_tokens (user_id, active, last_seen_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_device_tokens_session
    ON user_device_tokens (session_id);

INSERT INTO user_device_tokens (
    user_id,
    fcm_token,
    active,
    created_at,
    last_seen_at,
    deactivation_reason
)
SELECT
    u.id,
    btrim(u.fcm_token),
    TRUE,
    now(),
    now(),
    'LEGACY_USER_TOKEN'
FROM users u
WHERE u.fcm_token IS NOT NULL
  AND btrim(u.fcm_token) <> ''
ON CONFLICT (fcm_token) DO UPDATE
SET user_id = EXCLUDED.user_id,
    active = TRUE,
    last_seen_at = now(),
    deactivated_at = NULL,
    deactivation_reason = NULL;
