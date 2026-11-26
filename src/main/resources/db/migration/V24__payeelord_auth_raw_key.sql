-- V24: Default Payeelord auth to the RAW API key (no "Bearer" prefix).
--
-- Live testing returned HTTP 401 for `Authorization: Bearer <key>` on every
-- Payeelord endpoint, while their /data and /check/balance docs show the raw key.
-- Flip the existing config row (seeded 'true' in V22) to 'false'. Operators can
-- still flip it back at runtime via PUT /admin/config/payeelord.auth.use_bearer.

UPDATE system_config
   SET config_value = 'false'
 WHERE config_key = 'payeelord.auth.use_bearer';

INSERT INTO system_config (config_key, config_value, description)
VALUES ('payeelord.auth.use_bearer', 'false',
        'Send the Payeelord API key as a raw Authorization header (false) or as a Bearer token (true).')
ON CONFLICT (config_key) DO NOTHING;
