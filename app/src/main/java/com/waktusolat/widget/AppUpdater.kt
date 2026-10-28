package com.waktusolat.widget

import android.content.Context
import android.util.Log
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
 * Central refresh.
 *
 * Two cached months, both keyed by zone: the current one and (from the 20th onwards) the next
 * one, so the last days of a month still know tomorrow's Fajr. Every network attempt is
 * recorded, and repeated failures back off instead of firing once a minute.
 */
object AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Seconds to wait after the Nth consecutive failure (index = fail count, capped). */
    private val BACKOFF_SEC = longArrayOf(0, 60, 120, 300, 900, 1_800, 3_600)

    fun update(context: Context, forceRefresh: Boolean = false) {
        val ctx = context.applicationContext
        scope.launch { doUpdate(ctx, forceRefresh) }
    }

    /** Fetch + save the current month now (suspend, used by setup actions). */
    suspend fun refreshNow(ctx: Context): Boolean = mutex.withLock { fetchCurrent(ctx, force = true) }

    private fun backoffAllows(ctx: Context, force: Boolean): Boolean {
        if (force) return true
        val fails = Prefs.failCount(ctx)
        if (fails <= 0) return true
        val waitMs = BACKOFF_SEC[fails.coerceIn(0, BACKOFF_SEC.size - 1)] * 1_000L
        return System.currentTimeMillis() - Prefs.lastAttemptMs(ctx) >= waitMs
    }

    private fun hasCachedDay(ctx: Context, dayOfMonth: Int): Boolean {
        val raw = Prefs.monthJson(ctx) ?: return false
        return PrayerApi.parseMonth(raw)?.days?.containsKey(dayOfMonth) == true
    }

    private suspend fun fetchCurrent(ctx: Context, force: Boolean): Boolean {
        val zone = Prefs.zone(ctx) ?: return false
        if (!backoffAllows(ctx, force)) return false

        val now = System.currentTimeMillis()
        Prefs.recordAttempt(ctx, now)
        return when (val result = PrayerApi.fetchMonthByZone(zone)) {
            is ApiResult.Err -> {
                Prefs.recordFailure(ctx, now, result.message)
                Log.w("AppUpdater", "month fetch failed: ${result.message}")
                false
            }
            is ApiResult.Ok -> {
                val month = result.value.month
                if (month.zone != zone) {
                    // Server answered for a different zone — never save it under this one.
                    Prefs.recordFailure(ctx, now, "zone mismatch")
                    false
                } else {
                    Prefs.saveMonth(ctx, result.value.raw, month.year, month.monthNum, zone)
                    Prefs.recordSuccess(ctx, now)
                    true
                }
            }
        }
    }

    private suspend fun fetchNextMonth(ctx: Context, zone: String, year: Int, monthNum: Int) {
        val now = System.currentTimeMillis()
        Prefs.recordAttempt(ctx, now)
        when (val result = PrayerApi.fetchMonth(zone, year, monthNum)) {
            is ApiResult.Err -> Prefs.recordFailure(ctx, now, result.message)
            is ApiResult.Ok -> {
                val month = result.value.month
                if (month.zone == zone && month.year == year && month.monthNum == monthNum) {
                    Prefs.saveNextMonth(ctx, result.value.raw, year, monthNum, zone)
                    Prefs.recordSuccess(ctx, now)
                } else {
                    Prefs.recordFailure(ctx, now, "zone mismatch")
                }
            }
        }
    }

    /**
     * Repaint + refresh. Non-blocking for callers; safe to call from a broadcast receiver.
     *
     * @param forceRefresh bypasses the backoff and the staleness checks.
     */
    suspend fun doUpdate(ctx: Context, forceRefresh: Boolean) {
        val zone = Prefs.zone(ctx)
        if (zone == null) {
            PrayerWidget.updateAll(ctx)
            return
        }

        val cal = Calendar.getInstance(MYT)
        val year = cal.get(Calendar.YEAR)
        val monthNum = cal.get(Calendar.MONTH) + 1
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)

        // Promote a prefetched month as soon as it becomes the current one, so the first
        // minutes of a new month never render "Loading…".
        if (Prefs.nextJson(ctx) != null &&
            Prefs.nextZone(ctx) == zone &&
            Prefs.nextYear(ctx) == year &&
            Prefs.nextNum(ctx) == monthNum
        ) {
            val (cachedYear, cachedNum) = Prefs.monthYear(ctx) to Prefs.monthNum(ctx)
            if (cachedYear != year || cachedNum != monthNum || Prefs.monthZone(ctx) != zone) {
                Prefs.nextJson(ctx)?.let { raw ->
                    Prefs.saveMonth(ctx, raw, year, monthNum, zone)
                    Prefs.recordSuccess(ctx, System.currentTimeMillis())
                }
            }
        }

        val needsCurrent = forceRefresh ||
            Prefs.monthJson(ctx) == null ||
            Prefs.monthZone(ctx) != zone ||
            Prefs.monthYear(ctx) != year ||
            Prefs.monthNum(ctx) != monthNum ||
            !hasCachedDay(ctx, dayOfMonth)

        if (needsCurrent) fetchCurrent(ctx, forceRefresh)

        // From the 20th on, the last days of the month need tomorrow's Fajr. Prefetch the
        // next month once, and refresh it if it belongs to another zone or is missing.
        val needsNext = Prefs.nextJson(ctx) == null || Prefs.nextZone(ctx) != zone
        if (needsNext && dayOfMonth >= 20 && backoffAllows(ctx, forceRefresh)) {
            val nextMonth = if (monthNum == 12) 1 else monthNum + 1
            val nextYear = if (monthNum == 12) year + 1 else year
            fetchNextMonth(ctx, zone, nextYear, nextMonth)
        }

        // Notifications need the prayer-time alarms re-armed whenever data changes.
        AlarmScheduler.ensureScheduled(ctx)
        PrayerWidget.updateAll(ctx)
    }
}
