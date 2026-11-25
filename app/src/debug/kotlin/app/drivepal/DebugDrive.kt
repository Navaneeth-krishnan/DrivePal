package app.drivepal

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Debug builds only: drives a test route by feeding fake GPS fixes, in real time, so the whole app can be tried
 * on a phone at a desk. Needs the app to be the mock location app, which is allowed once with
 * `adb shell appops set app.drivepal.debug android:mock_location allow`
 */
object DebugDrive {
    val routes: List<String> = DebugRoutes.all.map { it.name }

    private const val STEP_SECONDS = 5
    private const val VARIATION_PERIOD_SECONDS = 40.0
    private const val MIN_MOVING_KMH = 8.0
    private const val PARKED_SECONDS = 600
    private const val METERS_PER_DEGREE = 111_195.0

    private val handler = Handler(Looper.getMainLooper())
    private var running: Runnable? = null

    /** Returns a message if the drive could not start. */
    // ProviderProperties.* only exists from Android 12; Criteria.* has the same values on every version we support.
    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    fun start(context: Context, routeName: String): String? {
        val route = DebugRoutes.all.firstOrNull { it.name == routeName } ?: return "No such route: $routeName"
        stop(context)
        val manager = context.getSystemService(LocationManager::class.java)
        try {
            manager.addTestProvider(
                LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE,
            )
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        } catch (e: SecurityException) {
            return "Allow mock locations first: adb shell appops set app.drivepal.debug android:mock_location allow"
        }
        val path = Path(route.points)
        var distance = 0.0
        var seconds = 0
        var jamsDone = 0
        var jamSecondsLeft = 0
        var parkedSeconds = 0
        val tick = object : Runnable {
            override fun run() {
                val arrived = distance >= path.length
                // A real car that stops keeps its GPS on: park at the end of the route for a while.
                if (arrived && parkedSeconds >= PARKED_SECONDS) return stop(context)
                val nextJam = route.jams.getOrNull(jamsDone)
                if (jamSecondsLeft == 0 && nextJam != null && distance >= nextJam.atFraction * path.length) {
                    jamSecondsLeft = nextJam.seconds
                    jamsDone++
                }
                val speed = when {
                    arrived || jamSecondsLeft > 0 -> 0.0
                    else -> (route.speedKmh + route.speedVariationKmh * sin(seconds / VARIATION_PERIOD_SECONDS)).coerceAtLeast(MIN_MOVING_KMH) / 3.6
                }
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix(path, distance.coerceAtMost(path.length), speed))
                if (arrived) parkedSeconds += STEP_SECONDS
                if (jamSecondsLeft > 0) jamSecondsLeft = (jamSecondsLeft - STEP_SECONDS).coerceAtLeast(0)
                distance += speed * STEP_SECONDS
                seconds += STEP_SECONDS
                handler.postDelayed(this, STEP_SECONDS * 1000L)
            }
        }
        running = tick
        handler.post(tick)
        return null
    }

    /**
     * Ends the simulated drive and hands the GPS back to the real receiver. Also removes a simulation left behind by an
     * earlier run of the app, so it is called whenever a Trip starts or ends.
     */
    fun stop(context: Context) {
        running?.let(handler::removeCallbacks)
        running = null
        runCatching {
            context.getSystemService(LocationManager::class.java).removeTestProvider(LocationManager.GPS_PROVIDER)
        }
    }

    private fun fix(path: Path, distance: Double, speedMetersPerSecond: Double) = Location(LocationManager.GPS_PROVIDER).apply {
        val point = path.at(distance)
        latitude = point.latitude
        longitude = point.longitude
        speed = speedMetersPerSecond.toFloat()
        bearing = point.bearing
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private class Point(val latitude: Double, val longitude: Double, val bearing: Float)

    /** A polyline walked by distance. Flat-earth maths is plenty at the few kilometres a route covers. */
    private class Path(private val points: DoubleArray) {
        private val count = points.size / 2
        private val cumulative = DoubleArray(count).also { total ->
            for (i in 1 until count) total[i] = total[i - 1] + segmentLength(i - 1)
        }
        val length: Double get() = cumulative.last()

        fun at(distance: Double): Point {
            val i = (1 until count).firstOrNull { cumulative[it] >= distance } ?: (count - 1)
            val from = i - 1
            val along = ((distance - cumulative[from]) / (cumulative[i] - cumulative[from]).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
            val lat = points[2 * from] + (points[2 * i] - points[2 * from]) * along
            val lon = points[2 * from + 1] + (points[2 * i + 1] - points[2 * from + 1]) * along
            val north = points[2 * i] - points[2 * from]
            val east = (points[2 * i + 1] - points[2 * from + 1]) * cos(Math.toRadians(lat))
            val bearing = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
            return Point(lat, lon, bearing.toFloat())
        }

        private fun segmentLength(from: Int): Double {
            val north = (points[2 * from + 2] - points[2 * from]) * METERS_PER_DEGREE
            val east = (points[2 * from + 3] - points[2 * from + 1]) * METERS_PER_DEGREE * cos(Math.toRadians(points[2 * from]))
            return hypot(north, east)
        }
    }
}
