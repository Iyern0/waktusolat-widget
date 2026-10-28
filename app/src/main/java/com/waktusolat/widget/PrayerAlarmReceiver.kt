package com.waktusolat.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires exactly at one prayer time (armed by [AlarmScheduler] with `setAlarmClock`, so it is a
 * real user-visible alarm). Purely a notifier: it never blocks on the network.
 */
class PrayerAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_NAME = "prayer_name"
        const val EXTRA_AT = "prayer_at"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_NAME)
            ?.let { runCatching { PrayerKey.valueOf(it) }.getOrNull() }
            ?: return
        val atSec = intent.getLongExtra(EXTRA_AT, 0L)

        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PrayerNotifier.notify(app, key, atSec)
            } catch (e: Exception) {
                Log.w("PrayerAlarmReceiver", "notify failed", e)
            } finally {
                pending.finish()
            }
        }

        // Re-arm: the next prayer's slot (and tomorrow's Fajr) must exist even if no refresh
        // happens before then.
        AlarmScheduler.ensureScheduled(context)
    }
}
