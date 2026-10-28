# Waktu Solat Widget

An Android home-screen widget showing the next Malaysian prayer time (JAKIM zones), with a
live countdown, the remaining prayers for the day, and a qibla bearing.

## Features

- Next prayer + countdown, refreshed every minute
- Remaining prayers as pills, next one highlighted
- Qibla direction (Kuala Lumpur bearing)
- GPS or manual JAKIM zone selection
- Monthly timetable fetched from [api.waktusolat.app](https://api.waktusolat.app) and cached

## Reliability notes

The widget survives the ways Android kills background work:

- **Exact alarms**: Android 12+ needs a user grant. The app asks for it; without it the minute
  chain falls back to inexact alarms and can lag in Doze.
- **Watchdog**: a 15-minute inexact repeating alarm re-arms the minute chain if a tick is lost.
- **Recovery broadcasts**: boot, time change, date change, timezone change and app update all
  re-arm and refresh; the system's 30-minute widget tick is a further safety net.
- **Stale cache guard**: a cached month that does not match today's year/month is never rendered.

## Build

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and an Android SDK with platform 35. `local.properties` must point at your SDK
(`sdk.dir=...`) and is intentionally not committed.

## Install

Download the latest APK from [Releases](../../releases) and sideload it, or:

```bash
adb install -r app-debug.apk
```

After installing, open the app once and tap **Allow exact alarms**.

## Layout

- `AlarmScheduler.kt` — minute chain + watchdog
- `AlarmReceiver.kt` — tick handling, re-arm before refresh
- `AppUpdater.kt` — monthly cache refresh + repaint
- `PrayerWidget.kt` — widget rendering
- `PrayerApi.kt` / `PrayerLogic.kt` — API client and pure time logic
- `MainActivity.kt` — Compose setup screen (GPS / zone list / exact-alarm fix)
