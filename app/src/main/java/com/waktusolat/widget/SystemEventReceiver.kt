package com.waktusolat.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Boot, app update, timezone change, time set, date change → force refresh + re-arm alarms. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AlarmScheduler.ensureScheduled(context)
                AppUpdater.doUpdate(context.applicationContext, true)
            } finally {
                pending.finish()
            }
        }
    }
}
