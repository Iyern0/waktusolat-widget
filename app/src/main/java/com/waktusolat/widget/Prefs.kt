package com.waktusolat.widget

import android.content.Context

/**
 * SharedPreferences wrapper — one file, no DataStore dependency.
 *
 * Every cached month is stored together with the zone it belongs to. Without that, a manual
 * zone change plus a failed refresh would keep rendering the previous zone's times under the
 * new zone's name, which is worse than showing nothing.
 */
object Prefs {
    private const val FILE = "waktusolat"

    const val KEY_ZONE = "zone"
    const val KEY_ZONE_NAME = "zone_name"
    const val KEY_MODE = "mode"

    const val KEY_MONTH_JSON = "month_json"
    const val KEY_MONTH_YEAR = "month_year"
    const val KEY_MONTH_NUM = "month_num"
    const val KEY_MONTH_ZONE = "month_zone"

    const val KEY_NEXT_JSON = "next_json"
    const val KEY_NEXT_YEAR = "next_year"
    const val KEY_NEXT_NUM = "next_num"
    const val KEY_NEXT_ZONE = "next_zone"

    const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"
    const val KEY_LAST_SUCCESS_MS = "last_success_ms"
    const val KEY_FAIL_COUNT = "fail_count"
    const val KEY_LAST_ERROR = "last_error"

    const val KEY_LAT = "lat"
    const val KEY_LON = "lon"
    const val KEY_NOTIFY = "notify_enabled"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun zone(c: Context): String? = p(c).getString(KEY_ZONE, null)
    fun zoneName(c: Context): String? = p(c).getString(KEY_ZONE_NAME, null)
    fun mode(c: Context): String? = p(c).getString(KEY_MODE, null)

    fun monthJson(c: Context): String? = p(c).getString(KEY_MONTH_JSON, null)
    fun monthYear(c: Context): Int = p(c).getInt(KEY_MONTH_YEAR, 0)
    fun monthNum(c: Context): Int = p(c).getInt(KEY_MONTH_NUM, 0)
    fun monthZone(c: Context): String? = p(c).getString(KEY_MONTH_ZONE, null)

    fun nextJson(c: Context): String? = p(c).getString(KEY_NEXT_JSON, null)
    fun nextYear(c: Context): Int = p(c).getInt(KEY_NEXT_YEAR, 0)
    fun nextNum(c: Context): Int = p(c).getInt(KEY_NEXT_NUM, 0)
    fun nextZone(c: Context): String? = p(c).getString(KEY_NEXT_ZONE, null)

    fun lastAttemptMs(c: Context): Long = p(c).getLong(KEY_LAST_ATTEMPT_MS, 0L)
    fun lastSuccessMs(c: Context): Long = p(c).getLong(KEY_LAST_SUCCESS_MS, 0L)
    fun failCount(c: Context): Int = p(c).getInt(KEY_FAIL_COUNT, 0)
    fun lastError(c: Context): String? = p(c).getString(KEY_LAST_ERROR, null)

    fun lat(c: Context): Double? = p(c).getFloat(KEY_LAT, Float.NaN).let {
        if (it.isNaN()) null else it.toDouble()
    }

    fun lon(c: Context): Double? = p(c).getFloat(KEY_LON, Float.NaN).let {
        if (it.isNaN()) null else it.toDouble()
    }

    fun notifyEnabled(c: Context): Boolean = p(c).getBoolean(KEY_NOTIFY, false)

    fun saveZone(c: Context, zone: String, name: String?, mode: String) {
        p(c).edit()
            .putString(KEY_ZONE, zone)
            .putString(KEY_ZONE_NAME, name)
            .putString(KEY_MODE, mode)
            .apply()
    }

    /** Stores a month together with its zone — the zone is what validates the cache later. */
    fun saveMonth(c: Context, rawJson: String, year: Int, monthNum: Int, zone: String) {
        p(c).edit()
            .putString(KEY_MONTH_JSON, rawJson)
            .putInt(KEY_MONTH_YEAR, year)
            .putInt(KEY_MONTH_NUM, monthNum)
            .putString(KEY_MONTH_ZONE, zone)
            .apply()
    }

    fun saveNextMonth(c: Context, rawJson: String, year: Int, monthNum: Int, zone: String) {
        p(c).edit()
            .putString(KEY_NEXT_JSON, rawJson)
            .putInt(KEY_NEXT_YEAR, year)
            .putInt(KEY_NEXT_NUM, monthNum)
            .putString(KEY_NEXT_ZONE, zone)
            .apply()
    }

    /**
     * Drops both cached months. Called when the zone changes so a stale month can never be
     * paired with a new zone — the widget shows "Loading…" instead of another city's times.
     */
    fun clearMonths(c: Context) {
        p(c).edit()
            .remove(KEY_MONTH_JSON).remove(KEY_MONTH_YEAR).remove(KEY_MONTH_NUM)
            .remove(KEY_MONTH_ZONE)
            .remove(KEY_NEXT_JSON).remove(KEY_NEXT_YEAR).remove(KEY_NEXT_NUM)
            .remove(KEY_NEXT_ZONE)
            .apply()
    }

    fun saveLocation(c: Context, lat: Double, lon: Double) {
        p(c).edit().putFloat(KEY_LAT, lat.toFloat()).putFloat(KEY_LON, lon.toFloat()).apply()
    }

    fun setNotifyEnabled(c: Context, enabled: Boolean) {
        p(c).edit().putBoolean(KEY_NOTIFY, enabled).apply()
    }

    fun recordAttempt(c: Context, nowMs: Long) {
        p(c).edit().putLong(KEY_LAST_ATTEMPT_MS, nowMs).apply()
    }

    fun recordSuccess(c: Context, nowMs: Long) {
        p(c).edit()
            .putLong(KEY_LAST_SUCCESS_MS, nowMs)
            .putInt(KEY_FAIL_COUNT, 0)
            .putString(KEY_LAST_ERROR, null)
            .apply()
    }

    fun recordFailure(c: Context, nowMs: Long, message: String) {
        val next = (failCount(c) + 1).coerceAtMost(6)
        p(c).edit()
            .putLong(KEY_LAST_ATTEMPT_MS, nowMs)
            .putInt(KEY_FAIL_COUNT, next)
            .putString(KEY_LAST_ERROR, message)
            .apply()
    }
}
