package app.drivepal

import android.content.Context

/** Release builds have no simulated drive. */
object DebugDrive {
    const val available = false

    fun start(context: Context): String? = "Not available in release builds"

    fun stop(context: Context) = Unit
}
