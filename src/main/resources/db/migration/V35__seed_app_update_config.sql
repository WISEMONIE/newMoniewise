-- V35: Seed runtime app update gate config.
-- The mobile app reads /app/version-check. Admins can adjust these keys via
-- PUT /admin/config/{key} without redeploying the backend.

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('app.update.enabled', 'true', 'Master switch for mobile app update checks.'),
    ('app.update.required_title', 'Update required', 'Title shown when the current app version is below the minimum supported version/build.'),
    ('app.update.required_message', 'Please update Wisemonie to continue. This version includes important fixes for notifications and account safety.', 'Message shown for required app updates.'),
    ('app.update.optional_title', 'Update available', 'Title shown when a newer app version is available but not required.'),
    ('app.update.optional_message', 'A newer Wisemonie version is available with improvements and fixes.', 'Message shown for optional app updates.'),
    ('app.update.android.min_version', '0.0.0', 'Minimum supported Android app versionName. Set alongside min_build when forcing an update.'),
    ('app.update.android.min_build', '0', 'Minimum supported Android versionCode/build number. Build number is compared before semantic version.'),
    ('app.update.android.latest_version', '0.0.0', 'Latest Android app versionName available in the store.'),
    ('app.update.android.latest_build', '0', 'Latest Android versionCode/build number available in the store.'),
    ('app.update.android.store_url', '', 'Google Play Store URL for Wisemonie.'),
    ('app.update.ios.min_version', '0.0.0', 'Minimum supported iOS CFBundleShortVersionString. Set alongside min_build when forcing an update.'),
    ('app.update.ios.min_build', '0', 'Minimum supported iOS CFBundleVersion/build number. Build number is compared before semantic version.'),
    ('app.update.ios.latest_version', '0.0.0', 'Latest iOS app version available in the App Store.'),
    ('app.update.ios.latest_build', '0', 'Latest iOS build number available in the App Store.'),
    ('app.update.ios.store_url', '', 'Apple App Store URL for Wisemonie.')
ON CONFLICT (config_key) DO NOTHING;
