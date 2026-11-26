ALTER TABLE auth_sessions
    ADD COLUMN IF NOT EXISTS device_platform VARCHAR(20);
