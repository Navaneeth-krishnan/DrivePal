package app.drivepal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.drivepal.core.Area
import app.drivepal.core.AreaEvent
import app.drivepal.core.AreaTracker
import app.drivepal.core.PassingDetector
import app.drivepal.core.Pick
import app.drivepal.core.Position
import app.drivepal.core.Relation
import app.drivepal.core.SourceLibrary
import app.drivepal.core.SourceLookup
import app.drivepal.core.StoryBrief
import app.drivepal.core.StoryDirector
import app.drivepal.core.StoryKind
import app.drivepal.core.StoryOutcome
import app.drivepal.core.StoryResult
import app.drivepal.core.announcement
import app.drivepal.core.StoryScheduler
import app.drivepal.core.StoryWriter
import app.drivepal.core.Subject
import app.drivepal.core.SubjectFinder
import app.drivepal.core.SubjectKind
import app.drivepal.core.Talkativeness
import app.drivepal.core.ToldStore
import app.drivepal.core.Utterance
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Looks around the driver during a Trip and hands the work out. The decisions about what to tell live in
 * [StoryDirector] (tested in :core); this class is the threads and the Android plumbing around it. Each thread owns
 * its own state:
 *
 * - **main**: the [PassingDetector] (arithmetic on subjects already loaded, so every GPS fix is judged straight
 *   away, however slow the network is) and the [StoryDirector].
 * - **loader**: the [AreaTracker] and the [SubjectFinder] (Nominatim, Overpass and their fallbacks). Slow.
 * - **lookups**: Source lookups (Wikipedia, Wikidata) for subjects and Areas, and retries of the failed ones.
 * - **writers**: the AI server writing one Story. Slow, so it has its own thread.
 *
 * Stories are written just in time: when nothing is being written or spoken, the director picks the one best
 * candidate that is still relevant and not yet Told, and only then is the AI asked.
 */
class Exploration(
    private val finder: SubjectFinder,
    private val sources: SourceLibrary,
    private val writer: StoryWriter,
    private val narrator: Narrator,
    store: ToldStore,
    private val talkativeness: () -> Talkativeness,
    wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val lookups = Executors.newSingleThreadExecutor()
    private val writers = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false

    // Main thread:
    private var detector = PassingDetector.watching(emptyList())
    private val director = StoryDirector(
        store = store,
        scheduler = StoryScheduler(
            talkativeness(),
            startedMillis = SystemClock.elapsedRealtime(),
            interludeAfterMillis = BuildConfig.INTERLUDE_AFTER_SECONDS * 1000L,
        ),
        isPlaceRelevant = { id -> detector.relevantSubjects.any { it.id == id } },
        wallClockMillis = wallClockMillis,
    )
    private var writing = false
    private var warnedAboutSpeech = false

    // Loader thread:
    private var areaTracker = AreaTracker.starting(NominatimHttp, SystemClock::elapsedRealtime, PhotonHttp)
    private val loading = AtomicBoolean(false)

    // Lookups thread:
    private var retrying: List<Subject> = emptyList()
    private val retryCount = AtomicInteger(0)
    private val retryQueued = AtomicBoolean(false)

    /** Called on the main thread for every GPS position. [standstill] is true while the driver is barely moving. */
    fun onPosition(position: Position, standstill: Boolean) {
        if (stopped) return
        val update = detector.withPosition(position)
        detector = update.detector
        update.detector.headingDegrees?.let { heading ->
            show { it.copy(headingDegrees = heading.toFloat()) }
        }
        update.events.forEach { event ->
            if (hasNoSource(event.subject)) director.placeFound(event.subject) else lookups.execute { guarded { lookUp(event.subject) } }
        }
        retryFailedLookups()
        loadAround(position)
        conduct(standstill)
    }

    /** A Junction, or a village the map links to no article: only its name can be said. */
    private fun hasNoSource(subject: Subject) =
        subject.kind == SubjectKind.Junction || (subject.wikipediaTag == null && subject.wikidataId == null)

    /** Whether the Story with this id is still worth telling. Main thread only. */
    fun isRelevant(utteranceId: String): Boolean = director.isRelevant(utteranceId)

    /** What became of a Story that was told: remembers it, and starts the quiet gap. Main thread only. */
    fun onOutcome(utterance: Utterance, outcome: StoryOutcome) =
        director.storyEnded(utterance.id, utterance.title, outcome, SystemClock.elapsedRealtime())

    /** Stops all work. A request already on the wire finishes in the background and is ignored. */
    fun stop() {
        stopped = true
        loader.shutdownNow()
        lookups.shutdownNow()
        writers.shutdownNow()
    }

    /** Picks the next Story, if it is time, and starts writing it. */
    private fun conduct(standstill: Boolean) {
        if (narrator.speechBroken) {
            // No point asking the AI for Stories nobody can hear.
            if (!warnedAboutSpeech) show { it.copy(problem = "Speech is not available on this phone") }
            warnedAboutSpeech = true
            return
        }
        val pick = director.next(
            nowMillis = SystemClock.elapsedRealtime(),
            talkativeness = talkativeness(),
            busy = writing || narrator.isTelling || !narrator.canSpeak,
            standstill = standstill,
        ) ?: return
        startWriting(pick)
    }

    private fun startWriting(pick: Pick) {
        writing = true
        // Whether a place is ahead or behind is judged now, as its Story is about to be told.
        val relation = if (pick.kind == StoryKind.Town || pick.kind == StoryKind.Landmark || pick.kind == StoryKind.Junction) {
            if (detector.isAhead(pick.subject)) Relation.Ahead else Relation.Behind
        } else {
            null
        }
        val earlier = director.earlierTitles(EARLIER_STORIES_OFFERED)
        val talk = talkativeness()
        writers.execute {
            var utterance: Utterance? = null
            try {
                guarded { utterance = write(pick, relation, earlier, talk) }
            } finally {
                post {
                    utterance?.let(narrator::tell)
                    writing = false
                }
            }
        }
    }

    private fun write(pick: Pick, relation: Relation?, earlier: List<String>, talk: Talkativeness): Utterance? {
        val announced = announcement(pick.kind, pick.subject.name, relation)
        val source = pick.source
        if (pick.announceOnly || source == null) {
            // Names mode, or nothing to tell about this place: just say where we are, without asking the AI.
            return Utterance(pick.key, "", "en", title = pick.title, kind = pick.kind, announcement = announced)
        }
        val brief = StoryBrief(pick.subject.name, pick.kind, source, talk, relation, earlier)
        val result = writer.write(brief) ?: return null
        show { ui -> ui.copy(stories = (listOf(describeStory(pick.subject.copy(name = pick.title), result)) + ui.stories).take(MAX_STORIES)) }
        val (text, language) = when (result) {
            is StoryResult.Written -> result.text to "en"
            is StoryResult.Fallback -> result.text to result.language
        }
        return Utterance(
            pick.key, text, language, title = pick.title,
            kind = pick.kind, sourceLanguage = pick.articleLanguage, sourceTitle = pick.articleTitle,
            // The announcement is English: not read out in front of an article that fell back to another language.
            announcement = announced.takeIf { language == "en" },
        )
    }

    private fun loadAround(position: Position) {
        if (!loading.compareAndSet(false, true)) return
        loader.execute {
            try {
                guarded {
                    val area = areaTracker.withPosition(position)
                    areaTracker = area.tracker
                    show { ui -> ui.copy(happenings = add(ui.happenings, area.events.map(::describe)), problem = area.problem) }
                    post { onArea(area.area, area.events) }

                    val found = finder.subjectsAround(position)
                    post { detector = detector.withSubjects(found.subjects) }
                    show { ui -> ui.copy(problem = area.problem ?: found.problem) }
                }
            } finally {
                loading.set(false)
            }
        }
    }

    /** Where the driver is now. The director says which Areas' articles still need looking up. */
    private fun onArea(area: Area?, events: List<AreaEvent>) {
        director.areaChanged(area, events).forEach { subject -> lookups.execute { guarded { lookUp(subject) } } }
    }

    private fun lookUp(subject: Subject) {
        val lookup = sources.sourceFor(subject)
        if (lookup is SourceLookup.Unavailable) {
            retrying = retrying + subject
            retryCount.set(retrying.size)
        }
        say(describePassing(subject, lookup))
        if (lookup is SourceLookup.Found) post { director.sourceFound(subject, lookup.source) }
    }

    /** Asks again for subjects whose lookup failed, while they are still close enough to matter. */
    private fun retryFailedLookups() {
        if (retryCount.get() == 0 || !retryQueued.compareAndSet(false, true)) return
        val relevantIds = detector.relevantSubjects.map { it.id }.toSet() + director.currentAreaKeys()
        lookups.execute {
            try {
                guarded { retry(relevantIds) }
            } finally {
                retryQueued.set(false)
            }
        }
    }

    private fun retry(relevantIds: Set<String>) {
        val results = retrying.map { subject ->
            subject to if (subject.id in relevantIds) sources.sourceFor(subject) else null
        }
        retrying = results.filter { (_, lookup) -> lookup is SourceLookup.Unavailable }.map { it.first }
        retryCount.set(retrying.size)
        results.forEach { (subject, lookup) ->
            when (lookup) {
                null -> say("Gave up on ${subject.name}: left behind before its Source could be looked up")
                is SourceLookup.Unavailable -> Unit
                else -> {
                    say(describePassing(subject, lookup))
                    if (lookup is SourceLookup.Found) post { director.sourceFound(subject, lookup.source) }
                }
            }
        }
    }

    /** Runs [work], turning anything unexpected into a visible notice (unless we were stopped meanwhile). */
    private fun guarded(work: () -> Unit) {
        try {
            work()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!stopped) show { it.copy(problem = "Place lookup problem: ${e.javaClass.simpleName}") }
        }
    }

    private fun say(line: String) = show { it.copy(happenings = add(it.happenings, listOf(line))) }

    private fun add(existing: List<String>, lines: List<String>) = (lines.reversed() + existing).take(MAX_HAPPENINGS)

    private fun show(change: (TripUi) -> TripUi) = post { TripSession.update(change) }

    /** Runs [block] on the main thread, unless the Trip has stopped. */
    private fun post(block: () -> Unit) {
        handler.post { if (!stopped) block() }
    }

    private companion object {
        const val MAX_HAPPENINGS = 8
        const val MAX_STORIES = 5
        const val EARLIER_STORIES_OFFERED = 3
    }
}
