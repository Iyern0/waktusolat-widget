package com.waktusolat.widget

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
                // The user just granted exact alarms — re-arm the chain and repaint.
                AlarmScheduler.ensureScheduled(this)
                AppUpdater.update(this, forceRefresh = true)
            }
        }
    }

    /** Android 12+ gates setAlarmClock behind a user grant this app never asked for before. */
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
            // Some OEMs hide the exact-alarm screen; the app details page is the fallback.
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
    var zones by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }

    val locPerms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            status = "Locating…"
            scope.launch { resolveGps(context, onDone = { code, name ->
                zone = code; zoneName = name
                AppUpdater.update(context, forceRefresh = true)
                status = null
            }, onError = { status = it }) }
        } else {
            status = "Location permission denied — pick a zone manually below."
        }
    }

    val darkColors = darkColorScheme(
        primary = Color(0xFFFFD54F),
        onPrimary = Color.Black,
        background = Color(0xFF000000),
        surface = Color(0xFF141414),
        onBackground = Color.White,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF1E1E1E),
        onSurfaceVariant = Color(0xFFAAAAAA),
    )

    MaterialTheme(colorScheme = darkColors) {
        Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Waktu Solat") }) }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
                Text(
                    text = "Location: ${zoneName ?: zone ?: "not set"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        locPerms.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use GPS location") }
                status?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                if (!exactAlarmsAllowed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Exact alarms are off, so Android throttles the widget — the countdown can freeze for minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFB74D),
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onFixExactAlarms, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow exact alarms")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Or pick a zone manually:", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                if (zones.isEmpty()) {
                    Button(
                        onClick = { scope.launch {
                            zones = PrayerApi.fetchZones()
                            if (zones.isEmpty()) status = "Could not load zone list. Check internet."
                        } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Load zone list") }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(zones) { (code, name) ->
                            ListItem(
                                headlineContent = { Text(name, maxLines = 1) },
                                supportingContent = { Text(code) },
                                trailingContent = { if (code == zone) Text("✓") },
                                modifier = Modifier.clickable {
                                    Prefs.saveZone(context, code, name, "manual")
                                    zone = code; zoneName = name
                                    AppUpdater.update(context, forceRefresh = true)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission") // permission checked by caller before invoking
private suspend fun resolveGps(
    context: Context,
    onDone: (code: String, name: String?) -> Unit,
    onError: (String) -> Unit,
) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = when {
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> null
    }
    if (provider == null) {
        onError("No location provider. Turn on GPS/WiFi.")
        return
    }

    // Request a fresh location — getLastKnownLocation returns null on emulators.
    val loc = withTimeoutOrNull(10_000L) {
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }
            }
            // requestLocationUpdates(provider, 0ms, 0m) fires on next available fix.
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }
    }

    if (loc == null) {
        onError("Location timed out. On emulator: set GPS via Extended Controls > Location. Or pick zone manually.")
        return
    }
    val body = PrayerApi.fetchMonthByGps(loc.latitude, loc.longitude)
    val month = body?.let { PrayerApi.parseMonth(it) }
    if (month == null) {
        onError("GPS lookup failed. Check internet, or pick a zone manually.")
        return
    }
    Prefs.saveMonth(context, body, month.year, month.monthNum)
    val name = PrayerApi.fetchZones().firstOrNull { it.first == month.zone }?.second
    Prefs.saveZone(context, month.zone, name ?: month.zone, "gps")
    onDone(month.zone, name)
}
