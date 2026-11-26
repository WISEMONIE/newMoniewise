# Wisemonie Deep Links

Wisemonie uses industry-standard HTTPS links:

```text
https://wisemonie.app/open/<app-route>
```

Examples:

```text
https://wisemonie.app/open/budgets
https://wisemonie.app/open/budgets/123
https://wisemonie.app/open/envelopes/456
https://wisemonie.app/open/onboarding/continue?email=user@example.com
```

When the app is installed, Android App Links / iOS Universal Links should open
the matching app screen. When the app is not installed, the backend serves a
fallback page with store links.

## Render Environment Variables

Set these on the backend Render service:

```env
MONIEWISE_DEEPLINK_BASE_URL=https://wisemonie.app
MONIEWISE_DEEPLINK_OPEN_PATH_PREFIX=/open
MONIEWISE_DEEPLINK_ANDROID_PACKAGE_NAME=com.wisemonie
MONIEWISE_DEEPLINK_ANDROID_SHA256_CERT_FINGERPRINTS=<PLAY_APP_SIGNING_SHA256>
MONIEWISE_DEEPLINK_ANDROID_PLAY_STORE_URL=https://play.google.com/store/apps/details?id=com.wisemonie
MONIEWISE_DEEPLINK_IOS_TEAM_ID=<APPLE_TEAM_ID>
MONIEWISE_DEEPLINK_IOS_BUNDLE_ID=<IOS_BUNDLE_ID>
MONIEWISE_DEEPLINK_IOS_APP_STORE_URL=<APPLE_APP_STORE_URL>
MONIEWISE_DEEPLINK_WEB_FALLBACK_URL=https://wisemonie.app
```

If the iOS app is not listed yet, leave the iOS values empty. The Apple
association file will stay valid but will not claim any iOS app until those
values are configured.

## Required Public URLs

These must be reachable over HTTPS with no login:

```text
https://wisemonie.app/.well-known/assetlinks.json
https://wisemonie.app/.well-known/apple-app-site-association
https://wisemonie.app/open/budgets
```

Android verification requires the SHA-256 fingerprint from Play Console:
Release > Setup > App integrity > App signing key certificate.

## Android App Config

Add an intent filter to the Flutter Android app manifest:

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />

    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />

    <data
        android:scheme="https"
        android:host="wisemonie.app"
        android:pathPrefix="/open" />
</intent-filter>
```

The app should parse the incoming URL path after `/open` and route internally:

```text
/open/budgets        -> /budgets
/open/budgets/123    -> /budgets/123
/open/envelopes/456  -> /envelopes/456
```

## iOS App Config

In Xcode, enable Associated Domains and add:

```text
applinks:wisemonie.app
```

The app should handle Universal Links the same way as Android: strip `/open`
and route the remaining path inside the app.

## Push Notifications

FCM payloads now include:

```text
redirectUrl=/budgets/123
route=/budgets/123
deepLinkUrl=https://wisemonie.app/open/budgets/123
```

The app should prefer `route` for in-app navigation. Use `deepLinkUrl` when a
full HTTPS link is needed for sharing, emails, analytics, or OS-level linking.
