package app.drivepal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.drivepal.core.Fix
import app.drivepal.core.Position
import app.drivepal.core.SourceLibrary
import app.drivepal.core.SubjectFinder
import app.drivepal.core.TripMonitor
import app.drivepal.core.TripState

/**
 * Keeps a Trip going while the screen is off: GPS in, [TripMonitor] decisions out.
 * Every time given to the monitor comes from the monotonic clock ([SystemClock.elapsedRealtime]),
 * so a wrong phone clock cannot confuse it.
 */
class TripService : Service() {

    private lateinit var locationManager: LocationManager
    private var monitor: TripMonitor? = null
    private var shownState: TripState? = null
    private val handler = Handler(Looper.getMainLooper())

    private var exploration: Exploration? = null

    // An object, not a lambda: on Android 10 the other callbacks are abstract.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        override fun onProviderEnabled(provider: String) =
            TripSession.update { it.copy(notice = null) }

        override fun onProviderDisabled(provider: String) =
            TripSession.update { it.copy(notice = "GPS is off") }

        @Deprecated("Only called on Android 10 and older")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    /** Moves time forward even when no fix arrives (parked indoors, GPS lost). */
    private val tick = object : Runnable {
        override fun run() {
            val current = monitor ?: return
            applyMonitor(current.withTime(SystemClock.elapsedRealtime()))
            if (monitor != null) handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> endTrip()
            ACTION_STILL_DRIVING -> answerStillOnTheRoad()
            else -> beginTrip()
        }
        return START_NOT_STICKY
    }

    private fun beginTrip() {
        createChannels()
        // Always promote to the foreground first: Android requires it after startForegroundService.
        startForeground(
            NOTIFICATION_ID,
            notification(TripState.Driving),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        if (monitor != null) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            refuse("DrivePal needs precise location to follow your drive")
            return
        }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MILLIS,
                0f,
                locationListener,
                Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            refuse("Location permission was withdrawn")
            return
        } catch (e: IllegalArgumentException) {
            refuse("This device has no GPS")
            return
        }
        monitor = TripMonitor.start(SystemClock.elapsedRealtime())
        exploration = Exploration(finder, sources)
        shownState = TripState.Driving
        TripSession.update { TripUi(running = true, state = TripState.Driving) }
        handler.postDelayed(tick, TICK_MILLIS)
    }

    private fun onLocation(location: Location) {
        val current = monitor ?: return
        if (!location.hasSpeed()) return
        val speedKmh = location.speed * MS_TO_KMH
        val fixMillis = location.elapsedRealtimeNanos / NANOS_PER_MILLI
        TripSession.update {
            it.copy(speedKmh = speedKmh)
        }
        applyMonitor(current.withFix(Fix(fixMillis, speedKmh)))
        exploration?.onPosition(Position(location.latitude, location.longitude))
    }

    private fun answerStillOnTheRoad() {
        val current = monitor
        if (current == null) {
            stopSelf()
            return
        }
        applyMonitor(current.withPlayPressed(SystemClock.elapsedRealtime()))
    }

    private fun applyMonitor(next: TripMonitor) {
        monitor = next
        TripSession.update { it.copy(state = next.state) }
        if (next.state == TripState.Ended) {
            notifyTripEnded()
            endTrip()
        } else if (next.state != shownState) {
            shownState = next.state
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(next.state))
        }
    }

    private fun endTrip() {
        handler.removeCallbacks(tick)
        exploration?.stop()
        exploration = null
        if (monitor != null) locationManager.removeUpdates(locationListener)
        monitor = null
        shownState = null
        TripSession.update { TripUi(running = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refuse(notice: String) {
        TripSession.update { TripUi(notice = notice) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        exploration?.stop()
        handler.removeCallbacks(tick)
        if (monitor != null) locationManager.removeUpdates(locationListener)
        monitor = null
        TripSession.update { TripUi(notice = it.notice) }
        super.onDestroy()
    }

    private fun notification(state: TripState): Notification {
        val asking = state == TripState.AskingStillOnTheRoad
        val text = when (state) {
            TripState.Driving -> "Trip running"
            TripState.Standstill -> "Standing still"
            TripState.AskingStillOnTheRoad -> "Still on the road?"
            TripState.Ended -> "Trip ended"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, if (asking) QUESTION_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("DrivePal")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (asking) {
            val keepGoing = PendingIntent.getService(
                this, 1,
                Intent(this, TripService::class.java).setAction(ACTION_STILL_DRIVING),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Keep going", keepGoing)
        }
        return builder.build()
    }

    private fun notifyTripEnded() {
        val ended = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("DrivePal")
            .setContentText("Trip ended: no answer to \"Still on the road?\"")
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ENDED_NOTIFICATION_ID, ended)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Trip", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(QUESTION_CHANNEL_ID, "Still on the road?", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val ACTION_STOP = "app.drivepal.STOP"
        const val ACTION_STILL_DRIVING = "app.drivepal.STILL_DRIVING"
        private const val CHANNEL_ID = "trip"
        private const val QUESTION_CHANNEL_ID = "question"
        private const val NOTIFICATION_ID = 1
        private const val ENDED_NOTIFICATION_ID = 2
        private const val LOCATION_INTERVAL_MILLIS = 5_000L
        private const val TICK_MILLIS = 10_000L
        private const val MS_TO_KMH = 3.6
        private const val NANOS_PER_MILLI = 1_000_000L

        // Process-wide so that what has been looked up is remembered from one Trip to the next.
        private val finder = SubjectFinder(OverpassHttp, SystemClock::elapsedRealtime)
        private val sources = SourceLibrary(WikipediaHttp, WikidataHttp, SystemClock::elapsedRealtime)
    }
}
