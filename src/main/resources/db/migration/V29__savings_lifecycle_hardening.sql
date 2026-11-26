-- Savings lifecycle hardening:
--   maturity_eve_reminder_sent — the "matures tomorrow" reminder went out (7-day one has its own flag)
--   last_processed_date        — per-goal daily idempotency so the lifecycle job can run every few
--                                hours (self-healing after missed 1AM runs) without double-applying
--                                daily interest
ALTER TABLE savings_goals
    ADD COLUMN IF NOT EXISTS maturity_eve_reminder_sent BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE savings_goals
    ADD COLUMN IF NOT EXISTS last_processed_date DATE;
