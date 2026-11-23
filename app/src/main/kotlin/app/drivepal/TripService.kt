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
import app.drivepal.core.ChatClient
import app.drivepal.core.LoggedStory
import app.drivepal.core.OfflineAreas
import app.drivepal.core.PackFirst
import app.drivepal.core.PackShelf
import app.drivepal.core.Shelf
import app.drivepal.core.TripLog
import app.drivepal.core.Fix
import app.drivepal.core.Position
import app.drivepal.core.SourceLibrary
import app.drivepal.core.StoryOutcome
import app.drivepal.core.StoryWriter
import app.drivepal.core.SubjectFinder
import app.drivepal.core.TripMonitor
import app.drivepal.core.TripState
import app.drivepal.core.Utterance
import java.io.IOException
import java.util.concurrent.Executors

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
    private var narrator: Narrator? = null
    private var tripLog: TripLog? = null
    private var packDatabase: AndroidPackDatabase? = null
    private val packOpener = Executors.newSingleThreadExecutor()
    private var tripNumber = 0 // which Trip a pack being opened belongs to

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
        val voice = Narrator(
            context = applicationContext,
            isRelevant = { exploration?.isRelevant(it.id) ?: false },
            onOutcome = ::noteOutcome,
            onPlayPressed = ::onPlayPressed,
        )
        narrator = voice
        val preferences = AppPreferences(applicationContext)
        val storage = AppStorage.of(applicationContext)
        val connection = storage.aiConnection()
        val problems = connection.settings.problems(connection.apiKey)
        // Settings are read once per Trip: a change in Settings applies from the next Trip.
        val writer = StoryWriter(ChatClient(AiHttp { connection }, connection.settings.chatSettings()))
        tripLog = TripLog.load(storage.tripLog).startTrip(System.currentTimeMillis())
        shownState = TripState.Driving
        TripSession.update {
            TripUi(running = true, state = TripState.Driving, notice = problems.firstOrNull()?.let { "AI settings: $it" })
        }
        handler.postDelayed(tick, TICK_MILLIS)

        // Opening the Region pack copies and checksums megabytes, so it is done off the main thread. Until it is open
        // there is no Exploration: the first GPS fixes of the Trip (well under a second's worth) are only counted
        // for speed.
        val trip = ++tripNumber
        packOpener.execute {
            val (shelf, database) = try {
                openShelf()
            } catch (e: IOException) {
                // Copying the bundled pack failed (a full disk, say): the Trip goes online rather than not at all.
                Shelf(pack = null, notice = "The Region pack could not be opened, so online maps are used") to null
            }
            handler.post {
                if (trip != tripNumber || monitor == null) {
                    database?.close() // the Trip ended while the pack was being opened
                    return@post
                }
                packDatabase = database
                exploration = Exploration(
                    places = shelf.pack?.let { PackFirst(it, finder) } ?: finder,
                    sources = sources,
                    writer = writer,
                    narrator = voice,
                    store = storage.told,
                    talkativeness = { preferences.talkativeness },
                    offline = shelf.pack?.let(::OfflineAreas),
                )
                TripSession.update { it.copy(offline = shelf.notice) }
            }
        }
    }

    /**
     * Finds the Region pack, on the pack opener thread. A pack copied into Android/data/app.drivepal/files/packs/ by
     * hand comes first, then the one that comes with the app. Returns the database of the pack in use, to be closed.
     */
    private fun openShelf(): Pair<Shelf, AndroidPackDatabase?> {
        var opened: AndroidPackDatabase? = null
        val shelf = PackShelf.open(listOf(packsFolder(), BundledPacks.unpack(applicationContext))) { file ->
            opened?.close() // a pack opened but then refused (an unknown format inside) is closed before the next is tried
            AndroidPackDatabase(file).also { opened = it }
        }
        if (shelf.pack == null) opened?.close()
        return shelf to opened?.takeIf { shelf.pack != null }
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
        exploration?.onPosition(Position(location.latitude, location.longitude), standstill = monitor?.state == TripState.Standstill)
    }

    private fun answerStillOnTheRoad() {
        val current = monitor
        if (current == null) {
            stopSelf()
            return
        }
        applyMonitor(current.withPlayPressed(SystemClock.elapsedRealtime()))
    }

    /** The media *play* button (steering wheel, headset): answers the question if one is waiting, otherwise replays the last Story. */
    private fun onPlayPressed() {
        if (monitor?.state == TripState.AskingStillOnTheRoad) answerStillOnTheRoad() else narrator?.replay()
    }

    private fun noteOutcome(utterance: Utterance, outcome: StoryOutcome) {
        exploration?.onOutcome(utterance, outcome)
        LoggedStory.ofOrNull(utterance, outcome, System.currentTimeMillis())?.let { tripLog = tripLog?.recording(it) }
        TripSession.update { ui -> ui.copy(narration = (listOf("${outcome.name}: ${utterance.title}") + ui.narration).take(MAX_NARRATION)) }
    }

    private fun applyMonitor(next: TripMonitor) {
        if (next.state == TripState.AskingStillOnTheRoad && monitor?.state != TripState.AskingStillOnTheRoad) {
            narrator?.ask(Utterance("question", QUESTION, "en", story = false))
        }
        narrator?.setQuestionPending(next.state == TripState.AskingStillOnTheRoad)
        if (monitor?.state == TripState.AskingStillOnTheRoad && next.state != TripState.AskingStillOnTheRoad) {
            narrator?.cancelQuestion() // answered, or the driver drove off: the question must not be spoken now
        }
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

    /** Where Region packs are copied to (adb push or a USB file transfer); no permission is needed to read it. */
    private fun packsFolder() = getExternalFilesDir(PACKS_FOLDER) ?: java.io.File(filesDir, PACKS_FOLDER)

    private fun closePack() {
        packDatabase?.close()
        packDatabase = null
    }

    private fun endTrip() {
        handler.removeCallbacks(tick)
        tripLog?.endTrip(System.currentTimeMillis())
        tripLog = null
        exploration?.stop()
        exploration = null
        closePack()
        narrator?.release()
        narrator = null
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
        tripLog?.endTrip(System.currentTimeMillis())
        exploration?.stop()
        closePack()
        packOpener.shutdown() // a pack still being opened is closed when it is ready, as the Trip has ended
        narrator?.release()
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
        private const val MAX_NARRATION = 6
        private const val PACKS_FOLDER = "packs"
        private const val QUESTION = "Still on the road? Press play to keep going."

        // Process-wide so that what has been looked up is remembered from one Trip to the next.
        private val finder = SubjectFinder(OverpassHttp, SystemClock::elapsedRealtime, WikipediaGeoSearchHttp)
        private val sources = SourceLibrary(WikipediaHttp, WikidataHttp, SystemClock::elapsedRealtime)
    }
}
