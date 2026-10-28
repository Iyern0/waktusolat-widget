package com.waktusolat.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** A network result that carries a human-readable reason instead of swallowing failures. */
sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val message: String) : ApiResult<Nothing>()
}

data class MonthPayload(val raw: String, val month: MonthData)

data class Zone(val code: String, val name: String)

/**
 * Raw HTTP + JSON parsing. HttpURLConnection only — no OkHttp/Retrofit.
 *
 * Parsing is deliberately tolerant: one malformed day or a renamed envelope field must not
 * take the whole month down for the rest of the month.
 */
object PrayerApi {
    private const val BASE = "https://api.waktusolat.app"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    private val MONTH_NAMES = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12,
    )

    /** Current month for a zone. */
    suspend fun fetchMonthByZone(zone: String): ApiResult<MonthPayload> =
        fetchMonthUrl("$BASE/v2/solat/$zone")

    /** Explicit month — used to prefetch next month before the calendar rolls over. */
    suspend fun fetchMonth(zone: String, year: Int, month: Int): ApiResult<MonthPayload> =
        fetchMonthUrl("$BASE/v2/solat/$zone?year=$year&month=$month")

    /** GPS variant: the server resolves the nearest zone and returns the same month shape. */
    suspend fun fetchMonthByGps(lat: Double, lon: Double): ApiResult<MonthPayload> =
        fetchMonthUrl("$BASE/v2/solat/gps/$lat/$lon")

    /** Network zone list. The app ships a bundled copy, so this is only a refresh. */
    suspend fun fetchZones(): List<Zone> = withContext(Dispatchers.IO) {
        when (val r = httpGet("$BASE/zones")) {
            is ApiResult.Ok -> zonesFromJson(r.value)
            is ApiResult.Err -> emptyList()
        }
    }

    fun zonesFromJson(body: String): List<Zone> = try {
        val arr = JSONArray(body)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val code = o.optString("jakimCode").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val negeri = o.optString("negeri")
            val daerah = o.optString("daerah")
            Zone(code, if (daerah.isBlank()) negeri else "$negeri - $daerah")
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Parses one month envelope. Returns null only when the month is unusable as a whole
     * (no zone, no year, no valid days) — a single bad day is skipped instead.
     */
    fun parseMonth(body: String): MonthData? = try {
        val o = JSONObject(body)
        val zone = o.optString("zone").takeIf { it.isNotBlank() }
        val year = o.optInt("year", 0)
        val monthNum = o.optInt("month_number", 0).takeIf { it in 1..12 }
            ?: MONTH_NAMES[o.optString("month").uppercase()]
        val arr = o.optJSONArray("prayers")

        if (zone == null || year <= 0 || monthNum == null || arr == null) {
            null
        } else {
            val days = HashMap<Int, DayTimes>(arr.length())
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val day = d.optInt("day", 0)
                val fajr = d.optLong("fajr", 0L)
                if (day !in 1..31 || fajr <= 0L) continue
                days[day] = DayTimes(
                    day = day,
                    hijri = d.optString("hijri"),
                    imsak = d.optLong("imsak", 0L),
                    fajr = fajr,
                    syuruk = d.optLong("syuruk", 0L),
                    dhuha = d.optLong("dhuha", 0L),
                    dhuhr = d.optLong("dhuhr", 0L),
                    asr = d.optLong("asr", 0L),
                    maghrib = d.optLong("maghrib", 0L),
                    isha = d.optLong("isha", 0L),
                )
            }
            if (days.isEmpty()) null else MonthData(zone, year, monthNum, days)
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchMonthUrl(url: String): ApiResult<MonthPayload> =
        withContext(Dispatchers.IO) {
            when (val r = httpGet(url)) {
                is ApiResult.Err -> r
                is ApiResult.Ok -> {
                    val parsed = parseMonth(r.value)
                    if (parsed == null) ApiResult.Err("Unreadable response")
                    else ApiResult.Ok(MonthPayload(r.value, parsed))
                }
            }
        }

    /** Returns the body, or an [ApiResult.Err] naming what went wrong. */
    private fun httpGet(url: String): ApiResult<String> = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
        }
        conn.disconnect()
        if (code in 200..299 && !body.isNullOrBlank()) {
            ApiResult.Ok(body)
        } else {
            ApiResult.Err(if (code in 200..299) "Empty response" else "HTTP $code")
        }
    } catch (e: SocketTimeoutException) {
        ApiResult.Err("timeout")
    } catch (e: UnknownHostException) {
        ApiResult.Err("offline")
    } catch (e: Exception) {
        ApiResult.Err(e.javaClass.simpleName)
    }
}
