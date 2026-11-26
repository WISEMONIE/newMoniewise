-- V22: Make the Payeelord Authorization scheme configurable.
-- Payeelord's docs show "Bearer <key>" on most endpoints and a raw "<key>" on a
-- couple (/data, /check/balance). Default to Bearer; flip to false if any
-- endpoint starts returning 401 without a redeploy.

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('payeelord.auth.use_bearer', 'true',
     'Send Payeelord API key as "Authorization: Bearer <key>" (true) or raw "Authorization: <key>" (false).')
ON CONFLICT (config_key) DO NOTHING;
