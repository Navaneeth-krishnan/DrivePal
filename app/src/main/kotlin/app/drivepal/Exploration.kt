package app.drivepal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.drivepal.core.AreaTracker
import app.drivepal.core.PassingDetector
import app.drivepal.core.Relation
import app.drivepal.core.Source
import app.drivepal.core.StoryBrief
import app.drivepal.core.StoryKind
import app.drivepal.core.StoryResult
import app.drivepal.core.StoryWriter
import app.drivepal.core.SubjectKind
import app.drivepal.core.Talkativeness
import app.drivepal.core.Utterance
import app.drivepal.core.Position
import app.drivepal.core.SourceLibrary
import app.drivepal.core.SourceLookup
import app.drivepal.core.Subject
import app.drivepal.core.SubjectFinder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Looks around the driver during a Trip. Three threads, each owning its own state:
 *
 * - **main**: the [PassingDetector]. It only does arithmetic on subjects already loaded, so every GPS
 *   fix is judged straight away, however slow the network is.
 * - **loader**: the [AreaTracker] and the [SubjectFinder] (Nominatim and Overpass). Slow; results are
 *   handed back to the main thread.
 * - **lookups**: Source lookups (Wikipedia and Wikidata) for Passed subjects, and retries of the failed ones.
 * - **writers**: the AI server writing a Story for each subject that has a Source. Slow, so it has its own thread.
 */
class Exploration(
    private val finder: SubjectFinder,
    private val sources: SourceLibrary,
    private val writer: StoryWriter,
    private val narrator: Narrator,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val lookups = Executors.newSingleThreadExecutor()
    private val writers = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false

    private var detector = PassingDetector.watching(emptyList()) // main thread

    private var areaTracker = AreaTracker.starting(NominatimHttp, SystemClock::elapsedRealtime, PhotonHttp) // loader thread
    private val loading = AtomicBoolean(false)

    private var retrying: List<Subject> = emptyList() // lookups thread
    private val retryCount = AtomicInteger(0)
    private val retryQueued = AtomicBoolean(false)

    private var told: List<String> = emptyList() // writers thread: names of subjects already told, for Framing

    /** Called on the main thread for every GPS position. */
    fun onPosition(position: Position) {
        if (stopped) return
        val update = detector.withPosition(position)
        detector = update.detector
        update.detector.headingDegrees?.let { heading ->
            show { it.copy(headingDegrees = heading.toFloat()) }
        }
        update.events.forEach { event -> lookups.execute { guarded { lookUp(event.subject) } } }
        retryFailedLookups()
        loadAround(position)
    }

    /** Whether [subjectId] is still close enough to be worth telling. Main thread only. */
    fun isRelevant(subjectId: String): Boolean = detector.relevantSubjects.any { it.id == subjectId }

    /** Stops all work. A request already on the wire finishes in the background and is ignored. */
    fun stop() {
        stopped = true
        loader.shutdownNow()
        lookups.shutdownNow()
        writers.shutdownNow()
    }

    private fun loadAround(position: Position) {
        if (!loading.compareAndSet(false, true)) return
        loader.execute {
            try {
                guarded {
                    val area = areaTracker.withPosition(position)
                    areaTracker = area.tracker
                    show { ui -> ui.copy(happenings = add(ui.happenings, area.events.map(::describe)), problem = area.problem) }

                    val found = finder.subjectsAround(position)
                    post { detector = detector.withSubjects(found.subjects) }
                    show { ui -> ui.copy(problem = area.problem ?: found.problem) }
                }
            } finally {
                loading.set(false)
            }
        }
    }

    private fun lookUp(subject: Subject) {
        val lookup = sources.sourceFor(subject)
        if (lookup is SourceLookup.Unavailable) {
            retrying = retrying + subject
            retryCount.set(retrying.size)
        }
        say(describePassing(subject, lookup))
        if (lookup is SourceLookup.Found) requestStory(subject, lookup.source)
    }

    /** Asks the AI for a Story. Whether the subject is ahead or behind is judged now, as the Story is about to be told. */
    private fun requestStory(subject: Subject, source: Source) {
        post {
            val relation = if (detector.isAhead(subject)) Relation.Ahead else Relation.Behind
            writers.execute { guarded { writeStory(subject, source, relation) } }
        }
    }

    private fun writeStory(subject: Subject, source: Source, relation: Relation) {
        val kind = if (subject.kind == SubjectKind.Town) StoryKind.Town else StoryKind.Landmark
        val brief = StoryBrief(subject.name, kind, source, Talkativeness.Normal, relation, told.takeLast(EARLIER_STORIES_OFFERED))
        val result = writer.write(brief) ?: return
        told = told + subject.name
        show { ui -> ui.copy(stories = (listOf(describeStory(subject, result)) + ui.stories).take(MAX_STORIES)) }
        val (text, language) = when (result) {
            is StoryResult.Written -> result.text to "en"
            is StoryResult.Fallback -> result.text to result.language
        }
        post { narrator.tell(Utterance(subject.id, text, language, title = subject.name)) }
    }

    /** Asks again for Passed subjects whose lookup failed, while they are still close enough to matter. */
    private fun retryFailedLookups() {
        if (retryCount.get() == 0 || !retryQueued.compareAndSet(false, true)) return
        val relevantIds = detector.relevantSubjects.map { it.id }.toSet()
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
                    if (lookup is SourceLookup.Found) requestStory(subject, lookup.source)
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
