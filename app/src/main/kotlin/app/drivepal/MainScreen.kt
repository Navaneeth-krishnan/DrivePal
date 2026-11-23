package app.drivepal

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
fun MainScreen(ui: TripUi, openTripLog: () -> Unit, openSettings: () -> Unit) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = openTripLog) { Text("Trip log") }
            OutlinedButton(onClick = openSettings) { Text("Settings") }
        }
        Text(describe(ui), style = MaterialTheme.typography.bodyLarge)
        val preferences = remember { AppPreferences(context) }
        var talkativeness by remember { mutableStateOf(preferences.talkativeness) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Talkativeness.entries.forEach { choice ->
                FilterChip(
                    selected = talkativeness == choice,
                    onClick = { talkativeness = choice; preferences.talkativeness = choice },
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
        ui.offline?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
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
            OutlinedButton(onClick = { send(context, TripService.ACTION_STOP) }) { Text("Stop Trip") }
        } else {
            Button(onClick = { permissions.launch(neededPermissions(context)) }) { Text("Start Trip") }
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
