-- Tracks the "complete your profile" nudge cadence for users who signed up
-- but never finished KYC/profile (and therefore never got a wallet created).
--
-- onboarding_reminder_count   → how many nudge emails have been sent so far
--                                (0 = none yet; the lifecycle worker uses this
--                                to compute when the NEXT one is due)
-- last_onboarding_reminder_at → timestamp of the most recent nudge, so the
--                                worker never double-sends within the same run
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS onboarding_reminder_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_onboarding_reminder_at TIMESTAMP;
