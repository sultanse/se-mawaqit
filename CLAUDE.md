# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

مواقيت — an Android TV app (package `sa.riyadh.screen`) that shows a full-screen dashboard for Riyadh: clock, Gregorian and Hijri date, weather, and prayer times with a countdown to the next prayer. The UI is Arabic and RTL.

## Build / run

This folder holds only the editable sources. There is no Gradle project, build script, test suite, or linter here. Per the README, the build files and signing key are kept outside this folder on purpose, so do not add build tooling or a keystore.

- Remote: https://github.com/sultanse/se-mawaqit
- To iterate on the UI, open `assets/index.html` in a desktop browser. Add `?lat=…&lon=…` to test other coordinates. The clock, dates, and day/night sky render on their own. Prayer and weather data only appear when you push data in from the devtools console, for example `setData("prayers", JSON.stringify(<aladhan response>))` (see the bridge contract below).
- Size the viewport to 16:9 (e.g. 1920×1080). Everything is sized in `vw` for a TV screen viewed from 2.5–3 m.
- To preview a sky phase, set `document.body.dataset.phase` to `night`, `dawn`, `day`, or `dusk`. `renderSky()` resets it on the next tick, so also stub `Date` or add `*{transition:none!important}`, because phases fade over 90 s.

## Architecture

Two layers, joined by a one-way JavaScript bridge:

- **Java shell** (`src/sa/riyadh/screen/`). `Preview` is the launcher activity (it appears in both `LEANBACK_LAUNCHER` and `LAUNCHER`). It goes immersive full-screen and hosts `Dashboard`. `Dashboard` owns the `WebView`, loads `file:///android_asset/index.html`, and **does all network I/O** on a single-thread executor. The page never fetches anything itself.
- **Web UI** (`assets/index.html`). A single self-contained file: inline CSS, an inline SVG Riyadh skyline (Kingdom Centre and Al Faisaliah), and a plain ES5-style script, with no external resources or fonts. The layout has two parts. On the right are the city, the clock, the dates, the weather, and a `#status` line that shows only when there's a problem. On the left is a glass panel (`#prayers`) listing the five prayers vertically, with Sunrise (الشروق) as a smaller `.minor` row between Fajr and Dhuhr. The next entry, which can be Sunrise, is highlighted with the remaining time from `remainingText()`. The page renders the clock and re-renders the prayer list every second.

### Bridge contract (Java → JS)

`Dashboard.request()` fetches a URL, parses it as `JSONObject`, then calls:

- `window.setData(kind, jsonString)`. The payload is the JSON **as a string**, so JS calls `JSON.parse` on it.
- `window.setError(kind)` on any failure: non-200 status, timeout (12 s), a response over 250 KB, or bad JSON.

`kind` is one of `"prayers"` or `"weather"`. `"prayers"` is Aladhan's range endpoint (`/v1/calendar/from/<today>/to/<today+7>`), so `data` is an array of 8 days whose timings carry a ` (+03)` suffix. JS also accepts the older single-day shape (`data` as an object). `"weather"` carries both `current` and a 3-day `hourly` forecast. If you add a new data source, you have to change both `Dashboard.java` (the fetch and the kind) and the `setData`/`setError` branches in `index.html`.

### Refresh schedule (`Dashboard.refresh`)

The cycle starts from `onPageFinished` and then runs once every 60 s (each run is a "tick"). The week of prayer times is fetched when the Riyadh date changes or every 60 ticks (about hourly). Weather is fetched every 15 ticks.

### Offline cache

Every successful response is saved in `SharedPreferences("cache")` under its `kind`, with a timestamp in `<kind>_at`. In `onPageFinished`, before the first fetch, the cache is replayed through `setData`. Prayer data is replayed if it is under 7 days old and weather if it is under 48 h old. The goal is that an outage of a day or more leaves the screen unchanged:

- JS keeps prayer timings in `days`, keyed by `dayKey()`, and drops any day outside today…today+8. Today's row is always `days[dayKey(0)]`, so midnight needs no special handling, and the after-Isha countdown uses `days[dayKey(1)]`.
- `currentWeather()` uses the live `current` reading while it is from the current Riyadh hour, and otherwise this hour's entry in `hourly`. Only past the end of the forecast does it show "الطقس غير متاح حاليًا".

### Invariants that span both files

- **Time zone is always `Asia/Riyadh`.** Neither layer uses the device time zone. Java uses `TimeZone.getTimeZone("Asia/Riyadh")`, and JS uses `Intl.DateTimeFormat` with `timeZone: "Asia/Riyadh"` everywhere.
- **Date key format `dd-MM-yyyy`** has to match in three places: Java `date()`, which builds the Aladhan URL; Aladhan's `data.date.gregorian.date`; and JS `dayKey()`. Open-Meteo's `hourly.time` is Riyadh-local `yyyy-MM-ddTHH:00`, and `currentWeather()` builds that key from `dayKey()`.
- **Location** is defined once, in the `LATITUDE`/`LONGITUDE` constants in `Dashboard.java`. Java uses them in the API URLs and also passes them to the page as `index.html?lat=…&lon=…`, where they become the `place` object that `solarTimes()` uses. The Riyadh values in `place` are only fallbacks for a browser preview. If you change the city, update the Java constants and the city labels in the HTML. The solar-noon formula also assumes UTC+3 (the `+ 180`).
- Prayer times come from Aladhan with `method=4` (Umm al-Qura). Weather comes from Open-Meteo (`temperature_2m`, `weather_code`, mapped to Arabic text in `weatherName()`).
- `renderSky()` sets `body[data-phase]`, and CSS drives the sky layers, sun or moon, stars, and skyline colours from it. The phases are:
  - `dawn`: Fajr to sunrise
  - `day`: sunrise until sunset − 45 min
  - `dusk`: until sunset + 40 min
  - `night`: the rest

  The boundaries come from today's Aladhan `Fajr`/`Sunrise`/`Sunset`. If those aren't loaded yet, they fall back to `solarTimes()`, with Fajr taken as sunrise − 80 min.
- After Isha, the Fajr row is highlighted with "غدًا بعد …", which needs tomorrow in `days`. Without it, the row is highlighted but shows no remaining time.

### Platform constraints

- `minSdkVersion 23`, `targetSdkVersion 36`. The external build config must use `compileSdk` ≥ 36. If it is Gradle-based, its `targetSdk` overrides the one in the manifest. The Java uses lambdas and try-with-resources, so it needs Java 8 source level. The only permission is `INTERNET`, and `usesCleartextTraffic="false"`, so every endpoint must be HTTPS.
- The WebView has JavaScript and file access enabled and content access disabled. There is no `addJavascriptInterface`, so JS cannot call into Java.
- UI text is Arabic. Numbers stay in Latin digits on purpose (the `-nu-latn` locale extension; `ar()` returns `String(n)` unchanged). Time strings are built with `direction: ltr` inside the RTL layout, and `formatTime(value, true)` puts the ص/م period marker first in the markup. Its containers (`.clock`, `.prayer > .num`) must stay flex. As plain inline text, the bidi algorithm moves the Arabic marker to the right of the digits.
