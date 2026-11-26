-- Events sit on the savings pot. These columns remember the type, which
-- reminders the person asked for, and when they accepted the lock.
ALTER TABLE savings_goals
    ADD COLUMN IF NOT EXISTS category VARCHAR(32);

ALTER TABLE savings_goals
    ADD COLUMN IF NOT EXISTS remind_week BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE savings_goals
    ADD COLUMN IF NOT EXISTS remind_day_before BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE savings_goals
    ADD COLUMN IF NOT EXISTS remind_morning BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE savings_goals
    ADD COLUMN IF NOT EXISTS morning_reminder_sent BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE savings_goals
    ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMP;
