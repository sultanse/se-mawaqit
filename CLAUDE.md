# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

مواقيت — an Android TV app (package `sa.riyadh.screen`) that shows a full-screen dashboard for Riyadh: clock, Gregorian and Hijri date, weather, and prayer times with a countdown to the next prayer. The UI is Arabic and RTL.

## Build / run

This folder holds only the editable sources. There is no Gradle project, build script, test suite, or linter here. Per the README, the build files and signing key are kept outside this folder on purpose, so do not add build tooling or a keystore.

- Remote: https://github.com/sultanse/se-mawaqit
- To iterate on the UI, open `assets/index.html` in a desktop browser. Add `?lat=…&lon=…` to test other coordinates. The clock, dates, and day/night sky render on their own. Prayer and weather data only appear when you push data in from the devtools console, for example `setData("prayers", JSON.stringify(<aladhan response>))` (see the bridge contract below).
- Size the viewport to 16:9 (e.g. 1920×1080). Everything is sized in `vh`/`vw` for a TV screen.

## Architecture

Two layers, joined by a one-way JavaScript bridge:

- **Java shell** (`src/sa/riyadh/screen/`). `Preview` is the launcher activity (it appears in both `LEANBACK_LAUNCHER` and `LAUNCHER`). It goes immersive full-screen and hosts `Dashboard`. `Dashboard` owns the `WebView`, loads `file:///android_asset/index.html`, and **does all network I/O** on a single-thread executor. The page never fetches anything itself.
- **Web UI** (`assets/index.html`). A single self-contained file: inline CSS, inline SVG skyline, plain ES5-style script, with no external resources or fonts. It renders the clock every second and re-renders the prayer row on every tick.

### Bridge contract (Java → JS)

`Dashboard.request()` fetches a URL, parses it as `JSONObject`, then calls:

- `window.setData(kind, jsonString)`. The payload is the JSON **as a string**, so JS calls `JSON.parse` on it.
- `window.setError(kind)` on any failure: non-200 status, timeout (12 s), a response over 250 KB, or bad JSON.

`kind` is one of `"prayers"`, `"tomorrow"`, or `"weather"`. If you add a new data source, you have to change both `Dashboard.java` (the fetch and the kind) and the `setData`/`setError` branches in `index.html`.

### Refresh schedule (`Dashboard.refresh`)

The cycle starts from `onPageFinished` and then runs once every 60 s (each run is a "tick"). Today's and tomorrow's prayer times are fetched when the Riyadh date changes or every 60 ticks (about hourly). Weather is fetched every 15 ticks.

### Offline cache

Every successful response is saved in `SharedPreferences("cache")` under its `kind`, with a timestamp in `<kind>_at`. In `onPageFinished`, before the first fetch, the cache is replayed through `setData`. Prayer data is replayed if it is under 48 h old and weather if it is under 3 h old. JS still checks the dates itself, so stale prayer data gets rejected. A cached `tomorrow` payload whose date is today fills today's times. At midnight `renderClock()` likewise promotes `tomorrow` to `times`, so the screen doesn't go blank until the next fetch succeeds.

### Invariants that span both files

- **Time zone is always `Asia/Riyadh`.** Neither layer uses the device time zone. Java uses `TimeZone.getTimeZone("Asia/Riyadh")`, and JS uses `Intl.DateTimeFormat` with `timeZone: "Asia/Riyadh"` everywhere.
- **Date key format `dd-MM-yyyy`** has to match in three places: Java `date()`, which builds the Aladhan URL; Aladhan's `data.date.gregorian.date`; and JS `dayKey()`. JS throws away any prayer response whose date isn't today or tomorrow. At midnight, if there is no `tomorrow` data to promote, `renderClock()` clears `times` until fresh data arrives.
- **Location** is defined once, in the `LATITUDE`/`LONGITUDE` constants in `Dashboard.java`. Java uses them in the API URLs and also passes them to the page as `index.html?lat=…&lon=…`, where they become the `place` object that `solarTimes()` uses. The Riyadh values in `place` are only fallbacks for a browser preview. If you change the city, update the Java constants and the city labels in the HTML. The solar-noon formula also assumes UTC+3 (the `+ 180`).
- Prayer times come from Aladhan with `method=4` (Umm al-Qura). Weather comes from Open-Meteo (`temperature_2m`, `weather_code`, mapped to Arabic text in `weatherName()`).
- The day/night sky uses Aladhan's `Sunrise`/`Sunset` for today. If those aren't loaded yet, it falls back to the approximate solar calculation in `solarTimes()`.
- After Isha, the "next prayer" is tomorrow's Fajr, which needs the `tomorrow` payload. Without it, the countdown is hidden.

### Platform constraints

- `minSdkVersion 23`, `targetSdkVersion 36`. The external build config must use `compileSdk` ≥ 36. If it is Gradle-based, its `targetSdk` overrides the one in the manifest. The Java uses lambdas and try-with-resources, so it needs Java 8 source level. The only permission is `INTERNET`, and `usesCleartextTraffic="false"`, so every endpoint must be HTTPS.
- The WebView has JavaScript and file access enabled and content access disabled. There is no `addJavascriptInterface`, so JS cannot call into Java.
- UI text is Arabic. Numbers stay in Latin digits on purpose (the `-nu-latn` locale extension; `ar()` returns `String(n)` unchanged). Time strings are built with `direction: ltr` inside the RTL layout, and `formatTime(value, true)` puts the ص/م period marker first in the markup.
