CREATE TABLE IF NOT EXISTS engagement_nudge_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    campaign VARCHAR(40) NOT NULL,
    segment VARCHAR(40) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    occasion_key VARCHAR(100) NOT NULL,
    copy_key VARCHAR(140) NOT NULL,
    sent_date DATE NOT NULL,
    sent_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_engagement_nudges_user_campaign_occasion_channel_date
        UNIQUE (user_id, campaign, occasion_key, channel, sent_date)
);

CREATE INDEX IF NOT EXISTS idx_engagement_nudges_user_sent_date
    ON engagement_nudge_notifications(user_id, sent_date);

CREATE INDEX IF NOT EXISTS idx_engagement_nudges_user_sent_at
    ON engagement_nudge_notifications(user_id, sent_at);

CREATE INDEX IF NOT EXISTS idx_engagement_nudges_campaign_sent_at
    ON engagement_nudge_notifications(campaign, sent_at);

CREATE TABLE IF NOT EXISTS engagement_special_occasions (
    id BIGSERIAL PRIMARY KEY,
    occasion_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(140) NOT NULL,
    occasion_date DATE NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    priority INTEGER NOT NULL DEFAULT 50,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_engagement_special_occasions_key_date
        UNIQUE (occasion_key, occasion_date)
);

CREATE INDEX IF NOT EXISTS idx_engagement_special_occasions_date_active
    ON engagement_special_occasions(occasion_date, active);

CREATE INDEX IF NOT EXISTS idx_users_profile_dob_month_day
    ON users ((substring(profile_data ->> 'dateOfBirth' from 6 for 5)))
    WHERE profile_data ? 'dateOfBirth';

-- Eid/Salah dates depend on official moon-sighting confirmation.
-- Add active rows here per year, e.g.:
-- INSERT INTO engagement_special_occasions (occasion_key, display_name, occasion_date, priority)
-- VALUES ('EID_EL_FITR', 'Eid el-Fitr', '2027-03-10', 90)
-- ON CONFLICT (occasion_key, occasion_date) DO NOTHING;
