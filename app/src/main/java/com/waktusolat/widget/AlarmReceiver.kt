package com.waktusolat.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires on the minute tick and on the 15-minute watchdog → re-arm FIRST, then update. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm SYNCHRONOUSLY — the chain must survive even if the process dies below.
        AlarmScheduler.ensureScheduled(context)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppUpdater.doUpdate(context.applicationContext, false)
            } finally {
                pending.finish()
            }
        }
    }
}
