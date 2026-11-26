CREATE TABLE IF NOT EXISTS salary_nudge_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    nudge_window VARCHAR(30) NOT NULL,
    period_year INTEGER NOT NULL,
    period_month INTEGER NOT NULL CHECK (period_month BETWEEN 1 AND 12),
    variation_index INTEGER NOT NULL CHECK (variation_index >= 0),
    sent_date DATE NOT NULL,
    sent_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_salary_nudges_user_window_period_variation
        UNIQUE (user_id, nudge_window, period_year, period_month, variation_index),
    CONSTRAINT uk_salary_nudges_user_window_period_date
        UNIQUE (user_id, nudge_window, period_year, period_month, sent_date)
);

CREATE INDEX IF NOT EXISTS idx_salary_nudges_user_window_period
    ON salary_nudge_notifications(user_id, nudge_window, period_year, period_month);

CREATE INDEX IF NOT EXISTS idx_salary_nudges_window_sent_at
    ON salary_nudge_notifications(nudge_window, sent_at);
