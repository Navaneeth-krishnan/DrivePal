package app.drivepal

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Debug builds only: drives a test route past Kinderdijk by feeding fake GPS fixes, in real time, so the whole
 * app can be tried on a phone at a desk. Needs the app to be the mock location app, which this allows once:
 * `adb shell appops set app.drivepal android:mock_location allow`
 */
object DebugDrive {
    const val available = true

    private const val START_LATITUDE = 51.865
    private const val LONGITUDE = 4.6403
    private const val SPEED_METERS_PER_SECOND = 100 / 3.6
    private const val STEP_SECONDS = 5
    private const val STEPS = 48 // 240 s: due north through Kinderdijk, starting among the subjects
    private const val METERS_PER_DEGREE = 111_195.0

    private val handler = Handler(Looper.getMainLooper())
    private var step = 0
    private var running: Runnable? = null

    /** Returns a message if the drive could not start. */
    // ProviderProperties.* only exists from Android 12; Criteria.* has the same values on every version we support.
    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    fun start(context: Context): String? {
        stop(context)
        val manager = context.getSystemService(LocationManager::class.java)
        try {
            manager.addTestProvider(
                LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE,
            )
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        } catch (e: SecurityException) {
            return "Allow mock locations first: adb shell appops set app.drivepal android:mock_location allow"
        }
        step = 0
        val tick = object : Runnable {
            override fun run() {
                if (step >= STEPS) return stop(context)
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix(step))
                step++
                handler.postDelayed(this, STEP_SECONDS * 1000L)
            }
        }
        running = tick
        handler.post(tick)
        return null
    }

    fun stop(context: Context) {
        running?.let(handler::removeCallbacks)
        running = null
        runCatching {
            context.getSystemService(LocationManager::class.java).removeTestProvider(LocationManager.GPS_PROVIDER)
        }
    }

    private fun fix(step: Int) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = START_LATITUDE + step * STEP_SECONDS * SPEED_METERS_PER_SECOND / METERS_PER_DEGREE
        longitude = LONGITUDE
        speed = SPEED_METERS_PER_SECOND.toFloat()
        bearing = 0f
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
}
