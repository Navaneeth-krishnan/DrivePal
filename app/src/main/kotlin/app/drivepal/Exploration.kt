package app.drivepal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.drivepal.core.Area
import app.drivepal.core.AreaEvent
import app.drivepal.core.AreaTracker
import app.drivepal.core.Candidate
import app.drivepal.core.InterludeSections
import app.drivepal.core.Notability
import app.drivepal.core.PassingDetector
import app.drivepal.core.Position
import app.drivepal.core.Relation
import app.drivepal.core.Section
import app.drivepal.core.Source
import app.drivepal.core.SourceLibrary
import app.drivepal.core.SourceLookup
import app.drivepal.core.StoryBrief
import app.drivepal.core.StoryKind
import app.drivepal.core.StoryOutcome
import app.drivepal.core.StoryResult
import app.drivepal.core.StoryScheduler
import app.drivepal.core.StoryWriter
import app.drivepal.core.Subject
import app.drivepal.core.SubjectFinder
import app.drivepal.core.SubjectKind
import app.drivepal.core.Talkativeness
import app.drivepal.core.ToldMemory
import app.drivepal.core.ToldStore
import app.drivepal.core.Utterance
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Looks around the driver during a Trip and decides what to tell. Each thread owns its own state:
 *
 * - **main**: the [PassingDetector] (arithmetic on subjects already loaded, so every GPS fix is judged straight
 *   away, however slow the network is), the pool of candidates, the [StoryScheduler] and the [ToldMemory].
 * - **loader**: the [AreaTracker] and the [SubjectFinder] (Nominatim, Overpass and their fallbacks). Slow.
 * - **lookups**: Source lookups (Wikipedia, Wikidata) for subjects and Areas, and retries of the failed ones.
 * - **writers**: the AI server writing one Story. Slow, so it has its own thread.
 *
 * Passing a subject or *entering* an Area only adds a candidate with its Source to the pool (the place a Trip
 * starts in is not an entry). Stories are written just in time: when nothing is being written or spoken, the
 * scheduler picks the one best candidate that is still relevant and not yet Told, and only then is the AI asked.
 *
 * The Area the driver is in always has its article looked up, whether or not it was entered, because after a long
 * silence an untold section of it becomes an Interlude.
 */
class Exploration(
    private val finder: SubjectFinder,
    private val sources: SourceLibrary,
    private val writer: StoryWriter,
    private val narrator: Narrator,
    store: ToldStore,
    private val talkativeness: () -> Talkativeness,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    /** A candidate with everything needed to write its Story. */
    private class Ready(val candidate: Candidate, val subject: Subject, val source: Source)

    /** An Area's article, split into the sections that can become Interludes. */
    private class AreaSource(val subject: Subject, val source: Source, val sections: List<Section>)

    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val lookups = Executors.newSingleThreadExecutor()
    private val writers = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false

    // Main thread:
    private var detector = PassingDetector.watching(emptyList())
    private val pool = LinkedHashMap<String, Ready>()
    private var scheduler = StoryScheduler(
        talkativeness(),
        startedMillis = SystemClock.elapsedRealtime(),
        interludeAfterMillis = BuildConfig.INTERLUDE_AFTER_SECONDS * 1000L,
    )
    private var memory = ToldMemory.load(store)
    private var writing = false
    private var currentCountry: String? = null
    private var currentProvince: String? = null
    private val areaSources = HashMap<String, AreaSource>()
    private val requestedAreas = HashSet<String>()
    private val enteredAreas = HashSet<String>()
    private val interludes = HashMap<String, Ready>()
    private var toldTitles: List<String> = emptyList() // names told on this Trip, offered to the AI for Framing

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
        update.events.forEach { event -> lookups.execute { guarded { lookUp(event.subject) } } }
        retryFailedLookups()
        loadAround(position)
        conduct(standstill)
    }

    /** Whether the Story with this id is still worth telling. Main thread only. */
    fun isRelevant(utteranceId: String): Boolean = (pool[utteranceId] ?: interludes[utteranceId])?.let(::isRelevant) ?: false

    /** What became of a Story that was told: remembers it, and starts the quiet gap. Main thread only. */
    fun onOutcome(utterance: Utterance, outcome: StoryOutcome) {
        memory = memory.recording(outcome, utterance.id, wallClockMillis())
        if (outcome != StoryOutcome.Dropped) {
            val kind = if (utterance.id.startsWith(INTERLUDE_PREFIX)) StoryKind.Interlude else null
            scheduler = scheduler.storyEnded(SystemClock.elapsedRealtime(), kind)
        }
        if (outcome == StoryOutcome.Told || outcome == StoryOutcome.Skipped) toldTitles = toldTitles + utterance.title
    }

    /** Stops all work. A request already on the wire finishes in the background and is ignored. */
    fun stop() {
        stopped = true
        loader.shutdownNow()
        lookups.shutdownNow()
        writers.shutdownNow()
    }

    /** Picks the next Story, if it is time, and starts writing it. */
    private fun conduct(standstill: Boolean) {
        scheduler = scheduler.withTalkativeness(talkativeness())
        val wallNow = wallClockMillis()
        pool.values.removeAll { !isRelevant(it) || memory.isTold(it.candidate.key, wallNow) }
        val interlude = dueInterlude(wallNow)
        val chosen = scheduler.next(
            nowMillis = SystemClock.elapsedRealtime(),
            candidates = pool.values.map { it.candidate } + listOfNotNull(interlude?.candidate),
            busy = writing || narrator.isTelling,
            standstill = standstill,
            isRelevant = { (pool[it.key] ?: interludes[it.key])?.let(::isRelevant) ?: false },
            isTold = { memory.isTold(it.key, wallNow) },
        ) ?: return
        startWriting(pool[chosen.key] ?: interludes.getValue(chosen.key))
    }

    /** The next untold section of the current Province's article, or else of the Country's: the Interlude that could be told now. */
    private fun dueInterlude(wallNow: Long): Ready? {
        val areas = listOfNotNull(currentProvince?.let { "area/province/$it" }, currentCountry?.let { "area/country/$it" })
        for (areaKey in areas) {
            val area = areaSources[areaKey] ?: continue
            val section = area.sections.firstOrNull { !memory.isTold("$INTERLUDE_PREFIX$areaKey/${it.title}", wallNow) } ?: continue
            val key = "$INTERLUDE_PREFIX$areaKey/${section.title}"
            return interludes.getOrPut(key) {
                Ready(
                    Candidate(key, StoryKind.Interlude, Notability(0, 0)),
                    area.subject,
                    Source(area.source.language, section.title, section.text, area.source.notability),
                )
            }
        }
        return null
    }

    private fun startWriting(ready: Ready) {
        writing = true
        // Whether a place is ahead or behind is judged now, as its Story is about to be told.
        val relation = if (ready.candidate.kind == StoryKind.Town || ready.candidate.kind == StoryKind.Landmark) {
            if (detector.isAhead(ready.subject)) Relation.Ahead else Relation.Behind
        } else {
            null
        }
        val earlier = toldTitles.takeLast(EARLIER_STORIES_OFFERED)
        val talk = talkativeness()
        writers.execute {
            var utterance: Utterance? = null
            try {
                guarded { utterance = write(ready, relation, earlier, talk) }
            } finally {
                post {
                    utterance?.let(narrator::tell)
                    writing = false
                }
            }
        }
    }

    private fun write(ready: Ready, relation: Relation?, earlier: List<String>, talk: Talkativeness): Utterance? {
        val brief = StoryBrief(ready.subject.name, ready.candidate.kind, ready.source, talk, relation, earlier)
        val result = writer.write(brief) ?: return null
        val title = if (ready.candidate.kind == StoryKind.Interlude) "${ready.subject.name}: ${ready.source.title}" else ready.subject.name
        show { ui -> ui.copy(stories = (listOf(describeStory(ready.subject.copy(name = title), result)) + ui.stories).take(MAX_STORIES)) }
        val (text, language) = when (result) {
            is StoryResult.Written -> result.text to "en"
            is StoryResult.Fallback -> result.text to result.language
        }
        return Utterance(ready.candidate.key, text, language, title = title)
    }

    private fun isRelevant(ready: Ready): Boolean = when (ready.candidate.kind) {
        StoryKind.Country -> ready.subject.name == currentCountry
        StoryKind.Province -> ready.subject.name == currentProvince
        // An Interlude is about the Area its subject names, and lasts while the driver is inside it.
        StoryKind.Interlude -> when (ready.subject.kind) {
            SubjectKind.Country -> ready.subject.name == currentCountry
            SubjectKind.Province -> ready.subject.name == currentProvince
            else -> false
        }
        else -> detector.relevantSubjects.any { it.id == ready.subject.id }
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

    /**
     * Where the driver is now, and which Areas were just entered. The current Area's article is always looked up
     * (for Interludes); only an Area that was entered also becomes a Story.
     */
    private fun onArea(area: Area?, events: List<AreaEvent>) {
        area?.let {
            currentCountry = it.country
            currentProvince = it.province
        }
        events.forEach { event ->
            val key = when (event) {
                is AreaEvent.EnteredCountry -> "area/country/${event.name}"
                is AreaEvent.EnteredProvince -> "area/province/${event.name}"
            }
            enteredAreas += key
            areaSources[key]?.let { addCandidate(it.subject, it.source) } // its article is already known
        }
        listOfNotNull(
            area?.let { Subject("area/country/${it.country}", it.country, SubjectKind.Country, NO_POSITION, wikipediaTag = "en:${it.country}") },
            area?.province?.let {
                Subject(
                    "area/province/$it", it, SubjectKind.Province, NO_POSITION,
                    wikipediaTag = area.provinceWikipediaTag ?: "en:$it", wikidataId = area.provinceWikidataId,
                )
            },
        ).filter { requestedAreas.add(it.id) }.forEach { subject -> lookups.execute { guarded { lookUp(subject) } } }
    }

    private fun lookUp(subject: Subject) {
        val lookup = sources.sourceFor(subject)
        if (lookup is SourceLookup.Unavailable) {
            retrying = retrying + subject
            retryCount.set(retrying.size)
        }
        say(describePassing(subject, lookup))
        if (lookup is SourceLookup.Found) post { register(subject, lookup.source) }
    }

    /** A Source was found. A place is ready to tell; an Area's article is kept for Interludes, and told only if it was entered. */
    private fun register(subject: Subject, source: Source) {
        if (subject.kind == SubjectKind.Country || subject.kind == SubjectKind.Province) {
            areaSources[subject.id] = AreaSource(subject, source, InterludeSections.of(source.text))
            if (subject.id in enteredAreas) addCandidate(subject, source)
        } else {
            addCandidate(subject, source)
        }
    }

    private fun addCandidate(subject: Subject, source: Source) {
        val kind = when (subject.kind) {
            SubjectKind.Country -> StoryKind.Country
            SubjectKind.Province -> StoryKind.Province
            SubjectKind.Town -> StoryKind.Town
            SubjectKind.Landmark -> StoryKind.Landmark
        }
        pool[subject.id] = Ready(Candidate(subject.id, kind, source.notability), subject, source)
    }

    /** Asks again for subjects whose lookup failed, while they are still close enough to matter. */
    private fun retryFailedLookups() {
        if (retryCount.get() == 0 || !retryQueued.compareAndSet(false, true)) return
        val relevantIds = detector.relevantSubjects.map { it.id }.toSet() + setOfNotNull(
            currentCountry?.let { "area/country/$it" }, currentProvince?.let { "area/province/$it" },
        )
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
                    if (lookup is SourceLookup.Found) post { register(subject, lookup.source) }
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
        const val INTERLUDE_PREFIX = "interlude/"
        val NO_POSITION = Position(0.0, 0.0) // an Area is not a point on the map
    }
}
