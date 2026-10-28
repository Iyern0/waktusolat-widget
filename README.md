# Waktu Solat Widget

An Android home-screen widget showing the next Malaysian prayer time (JAKIM zones), with a
live countdown, all five daily prayers, the Hijri date, and a qibla bearing.

## Features

- Next prayer + countdown, refreshed every minute
- All five prayers as pills — passed, next and upcoming stay visible after Isyak
- Hijri date (JAKIM month names) and a qibla bearing for the selected zone or GPS position
- Prayer-time notifications, opt-in per device
- GPS or manual JAKIM zone selection, from a zone list bundled with the app so setup works offline
- Monthly timetable from [api.waktusolat.app](https://api.waktusolat.app), cached per zone,
  with next month prefetched from the 20th

## Reliability notes

The widget survives the ways Android kills background work:

- **Exact alarms**: Android 12+ needs a user grant. The setup screen offers it; without it the
  minute chain falls back to inexact alarms and can lag in Doze.
- **Watchdog**: a 15-minute inexact repeating alarm re-arms the minute chain if a tick is lost.
- **Recovery broadcasts**: boot, time set, timezone change and app update re-arm and refresh.
- **Stale cache guard**: a cached month is only rendered when its zone, year and month match
  today — a zone change drops the old month instead of showing another city's times.
- **Failure state**: repeated fetch failures back off (1/2/5/15/30/60 min) and the widget says
  *No data · updated 28 Oct* instead of pretending it is loading forever.

## Build

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and an Android SDK with platform 35. `local.properties` must point at your SDK
(`sdk.dir=...`) and is intentionally not committed.

## Install

Download the latest APK from [Releases](../../releases) and sideload it, or:

```bash
adb install -r app-debug.apk
```

After installing, open the app once and tap **Allow exact alarms** if the banner appears.

## Layout

- `PrayerModel.kt` — pure render model: next prayer, pills, countdown, Hijri date, qibla
  (unit-tested in `app/src/test/.../PrayerModelTest.kt`)
- `ZoneGeo.kt` — state-capital coordinates per JAKIM zone code, for the qibla bearing
- `ZoneCatalogue.kt` — bundled `assets/zones.json` loader (60 zones)
- `AlarmScheduler.kt` — minute tick, 15-minute watchdog, per-prayer notification alarms
- `AlarmReceiver.kt` — tick handling, re-arm before refresh
- `PrayerAlarmReceiver.kt` / `PrayerNotifier.kt` — prayer-time notifications
- `AppUpdater.kt` — zone-keyed cache refresh, backoff, next-month prefetch, repaint
- `PrayerWidget.kt` — widget rendering, compact mode, accessibility descriptions
- `PrayerApi.kt` — tolerant JSON parsing and the HTTP client
- `MainActivity.kt` — Compose setup screen (GPS / zone search / notifications / exact-alarm fix)
