package com.waktusolat.widget

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private var exactAlarmsAllowed by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmScheduler.ensureScheduled(this)
        exactAlarmsAllowed = AlarmScheduler.canScheduleExact(this)
        if (Prefs.zone(this) != null) AppUpdater.update(this)
        setContent { AppScreen(exactAlarmsAllowed, onFixExactAlarms = ::requestExactAlarms) }
    }

    override fun onResume() {
        super.onResume()
        val allowed = AlarmScheduler.canScheduleExact(this)
        if (allowed != exactAlarmsAllowed) {
            exactAlarmsAllowed = allowed
            if (allowed) {
                // Granted while we were away: re-arm and repaint immediately.
                AlarmScheduler.ensureScheduled(this)
                AppUpdater.update(this, forceRefresh = true)
            }
        }
    }

    /** Android 12+ gates setAlarmClock/setExact behind a user grant. */
    private fun requestExactAlarms() {
        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName"),
                    )
                )
            } else {
                startActivity(appDetails)
            }
        } catch (e: Exception) {
            // Some OEMs hide the exact-alarm screen; app details is the fallback.
            startActivity(appDetails)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(exactAlarmsAllowed: Boolean, onFixExactAlarms: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var zone by remember { mutableStateOf(Prefs.zone(context)) }
    var zoneName by remember { mutableStateOf(Prefs.zoneName(context)) }
    var zones by remember { mutableStateOf<List<Zone>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var notify by remember { mutableStateOf(Prefs.notifyEnabled(context)) }

    LaunchedEffect(Unit) {
        zones = ZoneCatalogue.load(context)
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableNotifications(context)
            notify = true
        } else {
            status = context.getString(R.string.setup_denied)
        }
    }

    val locationPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            status = context.getString(R.string.setup_locating)
            scope.launch {
                val outcome = resolveByGps(context)
                status = outcome.message
                if (outcome.ok) {
                    zone = Prefs.zone(context)
                    zoneName = Prefs.zoneName(context)
                }
            }
        } else {
            status = context.getString(R.string.setup_denied)
        }
    }

    val colors = darkColorScheme(
        primary = Color(0xFFFFD54F),
        onPrimary = Color.Black,
        background = Color(0xFF000000),
        surface = Color(0xFF141414),
        onBackground = Color.White,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF1E1E1E),
        onSurfaceVariant = Color(0xFFAAAAAA),
    )

    val filtered = remember(query, zones) {
        if (query.isBlank()) zones
        else zones.filter {
            it.name.contains(query, ignoreCase = true) || it.code.contains(query, ignoreCase = true)
        }
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Waktu Solat") }) }) { pad ->
            Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
                Text(
                    text = context.getString(
                        R.string.setup_location,
                        zoneName ?: zone ?: context.getString(R.string.setup_not_set),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            locationPermissions.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(context.getString(R.string.setup_use_gps)) }

                    Button(
                        onClick = {
                            scope.launch {
                                status = context.getString(R.string.setup_locating)
                                val ok = AppUpdater.refreshNow(context)
                                PrayerWidget.updateAll(context)
                                status = if (ok) null else Prefs.lastError(context)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(context.getString(R.string.setup_refresh)) }
                }

                status?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB74D))
                }

                if (!exactAlarmsAllowed) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        context.getString(R.string.setup_exact_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFB74D),
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onFixExactAlarms, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(R.string.setup_allow_exact))
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(context.getString(R.string.setup_notify), style = MaterialTheme.typography.titleSmall)
                        Text(
                            context.getString(R.string.setup_notify_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA),
                        )
                    }
                    Switch(
                        checked = notify,
                        onCheckedChange = { on ->
                            if (!on) {
                                Prefs.setNotifyEnabled(context, false)
                                notify = false
                                AlarmScheduler.ensureScheduled(context)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                enableNotifications(context)
                                notify = true
                            }
                        },
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    statusLine(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888),
                )

                Spacer(Modifier.height(12.dp))
                Text(context.getString(R.string.setup_pick_zone), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(context.getString(R.string.setup_search_zone)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))

                if (zones.isEmpty()) {
                    Text(context.getString(R.string.setup_zones_failed), color = Color(0xFFFFB74D))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(filtered) { z ->
                            ListItem(
                                headlineContent = { Text(z.name, maxLines = 1) },
                                supportingContent = { Text(z.code) },
                                trailingContent = { if (z.code == zone) Text("✓") },
                                modifier = Modifier.clickable { selectZone(context, z) { code, name -> zone = code; zoneName = name } },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun selectZone(context: Context, z: Zone, onApplied: (String, String) -> Unit) {
    if (z.code != Prefs.zone(context)) {
        // A different city must never inherit the old city's cached month.
        Prefs.clearMonths(context)
    }
    Prefs.saveZone(context, z.code, z.name, "manual")
    onApplied(z.code, z.name)
    AppUpdater.update(context, forceRefresh = true)
    AlarmScheduler.ensureScheduled(context)
}

private fun enableNotifications(context: Context) {
    Prefs.setNotifyEnabled(context, true)
    AlarmScheduler.ensureScheduled(context)
}

private fun statusLine(context: Context): String {
    val error = Prefs.lastError(context)
    return if (error != null) {
        context.getString(R.string.setup_last_error, error)
    } else {
        val updated = Prefs.lastSuccessMs(context)
        if (updated <= 0) context.getString(R.string.setup_tap_to_enable)
        else context.getString(R.string.setup_updated, PrayerModel.shortDate(updated, Locale.getDefault()))
    }
}

private data class GpsOutcome(val ok: Boolean, val message: String?)

@SuppressLint("MissingPermission") // permission checked by the caller before invoking
private suspend fun resolveByGps(context: Context): GpsOutcome {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = when {
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> null
    } ?: return GpsOutcome(false, "No location provider. Turn on GPS/WiFi.")

    val loc: Location? = withTimeoutOrNull(10_000L) {
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }
            }
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }
    }

    if (loc == null) {
        return GpsOutcome(false, "Location timed out. Pick a zone manually.")
    }

    return when (val result = PrayerApi.fetchMonthByGps(loc.latitude, loc.longitude)) {
        is ApiResult.Err -> GpsOutcome(false, "GPS lookup failed (${result.message}).")
        is ApiResult.Ok -> {
            val month = result.value.month
            if (month.zone != Prefs.zone(context)) Prefs.clearMonths(context)
            Prefs.saveMonth(context, result.value.raw, month.year, month.monthNum, month.zone)
            Prefs.saveLocation(context, loc.latitude, loc.longitude)
            val label = ZoneCatalogue.find(context, month.zone)?.name ?: month.zone
            Prefs.saveZone(context, month.zone, label, "gps")
            AppUpdater.update(context, forceRefresh = true)
            AlarmScheduler.ensureScheduled(context)
            GpsOutcome(true, null)
        }
    }
}
