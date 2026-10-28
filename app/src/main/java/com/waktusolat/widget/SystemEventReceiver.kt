package com.waktusolat.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Boot, app update, timezone change, time set → re-arm every schedule and force a refresh.
 * (`DATE_CHANGED` is deliberately absent: it is never delivered to manifest receivers on
 * API 26+; the midnight rollover is covered by the minute tick instead.)
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.ensureScheduled(context)

        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(15_000) {
                    AppUpdater.doUpdate(app, forceRefresh = true)
                }
            } catch (e: Exception) {
                Log.w("SystemEventReceiver", "refresh failed", e)
                PrayerWidget.updateAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}
