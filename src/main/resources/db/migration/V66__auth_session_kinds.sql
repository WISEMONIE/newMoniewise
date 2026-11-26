-- One active phone-app session and one active website-admin session per
-- user. V6 allowed one active session in total, so signing in to the
-- website admin either failed or signed the admin out of the phone.

ALTER TABLE auth_sessions ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'APP';

DROP INDEX IF EXISTS ux_auth_sessions_one_active_per_user;

CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_sessions_one_active_per_user
    ON auth_sessions (user_id)
    WHERE revoked = FALSE AND kind = 'APP';

CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_sessions_one_active_site_admin_per_user
    ON auth_sessions (user_id)
    WHERE revoked = FALSE AND kind = 'SITE_ADMIN';
