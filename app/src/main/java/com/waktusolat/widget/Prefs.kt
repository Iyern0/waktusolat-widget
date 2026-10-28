package com.waktusolat.widget

import android.content.Context

/** Tiny SharedPreferences wrapper — one file, no DataStore dependency. */
object Prefs {
    private const val FILE = "waktusolat"
    const val KEY_ZONE = "zone"
    const val KEY_ZONE_NAME = "zone_name"
    const val KEY_MODE = "mode"
    const val KEY_MONTH_JSON = "month_json"
    const val KEY_MONTH_YEAR = "month_year"
    const val KEY_MONTH_NUM = "month_num"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun zone(c: Context): String? = p(c).getString(KEY_ZONE, null)
    fun zoneName(c: Context): String? = p(c).getString(KEY_ZONE_NAME, null)
    fun monthJson(c: Context): String? = p(c).getString(KEY_MONTH_JSON, null)
    fun monthYear(c: Context): Int = p(c).getInt(KEY_MONTH_YEAR, 0)
    fun monthNum(c: Context): Int = p(c).getInt(KEY_MONTH_NUM, 0)

    fun saveZone(c: Context, zone: String, name: String?, mode: String) {
        p(c).edit().putString(KEY_ZONE, zone).putString(KEY_ZONE_NAME, name).putString(KEY_MODE, mode).apply()
    }

    fun saveMonth(c: Context, rawJson: String, year: Int, monthNum: Int) {
        p(c).edit().putString(KEY_MONTH_JSON, rawJson).putInt(KEY_MONTH_YEAR, year).putInt(KEY_MONTH_NUM, monthNum).apply()
    }
}
