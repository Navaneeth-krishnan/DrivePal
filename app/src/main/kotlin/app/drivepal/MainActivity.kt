package app.drivepal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private enum class Screen { Main, TripLog, Settings }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Three screens are too few to need a navigation library.
                    var screen by rememberSaveable { mutableStateOf(Screen.Main) }
                    BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }
                    when (screen) {
                        Screen.Main -> MainScreen(
                            TripSession.ui,
                            openTripLog = { screen = Screen.TripLog },
                            openSettings = { screen = Screen.Settings },
                        )
                        Screen.TripLog -> TripLogScreen(back = { screen = Screen.Main })
                        Screen.Settings -> SettingsScreen(back = { screen = Screen.Main })
                    }
                }
            }
        }
    }
}

// Android 12+ ignores a request for FINE location unless COARSE is requested with it.
internal fun neededPermissions(context: Context): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT >= ANDROID_17 && aiServerIsOnLocalNetwork(context)) add(ACCESS_LOCAL_NETWORK)
}.toTypedArray()

private const val ANDROID_17 = 37
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

internal fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

internal fun startTrip(context: Context) {
    context.startForegroundService(Intent(context, TripService::class.java))
}

internal fun send(context: Context, action: String) {
    context.startService(Intent(context, TripService::class.java).setAction(action))
}
