package app.drivepal

import android.content.Context

/** Release builds have no simulated drive. */
object DebugDrive {
    val routes: List<String> = emptyList()

    fun start(context: Context, routeName: String): String? = "Not available in release builds"

    fun stop(context: Context) = Unit
}
