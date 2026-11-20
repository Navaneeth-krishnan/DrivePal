package app.drivepal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.drivepal.core.Talkativeness
import app.drivepal.core.TripState
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TripScreen(TripSession.ui)
                }
            }
        }
    }
}

@Composable
private fun TripScreen(ui: TripUi) {
    val context = LocalContext.current
    var locationDenied by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        locationDenied = granted[Manifest.permission.ACCESS_FINE_LOCATION] != true
        if (!locationDenied) startTrip(context)
    }

    Column(
        modifier = Modifier.statusBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DrivePal", style = MaterialTheme.typography.headlineMedium)
        Text(describe(ui), style = MaterialTheme.typography.bodyLarge)
        val settings = remember { Settings(context) }
        var talkativeness by remember { mutableStateOf(settings.talkativeness) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Talkativeness.entries.forEach { choice ->
                FilterChip(
                    selected = talkativeness == choice,
                    onClick = { talkativeness = choice; settings.talkativeness = choice },
                    label = { Text(choice.name) },
                )
            }
        }
        Text(
            "Speed: ${ui.speedKmh?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "-"}",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Heading: ${ui.headingDegrees?.let { String.format(Locale.US, "%.0f°", it) } ?: "-"}",
            style = MaterialTheme.typography.titleLarge,
        )
        ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ui.problem?.let { Text("Place lookup: $it", color = MaterialTheme.colorScheme.error) }
        if (locationDenied) {
            Text(
                "DrivePal needs precise location (not just approximate) to follow your drive.",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open settings") }
        }
        if (ui.state == TripState.AskingStillOnTheRoad) {
            Button(onClick = { send(context, TripService.ACTION_STILL_DRIVING) }) {
                Text("I'm still on the road")
            }
        }
        if (ui.running) {
            DebugDrive.routes.forEach { route ->
                OutlinedButton(onClick = {
                    DebugDrive.start(context, route)?.let { message -> TripSession.update { it.copy(notice = message) } }
                }) { Text("Simulate: $route (debug)") }
            }
        }
        if (ui.running) {
            OutlinedButton(onClick = { send(context, TripService.ACTION_STOP) }) { Text("Stop Trip") }
        } else {
            Button(onClick = { permissions.launch(neededPermissions()) }) { Text("Start Trip") }
        }
        ui.narration.forEach { Text(it, style = MaterialTheme.typography.titleMedium) }
        ui.stories.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        ui.happenings.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun describe(ui: TripUi): String = when {
    !ui.running -> "No Trip running"
    ui.state == TripState.Standstill -> "Trip running: standing still"
    ui.state == TripState.AskingStillOnTheRoad -> "Still on the road?"
    else -> "Trip running"
}

// Android 12+ ignores a request for FINE location unless COARSE is requested with it.
private fun neededPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT >= ANDROID_17 && aiServerIsOnLocalNetwork()) add(ACCESS_LOCAL_NETWORK)
}.toTypedArray()

private const val ANDROID_17 = 37
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

private fun startTrip(context: Context) {
    context.startForegroundService(Intent(context, TripService::class.java))
}

private fun send(context: Context, action: String) {
    context.startService(Intent(context, TripService::class.java).setAction(action))
}
