package com.waktusolat.widget

import android.content.Context

/**
 * Zone list bundled as an asset (60 entries, ~5 KB) so first-run setup works offline.
 * The network copy is only used to refresh the list; setup never blocks on it.
 */
object ZoneCatalogue {
    private const val ASSET = "zones.json"

    @Volatile
    private var cache: List<Zone>? = null

    fun load(context: Context): List<Zone> {
        cache?.let { return it }
        val loaded = try {
            context.assets.open(ASSET).bufferedReader().use { PrayerApi.zonesFromJson(it.readText()) }
        } catch (e: Exception) {
            emptyList()
        }
        cache = loaded
        return loaded
    }

    fun find(context: Context, code: String): Zone? = load(context).firstOrNull { it.code == code }
}
