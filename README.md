# defold-extension-appodeal

Android-only Defold Native Extension для Appodeal Ads с минимальным Lua API:

- `init(params, callback)`
- `is_interstitial_available() -> bool`
- `show_interstitial(callback)`
- `is_rewarded_available() -> bool`
- `show_rewarded(callback)`

Репозиторий создан как отдельный extension-проект, без привязки к конкретной игре.

## Текущий стек (на 15 февраля 2026)

- Appodeal Android SDK: `4.0.0`
- Режим extension по умолчанию: `core-only` (без `admob` и других adapters)
- Android minSdk: `23+` (требование Appodeal 4.0.0)

Версии зафиксированы в `appodeal/ext.manifest` через env-переменные для воспроизводимых билдов.

## Настройка адаптеров

Сейчас extension включает только `com.appodeal.ads.sdk:core`.
Это стартовый вариант "чисто Appodeal" без обязательной настройки AdMob.
Если позже понадобятся конкретные mediated сети, добавь их adapters в:

- `appodeal/ext.manifest` (env-версии)
- `appodeal/manifests/android/build.gradle` (`implementation "com.appodeal.ads.sdk.adapters:...`)

Координаты/версии adapters нужно брать из актуальной Appodeal Android документации или их Maven/Artifactory.

## Структура

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

## Подключение в игру (game.project)

1. Добавь dependency на этот репозиторий:

```ini
[project]
dependencies = https://github.com/<your-org>/defold-extension-appodeal/archive/refs/heads/main.zip
```

2. Вставь свой Appodeal App Key в код вызова `appodeal.init`.

3. Если позже добавишь AdMob adapter вручную, добавь `APPLICATION_ID` в Android manifest игры (или в кастомный app manifest):

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
```

4. Собери Android bundle из Defold Editor/Bob.

## Lua API

### init(params, callback)

`params`:
- `app_key` (string, required)
- `testing` (boolean, optional, default `false`)
- `log_level` (string, optional: `"none" | "verbose" | "debug"`, default `"none"`)

`callback(event)`:
- `event.success` (boolean)
- `event.event` (`"initialized"` или `"init_failed"`)
- `event.error` (string, optional)

### is_interstitial_available()

Возвращает `true`, если interstitial загружен и доступен.

### show_interstitial(callback)

`callback(event)` вызывается с полями:
- `event.success` (boolean)
- `event.ad_type == "interstitial"`
- `event.event`:
  - `"loaded"`
  - `"failed_to_load"`
  - `"shown"`
  - `"clicked"`
  - `"closed"`
  - `"show_failed"`
  - `"expired"`
- `event.error` (string, optional)

Терминальные события для `show_interstitial`: `show_failed`, `closed`, `expired`.

### is_rewarded_available()

Возвращает `true`, если rewarded загружен и доступен.

### show_rewarded(callback)

`callback(event)` вызывается с полями:
- `event.success` (boolean)
- `event.ad_type == "rewarded"`
- `event.event`:
  - `"loaded"`
  - `"failed_to_load"`
  - `"shown"`
  - `"clicked"`
  - `"reward"`
  - `"closed"`
  - `"show_failed"`
  - `"expired"`
- `event.error` (string, optional)
- `event.rewarded` (boolean, для rewarded событий)
- `event.amount` (number, только для `reward`)
- `event.currency` (string, только для `reward`)

Терминальные события для `show_rewarded`: `show_failed`, `closed`, `expired`.

## Пример использования

См. `example/appodeal_sample.lua`.

Минимально:

```lua
local appodeal = require "appodeal"

appodeal.init({
    app_key = "YOUR_APPODEAL_APP_KEY",
    testing = true,
    log_level = "verbose",
}, function(event)
    pprint(event)
end)

if appodeal.is_interstitial_available() then
    appodeal.show_interstitial(function(event)
        pprint(event)
    end)
end
```

## Совместимость с provider-слоем

См. `example/provider_adapter.lua`:
- `init`
- `is_fullscreen_available`
- `show_interstitial`
- `show_rewarded`
- `is_rewarded_available`

## Troubleshooting

### 1) `Could not resolve com.appodeal...`

Проверь, что в Android Gradle подключены репозитории:
- `https://artifactory.appodeal.com/appodeal`
- `https://artifactory.appodeal.com/appodeal-public`
- `mavenCentral()`
- `google()`

В extension это уже сделано в `appodeal/manifests/android/build.gradle`.

### 2) Добавил AdMob adapter и получил ошибку про `APPLICATION_ID`

Тогда добавь meta-data `com.google.android.gms.ads.APPLICATION_ID` в manifest приложения.

### 3) R8/ProGuard вырезает классы

В extension добавлены keep-rules: `appodeal/manifests/android/proguard-rules.pro`.
Если у проекта есть свои override-правила, проверь, что keep для `com.appodeal.ads.**` и `com.defold.appodeal.AppodealBridge` не удален.

### 4) Ошибки сети / cleartext

В extension добавлен `network_security_config`:
`appodeal/manifests/android/res/xml/defold_appodeal_network_security_config.xml`.

### 5) minSdk/targetSdk/Gradle

- Appodeal 4.0.0 требует `minSdk 23+`.
- Для публикации в Google Play используй актуальный targetSdk (для Defold 1.12.1 это `36`, проверь требования Play Console на дату релиза).
- Используй актуальную Defold-версию, чтобы Android toolchain/Gradle в билде были современными.

## Чеклист проверки на реальном Android устройстве

1. Собрать Android bundle с extension.
2. Установить сборку на физическое устройство.
3. Включить `testing = true`.
4. Вызвать `init` и получить callback `initialized`.
5. Дождаться доступности interstitial (`is_interstitial_available() == true`).
6. Показать interstitial, проверить `shown -> closed` (или `show_failed` с причиной).
7. Дождаться доступности rewarded (`is_rewarded_available() == true`).
8. Показать rewarded, проверить цепочку `shown -> reward -> closed` и корректные поля `amount/currency/rewarded`.
9. Проверить повторные показы (несколько циклов), чтобы callbacks не терялись.
10. Проверить поведение при сворачивании/возврате приложения.

## Официальные источники

- Defold Native Extensions overview: https://defold.com/manuals/extensions/
- Defold extension manifests: https://defold.com/manuals/extensions-ext-manifests/
- Appodeal Android get started: https://docs.appodeal.com/android/get-started
- Appodeal Android interstitial callbacks: https://docs.appodeal.com/android/ad-types/interstitial
- Appodeal Android rewarded callbacks: https://docs.appodeal.com/android/ad-types/rewarded-video
- Appodeal Android migration to 4.0.0: https://docs.appodeal.com/android/advanced/upgrade-guide
