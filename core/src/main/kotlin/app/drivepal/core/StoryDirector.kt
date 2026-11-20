package app.drivepal.core

/** The Story chosen to be written and told now. [title] names it on screen and in the Framing offered to the AI. */
class Pick(val key: String, val kind: StoryKind, val subject: Subject, val source: Source, val title: String)

/**
 * Decides what the driver hears. It keeps the pool of Stories that could be told (places with their Source), the
 * Areas the driver is in with their articles, the Told memory and the scheduler, and from them picks the one Story to
 * write next. It knows nothing of threads or Android: the caller asks it things and tells it what happened.
 *
 * Not thread-safe: all calls must come from one thread.
 *
 * Areas are keyed by country *and* name, since province names repeat (Limburg is in the Netherlands and in Belgium).
 * The Area a Trip starts in is looked up, for Interludes, but only an Area that is *entered* becomes a Story.
 * Leaving an Area forgets its article.
 */
class StoryDirector(
    store: ToldStore,
    private var scheduler: StoryScheduler,
    private val isPlaceRelevant: (subjectId: String) -> Boolean,
    private val wallClockMillis: () -> Long,
) {
    private class Ready(val candidate: Candidate, val subject: Subject, val source: Source)

    /** An Area's article, split into the sections that can become Interludes. */
    private class AreaSource(val subject: Subject, val source: Source, val sections: List<Section>)

    private var memory = ToldMemory.load(store)
    private val pool = LinkedHashMap<String, Ready>()
    private val areaSources = HashMap<String, AreaSource>()
    private val requestedAreas = HashSet<String>()
    private val enteredAreas = HashSet<String>()
    private val interludes = HashMap<String, Ready>()
    private val unspoken = HashSet<String>() // could not be told this Trip: not written again and again
    private var currentCountryKey: String? = null
    private var currentProvinceKey: String? = null
    private var toldTitles: List<String> = emptyList()

    /**
     * The driver is now in [area], having just entered [events]. Returns the Areas whose articles must be looked up.
     */
    fun areaChanged(area: Area?, events: List<AreaEvent>): List<Subject> {
        currentCountryKey = area?.let { countryKey(it.country) }
        currentProvinceKey = area?.province?.let { provinceKey(area.country, it) }
        val current = setOfNotNull(currentCountryKey, currentProvinceKey)
        areaSources.keys.retainAll(current)
        requestedAreas.retainAll(current)
        enteredAreas.retainAll(current)
        interludes.values.removeAll { it.subject.id !in current }

        if (area == null) return emptyList()
        events.forEach { event ->
            val key = when (event) {
                is AreaEvent.EnteredCountry -> countryKey(event.name)
                is AreaEvent.EnteredProvince -> provinceKey(area.country, event.name)
            }
            enteredAreas += key
            areaSources[key]?.let { addCandidate(it.subject, it.source) } // its article is already known
        }
        return listOfNotNull(countrySubject(area), provinceSubject(area)).filter { requestedAreas.add(it.id) }
    }

    /** A Source was found for [subject]: a place is ready to tell; an Area's article is kept, and told only if entered. */
    fun sourceFound(subject: Subject, source: Source) {
        if (subject.kind != SubjectKind.Country && subject.kind != SubjectKind.Province) return addCandidate(subject, source)
        if (subject.id !in setOfNotNull(currentCountryKey, currentProvinceKey)) return // the driver has left that Area
        areaSources[subject.id] = AreaSource(subject, source, InterludeSections.of(source.text))
        if (subject.id in enteredAreas) addCandidate(subject, source)
    }

    /** The Story to write now, or null: nothing is due, nothing is worth telling, or something is already [busy]. */
    fun next(nowMillis: Long, talkativeness: Talkativeness, busy: Boolean, standstill: Boolean): Pick? {
        scheduler = scheduler.withTalkativeness(talkativeness)
        val wallNow = wallClockMillis()
        pool.values.removeAll { !isRelevant(it) || memory.isTold(it.candidate.key, wallNow) || it.candidate.key in unspoken }
        val interlude = dueInterlude(wallNow)
        val chosen = scheduler.next(
            nowMillis = nowMillis,
            candidates = pool.values.map { it.candidate } + listOfNotNull(interlude?.candidate),
            busy = busy,
            standstill = standstill,
            isRelevant = { ready(it.key)?.let(::isRelevant) ?: false },
            isTold = { memory.isTold(it.key, wallNow) },
        ) ?: return null
        val ready = ready(chosen.key) ?: return null
        val title = if (ready.candidate.kind == StoryKind.Interlude) "${ready.subject.name}: ${ready.source.title}" else ready.subject.name
        return Pick(ready.candidate.key, ready.candidate.kind, ready.subject, ready.source, title)
    }

    /** The Areas the driver is in now (the Country and the Province), as keys. */
    fun currentAreaKeys(): Set<String> = setOfNotNull(currentCountryKey, currentProvinceKey)

    /** Whether the Story with this key is still worth telling. */
    fun isRelevant(storyKey: String): Boolean = ready(storyKey)?.let(::isRelevant) ?: false

    /** What became of a Story: it is remembered, the quiet gap starts, and a Story that could not be spoken is not tried again. */
    fun storyEnded(key: String, title: String, outcome: StoryOutcome, nowMillis: Long) {
        memory = memory.recording(outcome, key, wallClockMillis())
        if (outcome != StoryOutcome.Dropped) {
            scheduler = scheduler.storyEnded(nowMillis, if (key.startsWith(INTERLUDE_PREFIX)) StoryKind.Interlude else null)
        }
        if (outcome == StoryOutcome.Told || outcome == StoryOutcome.Skipped) toldTitles = toldTitles + title
        if (outcome == StoryOutcome.Dropped) {
            unspoken += key
            pool.remove(key)
        }
    }

    /** The names of the last [count] Stories told on this Trip, for the AI to link back to. */
    fun earlierTitles(count: Int): List<String> = toldTitles.takeLast(count)

    private fun ready(key: String): Ready? = pool[key] ?: interludes[key]

    private fun isRelevant(ready: Ready): Boolean = when (ready.candidate.kind) {
        StoryKind.Country -> ready.subject.id == currentCountryKey
        StoryKind.Province -> ready.subject.id == currentProvinceKey
        // An Interlude's subject is the Area it is about, and it lasts while the driver is inside it.
        StoryKind.Interlude -> ready.subject.id == currentCountryKey || ready.subject.id == currentProvinceKey
        else -> isPlaceRelevant(ready.subject.id)
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

    /** The next untold section of the current Province's article, or else of the Country's. */
    private fun dueInterlude(wallNow: Long): Ready? {
        for (areaKey in listOfNotNull(currentProvinceKey, currentCountryKey)) {
            val area = areaSources[areaKey] ?: continue
            val section = area.sections.firstOrNull {
                val key = interludeKey(areaKey, it.title)
                key !in unspoken && !memory.isTold(key, wallNow)
            } ?: continue
            val key = interludeKey(areaKey, section.title)
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

    private fun countrySubject(area: Area) =
        Subject(countryKey(area.country), area.country, SubjectKind.Country, NO_POSITION, wikipediaTag = "en:${area.country}")

    private fun provinceSubject(area: Area): Subject? = area.province?.let { name ->
        Subject(
            provinceKey(area.country, name), name, SubjectKind.Province, NO_POSITION,
            wikipediaTag = area.provinceWikipediaTag ?: "en:$name", wikidataId = area.provinceWikidataId,
        )
    }

    private fun countryKey(country: String) = "area/country/$country"

    private fun provinceKey(country: String, province: String) = "area/province/$country/$province"

    private fun interludeKey(areaKey: String, section: String) = "$INTERLUDE_PREFIX$areaKey/$section"

    private companion object {
        const val INTERLUDE_PREFIX = "interlude/"
        val NO_POSITION = Position(0.0, 0.0) // an Area is not a point on the map
    }
}
