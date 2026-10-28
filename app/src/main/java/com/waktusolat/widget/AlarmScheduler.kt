package com.waktusolat.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.TimeZone

private val MYT = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

/**
 * Three independent schedules:
 *
 *  1. the minute tick — repaints the countdown; exact-and-idle-capable, never an alarm clock;
 *  2. the 15-minute watchdog — always permitted (inexact), re-arms the tick if one was dropped;
 *  3. per-prayer alarms — the only place `setAlarmClock` belongs, since those are real,
 *     user-visible alarms that should wake the device exactly on time.
 *
 * Everything is idempotent: every entry point (boot, update, launch, refresh) calls
 * [ensureScheduled], and the heartbeat alarms are cancelled when there is nothing to show.
 */
object AlarmScheduler {
    private const val REQ_TICK = 1001
    private const val REQ_WATCHDOG = 1002

    /** Per-prayer request codes: REQ_PRAYER_BASE + 0..4 for today's five, +5 for tomorrow's Fajr. */
    private const val REQ_PRAYER_BASE = 2000
    private const val PRAYER_SLOTS = 6

    private const val MINUTE_MS = 60_000L
    private const val WATCHDOG_MS = 15 * 60_000L

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun hasWidgets(context: Context): Boolean {
        val am = AppWidgetManager.getInstance(context)
        val ids = am.getAppWidgetIds(ComponentName(context, PrayerWidget::class.java))
        return ids.isNotEmpty()
    }

    /**
     * Re-arms every schedule this app needs right now. Call from every entry point; it is
     * cheap and cancels work that is no longer needed (e.g. notifications just turned off).
     */
    fun ensureScheduled(context: Context) {
        val notificationsWanted = Prefs.notifyEnabled(context) && Prefs.zone(context) != null

        if (notificationsWanted) schedulePrayerAlarms(context) else cancelPrayerAlarms(context)

        if (!hasWidgets(context)) {
            // Nothing to repaint: the heartbeat is pure battery cost. Prayer alarms, if the
            // user wants them, survive on their own.
            if (notificationsWanted) cancelTick(context) else cancelAll(context)
            return
        }
        scheduleTick(context)
        scheduleWatchdog(context)
    }

    private fun scheduleTick(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, REQ_TICK, AlarmReceiver::class.java)
        val now = System.currentTimeMillis()
        val triggerAt = ((now / MINUTE_MS) + 1) * MINUTE_MS

        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            // Android 12+ without the grant: inexact and idle-throttled. The setup screen
            // explains the trade-off; the watchdog still keeps data fresh.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun scheduleWatchdog(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + WATCHDOG_MS,
            WATCHDOG_MS,
            pending(context, REQ_WATCHDOG, AlarmReceiver::class.java),
        )
    }

    /**
     * One alarm per upcoming prayer (plus tomorrow's Fajr, so a late-night viewer still gets
     * the morning notification). Needs a cached month for the current zone.
     */
    private fun schedulePrayerAlarms(context: Context) {
        val zone = Prefs.zone(context) ?: return
        val month = Prefs.monthJson(context)?.let { PrayerApi.parseMonth(it) } ?: return
        if (month.zone != zone) return

        val cal = Calendar.getInstance(MYT)
        val todayNum = cal.get(Calendar.DAY_OF_MONTH)
        val today = month.day(todayNum) ?: return
        val tomorrow = month.day(todayNum + 1)
            ?: Prefs.nextJson(context)?.let { PrayerApi.parseMonth(it) }?.day(1)

        val nowSec = System.currentTimeMillis() / 1_000

        // Today's remaining prayers.
        PrayerKey.entries.forEachIndexed { i, key ->
            val at = today.timeOf(key)
            if (at > nowSec && at - nowSec <= 86_400) {
                armPrayer(context, i, key, at)
            }
        }
        // Tomorrow's Fajr, only when today's day was found.
        tomorrow?.let {
            val at = it.fajr
            if (at > nowSec) armPrayer(context, PrayerKey.entries.size, PrayerKey.FAJR, at)
        }
    }

    private fun armPrayer(context: Context, slot: Int, key: PrayerKey, triggerSec: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, PrayerAlarmReceiver::class.java)
            .putExtra(PrayerAlarmReceiver.EXTRA_NAME, key)
            .putExtra(PrayerAlarmReceiver.EXTRA_AT, triggerSec)
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_PRAYER_BASE + slot,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = triggerSec * 1_000

        if (canScheduleExact(context)) {
            val show = PendingIntent.getActivity(
                context,
                REQ_PRAYER_BASE + slot,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelPrayerAlarms(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (slot in 0 until PRAYER_SLOTS) {
            am.cancel(
                PendingIntent.getBroadcast(
                    context,
                    REQ_PRAYER_BASE + slot,
                    Intent(context, PrayerAlarmReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
    }

    private fun cancelTick(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, REQ_TICK, AlarmReceiver::class.java))
        am.cancel(pending(context, REQ_WATCHDOG, AlarmReceiver::class.java))
    }

    fun cancelAll(context: Context) {
        cancelTick(context)
        cancelPrayerAlarms(context)
    }

    private fun pending(
        context: Context,
        requestCode: Int,
        target: Class<*>,
    ) = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, target),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
