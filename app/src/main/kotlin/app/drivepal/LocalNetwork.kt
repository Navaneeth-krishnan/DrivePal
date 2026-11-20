package app.drivepal

import android.content.Context
import app.drivepal.core.LocalAddress
import java.net.URI

/**
 * True when the configured AI server is a computer on the local network (such as Ollama on a laptop).
 * Android 17 only lets an app reach those addresses if the driver grants it the local network permission.
 */
fun aiServerIsOnLocalNetwork(context: Context): Boolean =
    runCatching { LocalAddress.isLocal(URI(AppStorage.of(context).aiConnection().settings.baseUrl.trim()).host.orEmpty()) }
        .getOrDefault(false)
