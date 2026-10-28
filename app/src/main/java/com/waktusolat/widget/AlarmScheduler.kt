package com.waktusolat.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Two alarms keep the widget honest:
 *
 *  1. a minute chain — exact when the OS permits it, re-renders the countdown every minute;
 *  2. a 15-minute watchdog — always permitted (inexact), re-arms the chain if a tick was
 *     dropped, the app was updated, or an OEM battery manager cancelled the pending alarm.
 *
 * The watchdog is the recovery path; the minute chain is the quality path.
 */
object AlarmScheduler {
    private const val REQ_TICK = 1001
    private const val REQ_WATCHDOG = 1002
    private const val MINUTE_MS = 60_000L
    private const val WATCHDOG_MS = 15 * 60_000L

    /** True when setAlarmClock/setExact* are usable (always true below Android 12). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** Arms both alarms. Idempotent — call from every entry point (boot, update, launch). */
    fun ensureScheduled(context: Context) {
        scheduleNext(context)
        scheduleWatchdog(context)
    }

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, REQ_TICK)
        val now = System.currentTimeMillis()
        val triggerAt = ((now / MINUTE_MS) + 1) * MINUTE_MS

        if (canScheduleExact(context)) {
            // User-visible alarm: exempt from Doze and from the 9-minute idle batching limit.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
        } else {
            // Android 12+ without the runtime grant. Inexact + idle-throttled, so the widget
            // may lag by several minutes — the UI tells the user how to fix this.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun scheduleWatchdog(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + WATCHDOG_MS,
            WATCHDOG_MS,
            pending(context, REQ_WATCHDOG),
        )
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, REQ_TICK))
        am.cancel(pending(context, REQ_WATCHDOG))
    }

    private fun pending(context: Context, requestCode: Int) = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
