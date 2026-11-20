package app.drivepal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.drivepal.core.LoggedStory
import app.drivepal.core.LoggedTrip
import app.drivepal.core.TripLog
import java.text.DateFormat
import java.util.Date

/** The last Trips and what was told on them, each Story with the Source it was based on. */
@Composable
fun TripLogScreen(back: () -> Unit) {
    val storage = AppStorage.of(LocalContext.current)
    var log by remember { mutableStateOf(TripLog.load(storage.tripLog)) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.statusBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = back) { Text("Back") }
            if (log.trips.isNotEmpty()) {
                OutlinedButton(onClick = {
                    if (confirmClear) { log = log.cleared(); confirmClear = false } else confirmClear = true
                }) { Text(if (confirmClear) "Really clear?" else "Clear trip log") }
            }
        }
        Text("Trip log", style = MaterialTheme.typography.headlineMedium)
        if (log.trips.isEmpty()) Text("No Stories yet. They show up here after a Trip.")
        log.trips.forEach { TripEntry(it) }
    }
}

@Composable
private fun TripEntry(trip: LoggedTrip) {
    var open by remember { mutableStateOf(false) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${format.format(Date(trip.startedMillis))}: ${trip.stories.size} Stories",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clickable { open = !open },
        )
        if (open) trip.stories.forEach { StoryEntry(it) }
    }
}

@Composable
private fun StoryEntry(story: LoggedStory) {
    val links = LocalUriHandler.current
    Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${story.title} (${story.outcome.name})", style = MaterialTheme.typography.titleSmall)
        Text(story.text, style = MaterialTheme.typography.bodyMedium)
        story.sourceUrl?.let { url ->
            TextButton(onClick = { runCatching { links.openUri(url) } }) { Text("Source: ${story.sourceTitle}") }
        }
    }
}
