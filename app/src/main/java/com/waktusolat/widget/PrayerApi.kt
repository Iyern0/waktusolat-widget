package com.waktusolat.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One day of prayer times. All times are epoch SECONDS (API format). */
data class Day(
    val day: Int,
    val imsak: Long, val fajr: Long, val syuruk: Long, val dhuha: Long,
    val dhuhr: Long, val asr: Long, val maghrib: Long, val isha: Long
)

data class Month(
    val zone: String,
    val year: Int,
    val monthNum: Int,
    val days: Map<Int, Day>
)

/** Raw HTTP calls + JSON parse. HttpURLConnection only, no OkHttp/Retrofit. */
object PrayerApi {
    private const val BASE = "https://api.waktusolat.app"

    /** Returns the raw JSON body for the current month of a zone. */
    suspend fun fetchMonthByZone(zone: String): String? = fetch("$BASE/v2/solat/$zone")

    /** GPS variant: resolves nearest zone server-side and returns the same month shape. */
    suspend fun fetchMonthByGps(lat: Double, lon: Double): String? = fetch("$BASE/v2/solat/gps/$lat/$lon")

    suspend fun fetchZones(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val body = httpGet("$BASE/zones") ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getString("jakimCode") to o.getString("negeri") + " - " + o.getString("daerah")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseMonth(body: String): Month? = try {
        val o = JSONObject(body)
        val zone = o.getString("zone")
        val year = o.getInt("year")
        val monthNum = o.getInt("month_number")
        val arr = o.getJSONArray("prayers")
        val days = HashMap<Int, Day>(arr.length())
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            days[d.getInt("day")] = Day(
                day = d.getInt("day"),
                imsak = d.getLong("imsak"), fajr = d.getLong("fajr"),
                syuruk = d.getLong("syuruk"), dhuha = d.getLong("dhuha"),
                dhuhr = d.getLong("dhuhr"), asr = d.getLong("asr"),
                maghrib = d.getLong("maghrib"), isha = d.getLong("isha")
            )
        }
        Month(zone, year, monthNum, days)
    } catch (e: Exception) {
        null
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        httpGet(url)
    }

    private fun httpGet(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
        conn.disconnect()
        body
    } catch (e: Exception) {
        null
    }
}
