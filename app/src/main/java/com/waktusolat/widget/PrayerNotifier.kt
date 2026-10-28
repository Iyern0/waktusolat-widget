package com.waktusolat.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Prayer-time notifications. Opt-in (toggle in setup) and silently skipped when the OS denies
 * notifications — the widget itself never depends on this.
 */
object PrayerNotifier {
    private const val CHANNEL_ID = "prayer_time"
    private const val NOTIFICATION_BASE_ID = 1000

    fun notify(context: Context, key: PrayerKey, atSec: Long) {
        if (!canNotify(context)) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, manager)

        val name = context.getString(nameRes(key))
        val time = PrayerModel.hm(atSec)
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_BASE_ID + key.ordinal,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title, name, time))
            .setContentText(context.getString(R.string.notif_text, name))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        manager.notify(NOTIFICATION_BASE_ID + key.ordinal, notification)
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun nameRes(key: PrayerKey): Int = when (key) {
        PrayerKey.FAJR -> R.string.prayer_fajr
        PrayerKey.DHUHR -> R.string.prayer_dhuhr
        PrayerKey.ASR -> R.string.prayer_asr
        PrayerKey.MAGHRIB -> R.string.prayer_maghrib
        PrayerKey.ISHA -> R.string.prayer_isha
    }
}
