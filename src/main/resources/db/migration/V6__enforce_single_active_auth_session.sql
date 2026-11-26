UPDATE auth_sessions latest
SET revoked = TRUE,
    revoked_at = COALESCE(revoked_at, NOW()),
    fcm_token = NULL
WHERE latest.revoked = FALSE
  AND latest.id NOT IN (
      SELECT DISTINCT ON (user_id) id
      FROM auth_sessions
      WHERE revoked = FALSE
      ORDER BY user_id, created_at DESC, id DESC
  );

CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_sessions_one_active_per_user
    ON auth_sessions (user_id)
    WHERE revoked = FALSE;
