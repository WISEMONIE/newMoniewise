CREATE TABLE user_events (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT        NOT NULL REFERENCES users(id),
    event_name      VARCHAR(100)  NOT NULL,
    screen_name     VARCHAR(100),
    metadata        JSONB,
    device_platform VARCHAR(20),
    app_version     VARCHAR(30),
    session_id      VARCHAR(64),
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_events_user_created ON user_events(user_id, created_at DESC);
CREATE INDEX idx_user_events_event_name ON user_events(event_name, created_at DESC);
CREATE INDEX idx_user_events_created_at ON user_events(created_at DESC);
