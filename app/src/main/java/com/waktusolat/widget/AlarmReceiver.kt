package com.waktusolat.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Minute tick and 15-minute watchdog. Re-arms FIRST (synchronously) so the chain survives even
 * if the process dies while the network call is still running, then refreshes with a hard
 * timeout so `goAsync()` cannot be held past the receiver budget.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.ensureScheduled(context)

        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(12_000) {
                    AppUpdater.doUpdate(app, false)
                }
            } catch (e: TimeoutCancellationException) {
                // Keep whatever cache exists — a hung request must not leave the widget blank.
                Log.w("AlarmReceiver", "update timed out; repainting cached data")
                PrayerWidget.updateAll(app)
            } catch (e: Exception) {
                Log.w("AlarmReceiver", "update failed", e)
                PrayerWidget.updateAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}
