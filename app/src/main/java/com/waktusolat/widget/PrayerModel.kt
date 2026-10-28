package com.waktusolat.widget

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure widget logic — no Android imports, so it runs under plain JUnit on the JVM.
 *
 * The old `PrayerLogic` object was unused dead code whose `next()` could return Syuruk or
 * Dhuha and still carried the past-Fajr bug. This replaces it and is the single place where
 * "what should the widget say" is decided.
 */

enum class PrayerKey { FAJR, DHUHR, ASR, MAGHRIB, ISHA }

/** One day of JAKIM times. All fields are epoch SECONDS, as the API returns them. */
data class DayTimes(
    val day: Int,
    val hijri: String,
    val imsak: Long,
    val fajr: Long,
    val syuruk: Long,
    val dhuha: Long,
    val dhuhr: Long,
    val asr: Long,
    val maghrib: Long,
    val isha: Long,
) {
    fun timeOf(key: PrayerKey): Long = when (key) {
        PrayerKey.FAJR -> fajr
        PrayerKey.DHUHR -> dhuhr
        PrayerKey.ASR -> asr
        PrayerKey.MAGHRIB -> maghrib
        PrayerKey.ISHA -> isha
    }
}

/** One month of times for one zone. The zone is what makes a cached month reusable. */
data class MonthData(
    val zone: String,
    val year: Int,
    val monthNum: Int,
    val days: Map<Int, DayTimes>,
) {
    fun day(dayOfMonth: Int): DayTimes? = days[dayOfMonth]
}

enum class PillState { PASSED, NEXT, FUTURE }

data class Pill(val key: PrayerKey, val timeSec: Long, val state: PillState)

/** Everything `PrayerWidget` needs, computed once per repaint. */
data class WidgetState(
    val zoneLabel: String,
    val nextKey: PrayerKey,
    /** null when the next prayer is tomorrow's Fajr and next month's data is not cached. */
    val nextTimeSec: Long?,
    val isTomorrow: Boolean,
    /** "2h 15m", "<1 min" or "now" — never "in now". */
    val countdownText: String?,
    val pills: List<Pill>,
    val hijriLabel: String?,
    val qiblaDegrees: Int,
    val qiblaArrow: String,
    /** "updated 28 Oct" / "offline" / null — shown only in the placeholder state. */
    val staleNote: String?,
)

object PrayerModel {
    val ORDER: List<PrayerKey> = listOf(
        PrayerKey.FAJR, PrayerKey.DHUHR, PrayerKey.ASR, PrayerKey.MAGHRIB, PrayerKey.ISHA,
    )

    private val MYT: TimeZone = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

    private const val MECCA_LAT = 21.4225
    private const val MECCA_LON = 39.8262

    /**
     * Builds the render state.
     *
     * Returns null only when [today] is unknown (no usable cached month for today); the
     * caller then paints a placeholder that explains why.
     */
    fun build(
        nowSec: Long,
        today: DayTimes?,
        tomorrow: DayTimes?,
        zoneLabel: String,
        hijri: String?,
        hijriMonths: List<String>,
        lat: Double,
        lon: Double,
        staleNote: String? = null,
    ): WidgetState? {
        if (today == null) return null

        val next: PrayerKey? = ORDER.firstOrNull { today.timeOf(it) > nowSec }
        val isTomorrow = next == null
        val nextKey = next ?: PrayerKey.FAJR
        val nextTime: Long? = if (next != null) today.timeOf(next) else tomorrow?.fajr

        val pills = ORDER.map { key ->
            val time = today.timeOf(key)
            val state = when {
                time <= nowSec -> PillState.PASSED
                key == next -> PillState.NEXT
                else -> PillState.FUTURE
            }
            Pill(key, time, state)
        }

        val countdown = nextTime?.let { countdown(nowSec, it) }
        val bearing = qiblaBearing(lat, lon)

        return WidgetState(
            zoneLabel = zoneLabel,
            nextKey = nextKey,
            nextTimeSec = nextTime,
            isTomorrow = isTomorrow,
            countdownText = countdown,
            pills = pills,
            hijriLabel = hijriLabel(hijri, hijriMonths),
            qiblaDegrees = bearing,
            qiblaArrow = qiblaArrow(bearing),
            staleNote = staleNote,
        )
    }

    /** "2h 15m" / "15 min" / "<1 min" / "now". Never returns "in now". */
    fun countdown(nowSec: Long, targetSec: Long): String {
        val s = targetSec - nowSec
        if (s <= 0) return "now"
        if (s < 60) return "<1 min"
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "%dh %dm".format(Locale.ROOT, h, m) else "%d min".format(Locale.ROOT, m)
    }

    /** 24-hour "16:04", locale-independent digits. */
    fun hm(epochSec: Long): String {
        val c = Calendar.getInstance(MYT).apply { timeInMillis = epochSec * 1000 }
        return "%d:%02d".format(
            Locale.ROOT,
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
        )
    }

    /** "28 Oct 2026" style label for the cache age, in the caller's locale. */
    fun shortDate(epochMs: Long, locale: Locale): String {
        val fmt = SimpleDateFormat("d MMM", locale)
        fmt.timeZone = MYT
        return fmt.format(epochMs)
    }

    /**
     * "1448-05-21" + JAKIM month names → "21 Jamadilawal 1448".
     * Returns null for a missing or malformed value rather than inventing a date.
     */
    fun hijriLabel(hijri: String?, monthNames: List<String>): String? {
        if (hijri.isNullOrBlank()) return null
        val parts = hijri.split('-')
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..30) return null
        val name = monthNames.getOrNull(month - 1) ?: return null
        return "$day $name $year"
    }

    /** Initial great-circle bearing from the observer to the Kaaba, 0..359. */
    fun qiblaBearing(lat: Double, lon: Double): Int {
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = Math.toRadians(MECCA_LAT)
        val lon2 = Math.toRadians(MECCA_LON)
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        var brng = Math.toDegrees(atan2(y, x)).roundToInt()
        if (brng < 0) brng += 360
        return brng % 360
    }

    /**
     * Eight-way arrow, rounded to the nearest 45° sector. Kuala Lumpur's ~292° bearing sits
     * almost exactly between 270° (W) and 315° (NW); nearest-sector rounding picks W (270°),
     * which is the closest of the eight labels.
     */
    fun qiblaArrow(bearing: Int): String {
        val arrows = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
        val index = (((bearing + 22.5) / 45.0).toInt()) % 8
        return arrows[index]
    }
}
