# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

مواقيت — an Android TV app (package `sa.se.mawaqit`) that shows a full-screen dashboard for a Saudi city: clock, Gregorian and Hijri date, weather, a rotating Quran verse, and prayer times with a countdown to the next prayer. The UI is Arabic and RTL.

## Build / run

This folder holds only the editable sources. There is no Gradle project, build script, test suite, or linter here. The build files and signing key are kept outside the repo on purpose (see `.gitignore`), so do not add build tooling or a keystore.

- Remote: https://github.com/sultanse/se-mawaqit
- To iterate on the UI, open `assets/index.html` in a desktop browser. Add `?lat=…&lon=…&city=…` to test another city (any `city` other than `الرياض` switches to the Makkah skyline). The clock, dates, and day/night sky render on their own. Prayer and weather data only appear when you push data in from the devtools console, for example `setData("prayers", JSON.stringify(<aladhan response>))` (see the bridge contract below). The settings menu can be previewed with `showMenu({title:"…", items:["…"], focus:0, checked:-1})`.
- Size the viewport to 16:9 (e.g. 1920×1080). Everything is sized in `vw` for a TV screen viewed from 2.5–3 m.
- To preview a sky phase, set `document.body.dataset.phase` to `night`, `dawn`, `day`, or `dusk`. `renderSky()` resets it on the next tick, so also stub `Date` or add `*{transition:none!important}`, because phases fade over 90 s.

## Architecture

Two layers, joined by a one-way JavaScript bridge (Java → JS only):

- **Java shell** (`src/sa/se/mawaqit/`).
  - `Preview` is the launcher activity (in both `LEANBACK_LAUNCHER` and `LAUNCHER`). It goes immersive full-screen, hosts `Dashboard`, and owns the remote keys.
  - `Dream` (a `DreamService`) hosts the same `Dashboard` as the TV screensaver, non-interactive.
  - `Dashboard` owns the `WebView`, loads `file:///android_asset/index.html?<city.query()>`, and **does all network I/O** on a single-thread executor. The page never fetches anything itself.
  - `City` is the fixed list of Saudi cities (name + coordinates). `City.nearest()` snaps a coordinate to the closest one.
  - `ScreenSaver` switches the system screensaver between `Dream` and the previous one by writing `screensaver_components`, because Google TV doesn't list third-party screensavers in Settings. The previous one is remembered in `SharedPreferences("screensaver")` and falls back to `screensaver_default_component`.
- **Web UI** (`assets/index.html`). A single self-contained file: inline CSS, inline SVG skylines, and a plain ES5-style script, with no external resources or fonts. On the right is a fixed-width (46vw) column: a `.head` row with the city (`#city`) and the weather, then the clock, the dates, a glass verse card (`#ayah`), and a `#status` line that shows only when there's a problem. On the left is a glass panel (`#prayers`) listing the five prayers, with Sunrise (الشروق) as a row between Fajr and Dhuhr. The next entry, which can be Sunrise, is highlighted with the remaining time from `remainingText()`. The page re-renders every second.

### Settings menu (OK on the remote)

The menu is **drawn by the page but driven by `Preview.java`**, since JS can't call Java. `Preview.dispatchKeyEvent` intercepts keys before the WebView sees them, keeps the menu state (`level` = `root` / `city` / `saver`, `focus`), and on each change calls `Dashboard.menu(json)` → `window.showMenu({title, items, focus, checked})`, or `showMenu(null)` to close. `checked` is `-1` for a level with no current choice. OK only acts on a full press (both down and up seen), so the press that closes the menu can't reopen it. Navigation plays the system `AudioManager` sound effects.

### City selection

- The chosen city lives in `SharedPreferences("place")`: `city` (the Arabic name, looked up with `City.named()`, defaulting to Riyadh) and `manual` (true when pinned from the menu).
- In automatic mode, `detectCity()` geolocates by IP (`ipwho.is`, falling back to `ipinfo.io`) once at start and then every 1440 ticks (about daily), and snaps to `City.nearest()`. Outside Saudi Arabia, or on failure, the current city stays.
- `moveTo()` clears the whole `cache` prefs (the old city's data is wrong), sends `setData("place", {name, lat, lon})`, and refetches prayers and weather. `request()` captures the city when it starts and drops the response if the city changed in the meantime.
- The page shows Riyadh's skyline (Kingdom Centre, Al Faisaliah) for `الرياض` and Makkah's (Clock Tower, Haram minarets) for every other city. `applyPlace()` toggles `body.makkah`.

### Bridge contract (Java → JS)

`Dashboard.request()` fetches a URL through `fetch()` (HTTPS, 12 s timeout, 250 KB cap), parses it as `JSONObject`, then calls:

- `window.setData(kind, jsonString)`. The payload is the JSON **as a string**, so JS calls `JSON.parse` on it.
- `window.setError(kind)` on any failure: non-200 status, timeout, oversize response, or bad JSON.

`kind` is one of:

- `"prayers"`: Aladhan's range endpoint (`/v1/calendar/from/<today>/to/<today+7>`), so `data` is an array of 8 days whose timings carry a ` (+03)` suffix. JS also accepts the older single-day shape (`data` as an object).
- `"weather"`: Open-Meteo, with both `current` and a 3-day `hourly` forecast.
- `"place"`: sent by `moveTo()`, not fetched and not cached. JS resets `days` and `weather` and calls `applyPlace()`.

If you add a new data source, you have to change both `Dashboard.java` (the fetch and the kind) and the `setData`/`setError` branches in `index.html`.

### Refresh schedule (`Dashboard.refresh`)

The cycle starts from `onPageFinished` and then runs once every 60 s (each run is a "tick"). The week of prayer times is fetched when the Riyadh date changes or every 60 ticks (about hourly). Weather is fetched every 15 ticks. City detection runs every 1440 ticks in automatic mode.

### Offline cache

Every successful response is saved in `SharedPreferences("cache")` under its `kind`, with a timestamp in `<kind>_at`. In `onPageFinished`, before the first fetch, the cache is replayed through `setData`. Prayer data is replayed if it is under 7 days old and weather if it is under 48 h old. The goal is that an outage of a day or more leaves the screen unchanged:

- JS keeps prayer timings in `days`, keyed by `dayKey()`, and drops any day outside today…today+8. Today's row is always `days[dayKey(0)]`, so midnight needs no special handling, and the after-Isha countdown uses `days[dayKey(1)]`.
- `currentWeather()` uses the live `current` reading while it is from the current Riyadh hour, and otherwise this hour's entry in `hourly`. Only past the end of the forecast does it show "الطقس غير متاح حاليًا".

### Invariants that span both files

- **Time zone is always `Asia/Riyadh`**, for every city (all of Saudi Arabia is UTC+3). Neither layer uses the device time zone. Java uses `TimeZone.getTimeZone("Asia/Riyadh")`, and JS uses `Intl.DateTimeFormat` with `timeZone: "Asia/Riyadh"` everywhere. The solar-noon formula in `solarTimes()` also assumes UTC+3 (the `+ 180`). Adding a city outside UTC+3 would break both.
- **Date key format `dd-MM-yyyy`** has to match in three places: Java `date()`, which builds the Aladhan URL; Aladhan's `data.date.gregorian.date`; and JS `dayKey()`. Open-Meteo's `hourly.time` is Riyadh-local `yyyy-MM-ddTHH:00`, and `currentWeather()` builds that key from `dayKey()`.
- **Location** comes from the current `City`. Java uses its coordinates in the API URLs and passes `lat`, `lon` and `city` in the page query (and later through `setData("place")`), where they become the JS `place` object that `solarTimes()` and `applyPlace()` use. The Riyadh defaults in `place` are only for a browser preview. The skyline switch compares against the literal `"الرياض"`, so keep that name in sync with `City.RIYADH`.
- Prayer times come from Aladhan with `method=4` (Umm al-Qura). Weather comes from Open-Meteo (`temperature_2m`, `weather_code`, mapped to Arabic text in `weatherName()`).
- `renderSky()` sets `body[data-phase]`, and CSS drives the sky layers, sun or moon, stars, and skyline colours from it. The phases are:
  - `dawn`: Fajr to sunrise
  - `day`: sunrise until sunset − 45 min
  - `dusk`: until sunset + 40 min
  - `night`: the rest

  The boundaries come from today's Aladhan `Fajr`/`Sunrise`/`Sunset`. If those aren't loaded yet, they fall back to `solarTimes()`, with Fajr taken as sunrise − 80 min.
- **Verses (`VERSES`, `renderAyah()`).** There are 43 short verses, stored verbatim from the Quranpedia.net dump (version 2026-10-19), which is the Hafs Uthmani text of the King Fahd Complex edition.
  - Every verse was checked letter for letter against Tanzil.
  - Don't edit the text, and don't add verses without the same check. The two sources use different Unicode for the marks, for example sukun is `U+06E1` versus `U+0652`, so compare letters, not raw strings.
  - Quranpedia's licence needs no on-screen attribution for use inside an app.
  - The verse index is `floor(Date.now() / AYAH_PERIOD) % length` (two minutes), so the list advances in order and survives restarts.
  - There are no ﴿ ﴾ brackets, because the TV's Noto font draws them as plain braces.
- `<body class="boot">` disables every transition until a second after load, so the app opens in the correct phase instead of fading there from `night`.
- After Isha, the Fajr row is highlighted with the time left until tomorrow's Fajr ("بعد …"), which needs tomorrow in `days`. Without it, the row is highlighted but shows no remaining time.

### Platform constraints

- `minSdkVersion 23`, `targetSdkVersion 36`. The external build config must use `compileSdk` ≥ 36. If it is Gradle-based, its `targetSdk` overrides the one in the manifest. The Java uses lambdas and try-with-resources, so it needs Java 8 source level. The permissions are:
  - `INTERNET`. `usesCleartextTraffic="false"`, so every endpoint must be HTTPS.
  - `WRITE_SECURE_SETTINGS`. It can't be granted at install time, so grant it once per device with `adb shell pm grant sa.se.mawaqit android.permission.WRITE_SECURE_SETTINGS`. The grant survives app updates. Without it, choosing a screensaver just shows a toast asking for the grant.
- The WebView has JavaScript and file access enabled and content access disabled. There is no `addJavascriptInterface`, so JS cannot call into Java. Anything interactive (like the menu) has to be driven from Java through `evaluateJavascript`.
- UI text is Arabic. Numbers stay in Latin digits on purpose (the `-nu-latn` locale extension; `ar()` returns `String(n)` unchanged). Time strings are built with `direction: ltr` inside the RTL layout, and `formatTime(value, true)` puts the ص/م period marker first in the markup. Its containers (`.clock`, `.prayer > .num`) must stay flex. As plain inline text, the bidi algorithm moves the Arabic marker to the right of the digits.
