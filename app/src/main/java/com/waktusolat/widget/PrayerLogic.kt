package com.waktusolat.widget

import java.util.Calendar
import java.util.TimeZone

// JAKIM zones = Malaysia only. API returns UTC epoch — always display as MYT (UTC+8).
private val MYT = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

/** Pure logic — testable without Android. All times epoch SECONDS. */
object PrayerLogic {
    /** Main 5 prayers, in order. */
    val MAIN_PRAYERS: List<String> = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

    private val ALL_SLOTS: List<Pair<String, (Day) -> Long>> = listOf(
        "Imsak" to Day::imsak, "Fajr" to Day::fajr, "Syuruk" to Day::syuruk,
        "Dhuha" to Day::dhuha, "Dhuhr" to Day::dhuhr, "Asr" to Day::asr,
        "Maghrib" to Day::maghrib, "Isha" to Day::isha
    )

    fun today(month: Month?, dayOfMonth: Int): Day? = month?.days?.get(dayOfMonth)

    fun next(nowSec: Long, today: Day?, tomorrow: Day?): NextPrayer? {
        if (today == null) return null
        for ((name, get) in ALL_SLOTS) {
            val t = get(today)
            if (t > nowSec) return NextPrayer(name, t, true)
        }
        val fajr = tomorrow?.fajr ?: today.fajr
        return NextPrayer("Fajr", fajr, false)
    }

    /** Countdown without seconds — clean per-minute update. */
    fun countdown(nowSec: Long, targetSec: Long): String {
        var s = targetSec - nowSec
        if (s < 0) s = 0
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m} min"
            else -> "now"
        }
    }

    fun hm(epochSec: Long): String {
        val c = Calendar.getInstance(MYT).apply { timeInMillis = epochSec * 1000 }
        return "%2d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun dayOfMonth(): Int = Calendar.getInstance(MYT).get(Calendar.DAY_OF_MONTH)
    fun nowSec(): Long = System.currentTimeMillis() / 1000

    /** Classify each prayer as passed or upcoming. */
    fun classify(nowSec: Long, today: Day?): List<PrayerStatus> {
        if (today == null) return emptyList()
        return MAIN_PRAYERS.map { name ->
            PrayerStatus(name, timeOf(today, name), timeOf(today, name) < nowSec)
        }
    }

    private fun timeOf(d: Day, name: String): Long = when (name) {
        "Fajr" -> d.fajr; "Dhuhr" -> d.dhuhr; "Asr" -> d.asr
        "Maghrib" -> d.maghrib; "Isha" -> d.isha; else -> 0L
    }
}

data class NextPrayer(val name: String, val timeSec: Long, val isToday: Boolean)
data class PrayerStatus(val name: String, val timeSec: Long, val passed: Boolean)
