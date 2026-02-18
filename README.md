# defold-extension-appodeal

Android-only Defold Native Extension for Appodeal Ads with minimal Lua API:

- `init(params, callback)`
- `is_interstitial_available() -> bool`
- `show_interstitial(callback)`
- `is_rewarded_available() -> bool`
- `show_rewarded(callback)`

## Current stack (February 2026)

- Appodeal Android SDK: `4.0.0`
- Android `minSdk 23+`
- Default setup is **not core-only anymore**.
- Included Appodeal-account adapters:
  - `amazon:11.1.1.0`
  - `applovin:13.5.1.0`
  - `bigo_ads:5.6.2.0`
  - `dt_exchange:8.4.1.0`
  - `inmobi:11.1.0.0`
  - `ironsource:9.1.0.0`
  - `mintegral:17.0.31.0`
  - `my_target:5.27.4.0` (VK Ads path)
  - `unity_ads:4.16.4.0`
  - `vungle:7.6.1.0`
  - `yandex:7.18.2.0`

AdMob is optional and **not required** for this default account-driven setup.

## Repository structure

- `appodeal/ext.manifest`
- `appodeal/src/appodeal.cpp`
- `appodeal/src/java/com/defold/appodeal/AppodealBridge.java`
- `appodeal/manifests/android/build.gradle`
- `appodeal/manifests/android/AndroidManifest.xml`
- `appodeal/manifests/android/proguard-rules.pro`
- `appodeal/manifests/android/res/xml/defold_appodeal_network_security_config.xml`
- `example/appodeal_sample.lua`
- `example/provider_adapter.lua`
- `example/main.script`

## Add extension to game.project

Use branch zip (always latest `master`):

```ini
[project]
dependencies = https://github.com/wcysitp/defold-extension-appodeal/archive/refs/heads/master.zip
```

This format does not require tag updates in game project.

## Lua API

### init(params, callback)

`params`:
- `app_key` (required string)
- `testing` (optional boolean, default `false`)
- `log_level` (optional string: `"none" | "verbose" | "debug"`, default `"none"`)

`callback(event)`:
- `event.success` (boolean)
- `event.event` (`initialized` or `init_failed`)
- `event.error` (optional string)

### is_interstitial_available()

Returns `true` if interstitial is loaded.

### show_interstitial(callback)

Callback events:
- `loaded`
- `failed_to_load`
- `shown`
- `clicked`
- `closed`
- `show_failed`
- `expired`

Terminal events:
- `show_failed`, `closed`, `expired`

### is_rewarded_available()

Returns `true` if rewarded is loaded.

### show_rewarded(callback)

Callback events:
- `loaded`
- `failed_to_load`
- `shown`
- `clicked`
- `reward`
- `closed`
- `show_failed`
- `expired`

Extra rewarded fields:
- `event.rewarded` (boolean)
- `event.amount` (number on `reward`)
- `event.currency` (string on `reward`)

## Java diagnostics logs

`AppodealBridge` logs use tag:

- `DefoldAppodeal`

Important points logged:
- `initialize called`
- `initialize success` / `initialize failed`
- `showInterstitial: canShow=true` / `interstitial_not_available`
- `showRewarded: canShow=true` / `rewarded_not_available`
- callbacks for load/show failures and reward finish

## Troubleshooting

### Could not resolve Appodeal dependencies

Make sure these repositories are available in Gradle:
- `https://artifactory.appodeal.com/appodeal`
- `https://artifactory.appodeal.com/appodeal-public`
- `mavenCentral()`
- `google()`

Already configured in `appodeal/manifests/android/build.gradle`.

### R8/ProGuard issues

See `appodeal/manifests/android/proguard-rules.pro`.

### No ad shown after button press

1. Verify game uses latest plugin branch zip.
2. `Project -> Fetch Libraries`.
3. Rebuild APK.
4. Check logs:
   - Lua-side logs
   - `DefoldAppodeal` Java logs via `adb logcat`

## Validation checklist

1. `testing = true`
2. `log_level = "verbose"`
3. Expect init callback: `initialized`
4. For interstitial/rewarded expect either:
   - `loaded -> shown -> closed`
   - or `failed_to_load/show_failed` with reason

## Sources

- Defold extensions manual: https://defold.com/manuals/extensions/
- Defold ext.manifest manual: https://defold.com/manuals/extensions-ext-manifests/
- Appodeal Android docs: https://docs.appodeal.com/android/get-started
- Appodeal interstitial docs: https://docs.appodeal.com/android/ad-types/interstitial
- Appodeal rewarded docs: https://docs.appodeal.com/android/ad-types/rewarded-video
