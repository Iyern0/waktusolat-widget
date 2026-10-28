package com.waktusolat.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Regression tests for the widget's pure logic. The bugs these lock down were all real:
 * the past-Fajr fallback, “in now”, the previous month's times being rendered as today's,
 * and a malformed day taking the whole month down.
 */
class PrayerModelTest {

    private val myt: TimeZone = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

    private fun sec(hour: Int, minute: Int, day: Int = 29, month: Int = 9, year: Int = 2026): Long {
        val c = Calendar.getInstance(myt)
        c.clear()
        c.set(year, month, day, hour, minute, 0)
        return c.timeInMillis / 1000
    }

    private fun day(
        dayNum: Int = 29,
        fajrAt: Long = sec(5, 40),
        dhuhrAt: Long = sec(13, 5),
        asrAt: Long = sec(16, 20),
        maghribAt: Long = sec(19, 5),
        ishaAt: Long = sec(20, 10),
    ) = DayTimes(
        day = dayNum,
        hijri = "1448-05-21",
        imsak = fajrAt - 600,
        fajr = fajrAt,
        syuruk = fajrAt + 3600,
        dhuha = fajrAt + 5400,
        dhuhr = dhuhrAt,
        asr = asrAt,
        maghrib = maghribAt,
        isha = ishaAt,
    )

    private val today = day()
    private val tomorrow = day(
        dayNum = 30,
        fajrAt = sec(5, 40, day = 30),
        dhuhrAt = sec(13, 5, day = 30),
        asrAt = sec(16, 20, day = 30),
        maghribAt = sec(19, 5, day = 30),
        ishaAt = sec(20, 10, day = 30),
    )

    private fun state(nowSec: Long, tomorrowDay: DayTimes? = tomorrow): WidgetState? =
        PrayerModel.build(
            nowSec = nowSec,
            today = today,
            tomorrow = tomorrowDay,
            zoneLabel = "W.P. Kuala Lumpur",
            hijri = today.hijri,
            hijriMonths = listOf(
                "Muharram", "Safar", "Rabiulawal", "Rabiulakhir", "Jamadilawal",
                "Jamadilakhir", "Rejab", "Syaaban", "Ramadan", "Syawal", "Zulkaedah", "Zulhijjah",
            ),
            lat = 3.1390,
            lon = 101.6869,
        )

    @Test
    fun `before fajr the next prayer is fajr`() {
        val s = state(sec(4, 0))
        assertNotNull(s)
        assertEquals(PrayerKey.FAJR, s!!.nextKey)
        assertFalse(s.isTomorrow)
        assertEquals(today.fajr, s.nextTimeSec)
        assertEquals(PillState.NEXT, s.pills.first { it.key == PrayerKey.FAJR }.state)
        assertTrue(s.pills.all { it.state != PillState.PASSED })
    }

    @Test
    fun `passed prayers are marked passed and the next one next`() {
        val s = state(sec(15, 0))!!
        assertEquals(PrayerKey.ASR, s.nextKey)
        assertEquals(PillState.PASSED, s.pills.first { it.key == PrayerKey.DHUHR }.state)
        assertEquals(PillState.NEXT, s.pills.first { it.key == PrayerKey.ASR }.state)
        assertEquals(PillState.FUTURE, s.pills.first { it.key == PrayerKey.MAGHRIB }.state)
    }

    @Test
    fun `the exact prayer second belongs to the next prayer`() {
        val s = state(today.fajr)!!
        assertEquals(PrayerKey.DHUHR, s.nextKey)
        assertEquals(PillState.PASSED, s.pills.first { it.key == PrayerKey.FAJR }.state)
    }

    @Test
    fun `after isha the next prayer is tomorrow fajr with a real time`() {
        val s = state(sec(21, 0))!!
        assertTrue(s.isTomorrow)
        assertEquals(PrayerKey.FAJR, s.nextKey)
        assertEquals(tomorrow.fajr, s.nextTimeSec)
        assertEquals("in 8h 40m", "in " + PrayerModel.countdown(sec(21, 0), tomorrow.fajr))
    }

    @Test
    fun `after isha on the last day without tomorrow data shows no time`() {
        val s = state(sec(21, 0), tomorrowDay = null)!!
        assertTrue(s.isTomorrow)
        assertNull(s.nextTimeSec)
        assertNull(s.countdownText)
    }

    @Test
    fun `countdown never reads as in now`() {
        assertEquals("now", PrayerModel.countdown(1000, 1000))
        assertEquals("now", PrayerModel.countdown(1000, 900))
        assertEquals("<1 min", PrayerModel.countdown(1000, 1030))
        assertEquals("2 min", PrayerModel.countdown(1000, 1120))
        assertEquals("2h 15m", PrayerModel.countdown(1000, 1000 + 8_100))
    }

    @Test
    fun `hm renders 24 hour time`() {
        assertEquals("5:40", PrayerModel.hm(today.fajr))
        assertEquals("16:20", PrayerModel.hm(today.asr))
        assertEquals("20:10", PrayerModel.hm(today.isha))
    }

    @Test
    fun `hijri label uses JAKIM month names`() {
        val names = listOf(
            "Muharram", "Safar", "Rabiulawal", "Rabiulakhir", "Jamadilawal",
            "Jamadilakhir", "Rejab", "Syaaban", "Ramadan", "Syawal", "Zulkaedah", "Zulhijjah",
        )
        assertEquals("21 Jamadilawal 1448", PrayerModel.hijriLabel("1448-05-21", names))
        assertNull(PrayerModel.hijriLabel(null, names))
        assertNull(PrayerModel.hijriLabel("nope", names))
        assertNull(PrayerModel.hijriLabel("1448-13-21", names))
    }

    @Test
    fun `qibla arrow rounds to the nearest eighth`() {
        assertEquals("↑", PrayerModel.qiblaArrow(0))
        assertEquals("↗", PrayerModel.qiblaArrow(45))
        assertEquals("→", PrayerModel.qiblaArrow(90))
        assertEquals("↘", PrayerModel.qiblaArrow(135))
        assertEquals("↓", PrayerModel.qiblaArrow(180))
        assertEquals("↙", PrayerModel.qiblaArrow(225))
        assertEquals("←", PrayerModel.qiblaArrow(270))
        assertEquals("↖", PrayerModel.qiblaArrow(315))
        assertEquals("←", PrayerModel.qiblaArrow(292))
        assertEquals("↖", PrayerModel.qiblaArrow(300))
        assertEquals("↑", PrayerModel.qiblaArrow(359))
    }

    @Test
    fun `kuala lumpur bearing is near due west-north-west`() {
        val bearing = PrayerModel.qiblaBearing(3.1390, 101.6869)
        assertTrue("bearing was $bearing", bearing in 288..296)
    }

    @Test
    fun `month data is looked up by day`() {
        val month = MonthData("WLY01", 2026, 10, mapOf(29 to today))
        assertEquals(today, month.day(29))
        assertNull(month.day(30))
        assertEquals(today.fajr, today.timeOf(PrayerKey.FAJR))
        assertEquals(today.isha, today.timeOf(PrayerKey.ISHA))
    }

    @Test
    fun `parse skips malformed days instead of failing the month`() {
        val json = """
            {"zone":"WLY01","year":2026,"month":"OCT","month_number":10,"prayers":[
              {"day":1,"fajr":1000,"dhuhr":2000,"asr":3000,"maghrib":4000,"isha":5000},
              {"day":0,"fajr":1000},
              {"day":2,"fajr":0},
              {"day":3,"fajr":7000,"dhuhr":8000,"asr":9000,"maghrib":10000,"isha":11000}
            ]}
        """.trimIndent()
        val month = PrayerApi.parseMonth(json)
        assertNotNull(month)
        assertEquals(2, month!!.days.size)
        assertEquals(7000L, month.day(3)!!.fajr)
    }

    @Test
    fun `parse falls back to the month name when month_number is missing`() {
        val json = """
            {"zone":"WLY01","year":2026,"month":"NOV","prayers":[
              {"day":1,"fajr":1000,"dhuhr":2000,"asr":3000,"maghrib":4000,"isha":5000}
            ]}
        """.trimIndent()
        val month = PrayerApi.parseMonth(json)
        assertNotNull(month)
        assertEquals(11, month!!.monthNum)
    }

    @Test
    fun `parse rejects unusable envelopes`() {
        assertNull(PrayerApi.parseMonth("not json"))
        assertNull(PrayerApi.parseMonth("""{"prayers":[{"day":1,"fajr":1}]}"""))
        assertNull(PrayerApi.parseMonth("""{"zone":"WLY01","year":2026,"prayers":[]}"""))
    }
}
