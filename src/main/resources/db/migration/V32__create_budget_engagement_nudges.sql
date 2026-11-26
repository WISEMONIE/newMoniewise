CREATE TABLE IF NOT EXISTS budget_engagement_nudges (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    nudge_type VARCHAR(40) NOT NULL,
    first_sent_at TIMESTAMP NOT NULL,
    last_sent_at TIMESTAMP NOT NULL,
    send_count INTEGER NOT NULL DEFAULT 0,
    last_eligible_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_budget_engagement_nudges_user_type UNIQUE (user_id, nudge_type)
);

CREATE INDEX IF NOT EXISTS idx_budget_engagement_nudges_user
    ON budget_engagement_nudges(user_id);

CREATE INDEX IF NOT EXISTS idx_budget_engagement_nudges_type_last_sent
    ON budget_engagement_nudges(nudge_type, last_sent_at);
