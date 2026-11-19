package app.drivepal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.drivepal.core.TripState

/** What the screen shows about the current Trip. Replaced as a whole on every update. */
data class TripUi(
    val running: Boolean = false,
    val state: TripState? = null,
    val speedKmh: Double? = null,
    val headingDegrees: Float? = null,
    /** Something the driver needs to know, such as "GPS is off". */
    val notice: String? = null,
    /** Why the places around the driver could not be looked up right now, if that is so. */
    val problem: String? = null,
    /** What DrivePal has noticed along the way, newest first. */
    val happenings: List<String> = emptyList(),
)

/** Process-wide holder through which [TripService] tells the screen what is happening. Main thread only. */
object TripSession {
    var ui by mutableStateOf(TripUi())
        private set

    fun update(change: (TripUi) -> TripUi) {
        ui = change(ui)
    }
}
