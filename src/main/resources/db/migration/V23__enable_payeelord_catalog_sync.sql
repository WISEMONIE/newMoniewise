-- V23: Enable the nightly Payeelord data-plan catalog sync.
--
-- The scraper is now implemented against the real /datatypes + /data-plan
-- endpoints, so turn on the nightly refresh. (This was briefly added to V20 by
-- mistake after V20 had already been applied — moved here as its own migration
-- to avoid a Flyway checksum mismatch. Admins can also trigger a sync on demand
-- via POST /admin/payeelord/sync-catalog.)

UPDATE system_config
   SET config_value = 'true'
 WHERE config_key = 'payeelord.catalog.sync.enabled';
