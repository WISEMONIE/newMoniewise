-- Per-account flag for the seeded app-review account. Lets that one account skip
-- the first-login-per-device email OTP (a store reviewer can't receive it) while
-- its password is still verified normally. Every real account stays FALSE.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS test_account BOOLEAN NOT NULL DEFAULT FALSE;
