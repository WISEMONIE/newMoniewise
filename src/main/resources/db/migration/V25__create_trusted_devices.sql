-- V25: Trusted devices for first-login-per-device 2FA.
--
-- A row per (user, device) is created the first time a user completes the login
-- OTP challenge on that device (see UserController#verifyOtp). AuthController#login
-- skips the OTP step only when a matching row exists; a new device or a reinstall
-- (fresh client-side device id) therefore re-triggers the password + email-OTP flow.

CREATE TABLE IF NOT EXISTS trusted_devices (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    device_id    VARCHAR(128) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    last_used_at TIMESTAMP,
    CONSTRAINT uq_trusted_device_user_device UNIQUE (user_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_trusted_devices_user ON trusted_devices (user_id);
