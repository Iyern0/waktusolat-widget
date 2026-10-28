package com.waktusolat.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val MYT: TimeZone = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

/**
 * Widget provider. Rendering is delegated to [PrayerModel] (pure, unit-tested); this class only
 * maps a [WidgetState] onto RemoteViews.
 */
class PrayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        AlarmScheduler.ensureScheduled(context)
        for (id in appWidgetIds) updateOne(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        AlarmScheduler.ensureScheduled(context)
    }

    override fun onDisabled(context: Context) {
        AlarmScheduler.cancelAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateOne(context, manager, appWidgetId)
    }

    companion object {
        private const val TAG = "PrayerWidget"
        private const val COMPACT_HEIGHT_DP = 120

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PrayerWidget::class.java))
            for (id in ids) {
                try {
                    updateOne(context, manager, id)
                } catch (e: Exception) {
                    Log.w(TAG, "repaint failed for $id", e)
                }
            }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val rv = RemoteViews(context.packageName, R.layout.widget_prayer)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.widget_root, open)
            rv.setOnClickPendingIntent(R.id.tv_next_name, open)

            val zone = Prefs.zone(context)
            val zoneLabel = Prefs.zoneName(context) ?: zone ?: context.getString(R.string.widget_placeholder_zone)

            if (zone == null) {
                showPlaceholder(
                    context, rv,
                    hero = context.getString(R.string.widget_placeholder_zone),
                    note = context.getString(R.string.widget_tap_to_set),
                )
            } else {
                render(context, rv, zone, zoneLabel)
            }

            // Compact layouts (110dp min resize) cannot fit pills + info column.
            val compact = manager.getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) < COMPACT_HEIGHT_DP
            if (compact) {
                rv.setViewVisibility(R.id.pill_row, View.GONE)
                rv.setViewVisibility(R.id.info_column, View.GONE)
                rv.setViewVisibility(R.id.divider, View.GONE)
            }

            manager.updateAppWidget(id, rv)
        }

        private fun render(context: Context, rv: RemoteViews, zone: String, zoneLabel: String) {
            val raw = Prefs.monthJson(context)
            val month = raw?.let { PrayerApi.parseMonth(it) }
            val isCurrent = month != null &&
                month.zone == zone &&
                Prefs.monthYear(context) == month.year &&
                Prefs.monthNum(context) == month.monthNum

            val cal = Calendar.getInstance(MYT)
            val dayNum = cal.get(Calendar.DAY_OF_MONTH)
            val nowSec = System.currentTimeMillis() / 1_000

            val today = if (isCurrent) month?.day(dayNum) else null
            val tomorrow = if (isCurrent) {
                month?.day(dayNum + 1) ?: nextMonthFirstDay(context, zone)
            } else {
                null
            }

            val state = today?.let {
                PrayerModel.build(
                    nowSec = nowSec,
                    today = it,
                    tomorrow = tomorrow,
                    zoneLabel = zoneLabel,
                    hijri = it.hijri,
                    hijriMonths = context.resources.getStringArray(R.array.hijri_months).toList(),
                    lat = Prefs.lat(context) ?: ZoneGeo.coordsFor(zone).first,
                    lon = Prefs.lon(context) ?: ZoneGeo.coordsFor(zone).second,
                    staleNote = null,
                )
            }

            if (state == null) {
                showPlaceholder(context, rv, context.getString(R.string.widget_placeholder_zone), placeholderNote(context))
                return
            }

            val heroName = name(context, state.nextKey)
            rv.setTextViewText(
                R.id.tv_next_name,
                if (state.isTomorrow) context.getString(R.string.hero_tomorrow, heroName) else heroName,
            )
            rv.setTextViewText(
                R.id.tv_next_time,
                state.nextTimeSec?.let { PrayerModel.hm(it) } ?: "--:--",
            )
            rv.setTextViewText(
                R.id.tv_countdown,
                state.countdownText?.let { context.getString(R.string.countdown_in, it) } ?: "",
            )

            renderPills(context, rv, state.pills)
            rv.setViewVisibility(R.id.pill_row, View.VISIBLE)
            rv.setViewVisibility(R.id.divider, View.VISIBLE)
            rv.setViewVisibility(R.id.info_column, View.VISIBLE)

            rv.setTextViewText(R.id.tv_zone, state.zoneLabel)
            rv.setTextViewText(
                R.id.tv_qibla,
                context.getString(R.string.qibla_format, state.qiblaArrow, state.qiblaDegrees),
            )
            if (state.hijriLabel != null) {
                rv.setTextViewText(R.id.tv_hijri, state.hijriLabel)
                rv.setViewVisibility(R.id.tv_hijri, View.VISIBLE)
            } else {
                rv.setViewVisibility(R.id.tv_hijri, View.GONE)
            }

            val time = state.nextTimeSec?.let { PrayerModel.hm(it) } ?: ""
            rv.setContentDescription(
                R.id.widget_root,
                context.getString(R.string.a11y_widget, heroName, time, state.countdownText ?: ""),
            )
        }

        private fun renderPills(context: Context, rv: RemoteViews, pills: List<Pill>) {
            val ids = intArrayOf(
                R.id.tv_fajr, R.id.tv_dhuhr, R.id.tv_asr, R.id.tv_maghrib, R.id.tv_isha,
            )
            pills.forEachIndexed { index, pill ->
                val id = ids[index]
                val name = name(context, pill.key)
                rv.setTextViewText(id, name)
                rv.setViewVisibility(id, View.VISIBLE)

                val (bg, fg) = when (pill.state) {
                    PillState.PASSED -> R.drawable.pill_passed to 0xFF808080.toInt()
                    PillState.NEXT -> R.drawable.pill_next to 0xFF1A1A1A.toInt()
                    PillState.FUTURE -> R.drawable.pill_normal to 0xFFDDDDDD.toInt()
                }
                rv.setInt(id, "setBackgroundResource", bg)
                rv.setTextColor(id, fg)

                val label = PrayerModel.hm(pill.timeSec)
                val a11y = when (pill.state) {
                    PillState.PASSED -> context.getString(R.string.a11y_pill_passed, name, label)
                    PillState.NEXT -> context.getString(R.string.a11y_pill_next, name, label)
                    PillState.FUTURE -> context.getString(R.string.a11y_pill, name, label)
                }
                rv.setContentDescription(id, a11y)
            }
        }

        private fun showPlaceholder(context: Context, rv: RemoteViews, hero: String, note: String) {
            rv.setTextViewText(R.id.tv_next_name, hero)
            rv.setTextViewText(R.id.tv_next_time, "")
            rv.setTextViewText(R.id.tv_countdown, note)
            rv.setViewVisibility(R.id.pill_row, View.GONE)
            rv.setViewVisibility(R.id.divider, View.GONE)
            rv.setViewVisibility(R.id.info_column, View.GONE)
            rv.setContentDescription(R.id.widget_root, "$hero $note")
        }

        /** Tells the user why there is nothing to show, with the cache age when known. */
        private fun placeholderNote(context: Context): String {
            val error = Prefs.lastError(context)
            if (error == null) return context.getString(R.string.widget_loading)
            val prefix = if (error == "offline") {
                context.getString(R.string.widget_offline)
            } else {
                context.getString(R.string.widget_no_data)
            }
            val updated = Prefs.lastSuccessMs(context)
            return if (updated > 0) {
                val date = PrayerModel.shortDate(updated, Locale.getDefault())
                "$prefix · ${context.getString(R.string.widget_updated, date)}"
            } else {
                prefix
            }
        }

        /** Tomorrow's Fajr from the prefetched next month, when today is the month's last day. */
        private fun nextMonthFirstDay(context: Context, zone: String): DayTimes? {
            val raw = Prefs.nextJson(context) ?: return null
            if (Prefs.nextZone(context) != zone) return null
            val month = PrayerApi.parseMonth(raw) ?: return null
            return month.day(1)
        }

        private fun name(context: Context, key: PrayerKey): String = context.getString(
            when (key) {
                PrayerKey.FAJR -> R.string.prayer_fajr
                PrayerKey.DHUHR -> R.string.prayer_dhuhr
                PrayerKey.ASR -> R.string.prayer_asr
                PrayerKey.MAGHRIB -> R.string.prayer_maghrib
                PrayerKey.ISHA -> R.string.prayer_isha
            }
        )
    }
}
