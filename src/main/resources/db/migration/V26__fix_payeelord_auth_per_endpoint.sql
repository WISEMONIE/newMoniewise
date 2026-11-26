-- V26: Fix Payeelord auth — per-endpoint Bearer/raw is now hardcoded in PayeelordGateway,
-- so the system_config toggle no longer controls anything.
--
-- History:
--   V22 seeded payeelord.auth.use_bearer = 'true'  (Bearer for all)
--   V24 flipped it to 'false' (raw key for all) after an inconclusive 401 test
--   Both were wrong: airtime needs Bearer, data/balance need raw key.
--
-- The gateway now ignores this config row and applies per-endpoint defaults directly:
--   POST /buy/airtime  → Authorization: Bearer <key>
--   POST /data         → Authorization: <key>  (raw)
--   GET  /check/balance → Authorization: <key> (raw)
--
-- This UPDATE keeps the row visible in the admin UI as an audit trail but marks
-- it retired so no operator is confused about whether flipping it does anything.

UPDATE system_config
   SET config_value  = 'retired',
       description   = '[RETIRED — no longer read by the gateway] Per-endpoint auth is now '
                       || 'hardcoded in PayeelordGateway: airtime uses Bearer, data/balance use raw key.'
 WHERE config_key = 'payeelord.auth.use_bearer';
