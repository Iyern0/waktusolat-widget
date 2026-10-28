package com.waktusolat.widget

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.TimeZone

private val MYT = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

/**
 * Central refresh. Monthly fetch is fire-and-forget (non-blocking).
 * Widget re-renders from cache every minute; monthly data updates in background.
 */
object AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = kotlinx.coroutines.sync.Mutex()

    fun update(context: Context, forceRefresh: Boolean = false) {
        val ctx = context.applicationContext
        scope.launch { doUpdate(ctx, forceRefresh) }
    }

    /** Suspend fetch+save of current month. Returns false on failure. */
    suspend fun refreshNow(ctx: Context): Boolean = mutex.withLock {
        val zone = Prefs.zone(ctx) ?: return@withLock false
        val body = PrayerApi.fetchMonthByZone(zone) ?: return@withLock false
        val month = PrayerApi.parseMonth(body) ?: return@withLock false
        Prefs.saveMonth(ctx, body, month.year, month.monthNum)
        true
    }

    suspend fun doUpdate(ctx: Context, forceRefresh: Boolean) {
        val cal = Calendar.getInstance(MYT)
        val stale = forceRefresh ||
            Prefs.monthJson(ctx) == null ||
            Prefs.monthYear(ctx) != cal.get(Calendar.YEAR) ||
            Prefs.monthNum(ctx) != cal.get(Calendar.MONTH) + 1
        if (stale) refreshNow(ctx)
        PrayerWidget.updateAll(ctx)
    }
}
