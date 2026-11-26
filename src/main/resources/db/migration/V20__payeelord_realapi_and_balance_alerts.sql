-- V20: Align Payeelord config with the real API + seed the admin low-balance alert job.
--
-- 1. The V13 seed pointed at https://payeelord.com/api, but the real base URL is
--    https://api.payeelord.com/api. Update it (UPDATE, not INSERT — the row exists).
-- 2. Seed the float low-balance admin-alert config used by PayeelordBalanceMonitorJob.

UPDATE system_config
   SET config_value = 'https://api.payeelord.com/api',
       description  = 'Payeelord API base URL (real host: api.payeelord.com)'
 WHERE config_key = 'payeelord.api.base_url';

-- Safety net in case the V13 row was never present for some reason.
INSERT INTO system_config (config_key, config_value, description)
VALUES ('payeelord.api.base_url', 'https://api.payeelord.com/api',
        'Payeelord API base URL (real host: api.payeelord.com)')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('payeelord.balance.alert.enabled', 'true',
     'Master switch for the Payeelord float low-balance admin alert job.'),
    ('payeelord.balance.alert.threshold', '5000',
     'Threshold (NGN) below which a low-balance push alert is sent to all admins.'),
    ('payeelord.balance.alert.cooldown_minutes', '360',
     'Minutes to suppress repeat low-balance alerts after one fires (default 6h).')
ON CONFLICT (config_key) DO NOTHING;
