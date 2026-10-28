package com.waktusolat.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

private val MYT = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
private const val MECCA_LAT = 21.4225
private const val MECCA_LON = 39.8262

class PrayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // The system pings us at least every 30 min (updatePeriodMillis). Treat any system
        // update as a chance to repair a dead alarm chain.
        AlarmScheduler.ensureScheduled(context)
        for (id in ids) updateOne(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        AlarmScheduler.ensureScheduled(context)
    }

    override fun onDisabled(context: Context) {
        // Last widget removed — stop draining battery.
        AlarmScheduler.cancelAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PrayerWidget::class.java))
            for (id in ids) updateOne(context, manager, id)
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_prayer)

                val intent = Intent(context, MainActivity::class.java)
                val pi = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.tv_next_name, pi)

                val nowSec = System.currentTimeMillis() / 1000
                val cal = Calendar.getInstance(MYT)
                val zone = Prefs.zone(context)

                if (zone == null) {
                    showPlaceholder(views, manager, id, "Waktu Solat", "Tap to set")
                    return
                }

                // Only render data whose month/year matches "now". On the 1st of a month the
                // cached JSON still holds last month's day 1, which would paint times that
                // are already hours in the past ("stuck at in now").
                val cachedYear = Prefs.monthYear(context)
                val cachedMonth = Prefs.monthNum(context)
                val monthIsCurrent = cachedYear == cal.get(Calendar.YEAR) &&
                    cachedMonth == cal.get(Calendar.MONTH) + 1
                val month = if (monthIsCurrent) {
                    Prefs.monthJson(context)?.let { PrayerApi.parseMonth(it) }
                } else null

                val dayNum = cal.get(Calendar.DAY_OF_MONTH)
                val today = month?.days?.get(dayNum)
                val tomorrow = month?.days?.get(dayNum + 1)

                if (today == null) {
                    showPlaceholder(views, manager, id, "Waktu Solat", "Loading…")
                    return
                }

                val prayers = listOf(
                    Triple("Fajr", today.fajr, R.id.tv_fajr),
                    Triple("Dhuhr", today.dhuhr, R.id.tv_dhuhr),
                    Triple("Asr", today.asr, R.id.tv_asr),
                    Triple("Maghrib", today.maghrib, R.id.tv_maghrib),
                    Triple("Isha", today.isha, R.id.tv_isha),
                )

                val upcoming = prayers.firstOrNull { it.second > nowSec }
                // After Isha there is no next prayer in today's data. Tomorrow's Fajr is only
                // knowable once the next month's JSON arrives (after midnight) — never fall
                // back to today's Fajr, which would render a past time with "in now".
                val nextName = upcoming?.first ?: "Fajr"
                val nextTime = upcoming?.second ?: tomorrow?.fajr
                val nextPillId = upcoming?.third ?: R.id.tv_fajr

                // Hero
                views.setTextViewText(R.id.tv_next_name, nextName.uppercase())
                views.setTextViewText(R.id.tv_next_time, nextTime?.let { hm(it) } ?: "--:--")
                views.setTextViewText(
                    R.id.tv_countdown,
                    when {
                        nextTime == null -> "tomorrow"
                        else -> "in " + countdown(nowSec, nextTime)
                    },
                )

                // Remaining prayers as pills — wrap_content so no truncation
                val remaining = prayers.filter { it.second > nowSec }
                val allPillIds = listOf(R.id.tv_fajr, R.id.tv_dhuhr, R.id.tv_asr, R.id.tv_maghrib, R.id.tv_isha)

                for (pid in allPillIds) views.setViewVisibility(pid, View.GONE)

                for ((_, time, viewId) in remaining) {
                    val isNext = viewId == nextPillId
                    views.setViewVisibility(viewId, View.VISIBLE)
                    views.setTextViewText(viewId, hm(time))
                    val textColor = if (isNext) Color.parseColor("#FFD54F") else Color.parseColor("#DDDDDD")
                    views.setTextColor(viewId, textColor)
                }

                // Qibla
                val bearing = qiblaBearing(3.139, 101.6869)
                views.setTextViewText(R.id.tv_qibla, "Qibla ${qiblaArrow(bearing)} ${bearing}°")
                views.setTextViewText(R.id.tv_zone, Prefs.zoneName(context) ?: zone)

                manager.updateAppWidget(id, views)
            } catch (e: Exception) {
                android.util.Log.e("PrayerWidget", "update failed", e)
            }
        }

        private fun showPlaceholder(
            views: RemoteViews,
            manager: AppWidgetManager,
            id: Int,
            title: String,
            subtitle: String,
        ) {
            views.setTextViewText(R.id.tv_next_name, title)
            views.setTextViewText(R.id.tv_next_time, subtitle)
            views.setTextViewText(R.id.tv_countdown, "")
            views.setViewVisibility(R.id.tv_qibla, View.GONE)
            views.setTextViewText(R.id.tv_zone, "")
            hidePills(views)
            manager.updateAppWidget(id, views)
        }

        private fun hidePills(views: RemoteViews) {
            for (id in listOf(R.id.tv_fajr, R.id.tv_dhuhr, R.id.tv_asr,
                R.id.tv_maghrib, R.id.tv_isha)) {
                views.setViewVisibility(id, View.GONE)
            }
        }

        private fun qiblaBearing(lat: Double, lon: Double): Int {
            val lat1 = Math.toRadians(lat); val lon1 = Math.toRadians(lon)
            val lat2 = Math.toRadians(MECCA_LAT); val lon2 = Math.toRadians(MECCA_LON)
            val dLon = lon2 - lon1
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            var brng = Math.toDegrees(atan2(y, x)).toInt()
            if (brng < 0) brng += 360
            return brng
        }

        private fun qiblaArrow(bearing: Int): String = when (bearing) {
            in 338..360, in 0..22 -> "↑"
            in 23..67 -> "↗"
            in 68..112 -> "→"
            in 113..157 -> "↘"
            in 158..202 -> "↓"
            in 203..247 -> "↙"
            in 248..292 -> "←"
            in 293..337 -> "↖"
            else -> "↖"
        }

        private fun hm(epochSec: Long): String {
            val c = Calendar.getInstance(MYT).apply { timeInMillis = epochSec * 1000 }
            return "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }

        private fun countdown(nowSec: Long, targetSec: Long): String {
            var s = targetSec - nowSec; if (s < 0) s = 0
            val h = s / 3600; val m = (s % 3600) / 60
            return when { h > 0 -> "${h}h ${m}m"; m > 0 -> "${m} min"; else -> "now" }
        }
    }
}
