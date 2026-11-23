package app.drivepal.core

/**
 * The Story chosen to be written and told now. [title] names it on screen and in the Framing offered to the AI.
 * [source] is what it is written from; [articleLanguage] and [articleTitle] name the whole article, which differs from
 * [source] for an Interlude (a section of an Area's article). With [announceOnly] only the place's name is said, no
 * Story: the driver chose Names mode, or the place has no Source (a Junction, a village without an article).
 * [key] is what the Told memory records, so an announcement and a Story about one place are remembered apart.
 * [distanceMeters]: how far the place was when it was picked (0 for an Area or Interlude).
 * [nameAlreadySaid]: the place's name was already announced on this Trip, so its Story needs no Announcement.
 */
class Pick(
    val key: String,
    val kind: StoryKind,
    val subject: Subject,
    val source: Source?,
    val title: String,
    val articleLanguage: String?,
    val articleTitle: String?,
    val announceOnly: Boolean = false,
    val nameAlreadySaid: Boolean = false,
    val distanceMeters: Double = 0.0,
)

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
    /** How far a place is from the driver now. Not asked for Areas or Interludes. */
    private val distanceMeters: (Subject) -> Double = { 0.0 },
) {
    private class Ready(val candidate: Candidate, val subject: Subject, val source: Source?, val article: Source? = source)

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
    private val namesSaid = HashSet<String>() // places announced on this Trip

    /**
     * The driver is now in [area], having just entered [events]. Returns the Areas whose articles must be looked up.
     * An Area named by a Region pack ([fromPack]) has no article to look up: an entered one is announced by name.
     */
    fun areaChanged(area: Area?, events: List<AreaEvent>, fromPack: Boolean = false): List<Subject> {
        currentCountryKey = area?.let { countryKey(it.country) }
        currentProvinceKey = area?.province?.let { provinceKey(area.country, it) }
        val current = setOfNotNull(currentCountryKey, currentProvinceKey)
        areaSources.keys.retainAll(current)
        requestedAreas.retainAll(current)
        enteredAreas.retainAll(current)
        interludes.values.removeAll { it.subject.id !in current }

        if (area == null) return emptyList()
        if (fromPack) {
            events.forEach { event ->
                when (event) {
                    is AreaEvent.EnteredCountry -> countrySubject(area)
                    is AreaEvent.EnteredProvince -> provinceSubject(area)
                }?.let { addCandidate(it, source = null) }
            }
            return emptyList()
        }
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

    /**
     * What the Source lookup for [subject] came to. A place whose article is too thin for a Story is still announced by
     * name; a failed lookup says nothing, since it is asked again.
     */
    fun lookupDone(subject: Subject, lookup: SourceLookup) = when (lookup) {
        is SourceLookup.Found -> sourceFound(subject, lookup.source)
        SourceLookup.NotStoryWorthy -> if (isPlace(subject)) placeFound(subject) else Unit
        is SourceLookup.Unavailable -> Unit
    }

    private fun isPlace(subject: Subject) = subject.kind == SubjectKind.Town || subject.kind == SubjectKind.Landmark

    /** A place with nothing to tell (a Junction, a village without an article) that is only announced by name. */
    fun placeFound(subject: Subject) = addCandidate(subject, source = null)

    /** The Story to write now, or null: nothing is due, nothing is worth telling, or something is already [busy]. */
    fun next(nowMillis: Long, talkativeness: Talkativeness, busy: Boolean, standstill: Boolean): Pick? {
        scheduler = scheduler.withTalkativeness(talkativeness)
        val wallNow = wallClockMillis()
        val namesOnly = talkativeness == Talkativeness.Names
        pool.values.removeAll { !isRelevant(it) || memory.isTold(it.candidate.key, wallNow) || it.candidate.key in unspoken }
        val interlude = dueInterlude(wallNow)
        val chosen = scheduler.next(
            nowMillis = nowMillis,
            candidates = pool.values.map { it.candidate } + listOfNotNull(interlude?.candidate),
            busy = busy,
            standstill = standstill,
            isRelevant = { ready(it.key)?.let(::isRelevant) ?: false },
            isTold = { candidate -> ready(candidate.key)?.let { memory.isTold(toldKey(it, namesOnly), wallNow) } ?: true },
            isNameSaid = { candidate -> memory.isTold("$NAME_PREFIX${candidate.key}", wallNow) },
            distanceMeters = { candidate -> ready(candidate.key)?.takeIf { isPlace(it.candidate.kind) }?.let { distanceMeters(it.subject) } ?: 0.0 },
        ) ?: return null
        val ready = ready(chosen.candidate.key) ?: return null
        val announceOnly = namesOnly || ready.source == null || chosen.announceOnly
        val title = if (ready.candidate.kind == StoryKind.Interlude) "${ready.subject.name}: ${ready.source?.title}" else ready.subject.name
        return Pick(
            toldKey(ready, announceOnly), ready.candidate.kind, ready.subject, ready.source, title,
            ready.article?.language, ready.article?.title, announceOnly = announceOnly,
            nameAlreadySaid = !announceOnly && ready.candidate.key in namesSaid,
            distanceMeters = if (isPlace(ready.candidate.kind)) distanceMeters(ready.subject) else 0.0,
        )
    }

    /** The Areas the driver is in now (the Country and the Province), as keys. */
    fun currentAreaKeys(): Set<String> = setOfNotNull(currentCountryKey, currentProvinceKey)

    /** Whether the Story with this key is still worth telling. */
    fun isRelevant(storyKey: String): Boolean = ready(storyKey.removePrefix(NAME_PREFIX))?.let(::isRelevant) ?: false

    /** What became of a Story: it is remembered, the quiet gap starts, and a Story that could not be spoken is not tried again. */
    fun storyEnded(key: String, title: String, outcome: StoryOutcome, nowMillis: Long) {
        memory = memory.recording(outcome, key, wallClockMillis())
        if (outcome != StoryOutcome.Dropped) {
            scheduler = if (key.startsWith(NAME_PREFIX)) {
                scheduler.announcementEnded(nowMillis)
            } else {
                scheduler.storyEnded(nowMillis, if (key.startsWith(INTERLUDE_PREFIX)) StoryKind.Interlude else null)
            }
        }
        if (key.startsWith(NAME_PREFIX) && outcome != StoryOutcome.Dropped) namesSaid += key.removePrefix(NAME_PREFIX)
        if (outcome == StoryOutcome.Told || outcome == StoryOutcome.Skipped) toldTitles = toldTitles + title
        if (outcome == StoryOutcome.Dropped) {
            unspoken += key.removePrefix(NAME_PREFIX)
            pool.remove(key.removePrefix(NAME_PREFIX))
        }
    }

    /** The names of the last [count] Stories told on this Trip, for the AI to link back to. */
    fun earlierTitles(count: Int): List<String> = toldTitles.takeLast(count)

    private fun isPlace(kind: StoryKind) = kind == StoryKind.Town || kind == StoryKind.Landmark || kind == StoryKind.Junction

    private fun ready(key: String): Ready? = pool[key] ?: interludes[key]

    private fun isRelevant(ready: Ready): Boolean = when (ready.candidate.kind) {
        StoryKind.Country -> ready.subject.id == currentCountryKey
        StoryKind.Province -> ready.subject.id == currentProvinceKey
        // An Interlude's subject is the Area it is about, and it lasts while the driver is inside it.
        StoryKind.Interlude -> ready.subject.id == currentCountryKey || ready.subject.id == currentProvinceKey
        else -> isPlaceRelevant(ready.subject.id)
    }

    /** What the Told memory records: a name said on its own is not the Story, so it must not use it up. */
    private fun toldKey(ready: Ready, announceOnly: Boolean) =
        if (announceOnly || ready.source == null) "$NAME_PREFIX${ready.candidate.key}" else ready.candidate.key

    private fun addCandidate(subject: Subject, source: Source?) {
        val kind = when (subject.kind) {
            SubjectKind.Country -> StoryKind.Country
            SubjectKind.Province -> StoryKind.Province
            SubjectKind.Town -> StoryKind.Town
            SubjectKind.Landmark -> StoryKind.Landmark
            SubjectKind.Junction -> StoryKind.Junction
        }
        pool[subject.id] = Ready(Candidate(subject.id, kind, source?.notability ?: Notability(0, 0), nameOnly = source == null), subject, source)
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
                    article = area.source,
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
        const val NAME_PREFIX = "name/"
        val NO_POSITION = Position(0.0, 0.0) // an Area is not a point on the map
    }
}
