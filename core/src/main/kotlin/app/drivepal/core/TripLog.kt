package app.drivepal.core

/** One Story as the Trip log remembers it. [sourceTitle] is the *article's* title (an Interlude's is not its section's). */
data class LoggedStory(
    val title: String,
    val kind: StoryKind,
    val text: String,
    val language: String,
    val outcome: StoryOutcome,
    val sourceLanguage: String?,
    val sourceTitle: String?,
    val atMillis: Long,
) {
    /** A link to the Source article, written the way Wikipedia writes its addresses. Null if the Source is not known. */
    val sourceUrl: String?
        get() = if (sourceLanguage == null || sourceTitle == null) null else "https://$sourceLanguage.wikipedia.org/wiki/${wikipediaPath(sourceTitle)}"

    companion object {
        /** Null for something that is no Story: a place name said on its own, or a system message. */
        fun ofOrNull(utterance: Utterance, outcome: StoryOutcome, atMillis: Long): LoggedStory? =
            if (utterance.story && utterance.text.isNotBlank()) of(utterance, outcome, atMillis) else null

        fun of(utterance: Utterance, outcome: StoryOutcome, atMillis: Long) = LoggedStory(
            title = utterance.title,
            kind = utterance.kind ?: StoryKind.Landmark,
            text = utterance.text,
            language = utterance.language,
            outcome = outcome,
            sourceLanguage = utterance.sourceLanguage,
            sourceTitle = utterance.sourceTitle,
            atMillis = atMillis,
        )

        private const val UNRESERVED = "_.~-()!*',:/"

        /** Spaces become underscores; anything else that is not plain is percent-encoded. */
        private fun wikipediaPath(title: String): String = buildString {
            title.replace(' ', '_').toByteArray(Charsets.UTF_8).forEach { byte ->
                val char = byte.toInt().toChar()
                if (byte >= 0 && (char.isLetterOrDigit() || char in UNRESERVED)) append(char) else append("%%%02X".format(byte.toInt() and 0xFF))
            }
        }
    }
}

data class LoggedTrip(val startedMillis: Long, val endedMillis: Long?, val stories: List<LoggedStory>)

/** Where [TripLog] keeps the Trips, so they survive the app closing. */
interface TripLogStore {
    fun load(): List<LoggedTrip>

    fun save(trips: List<LoggedTrip>)
}

/**
 * What was told on the last 50 Trips, newest first, with each Story's Source. A Trip with nothing told is not kept.
 * Every change is saved straight away, so a Trip cut short by the app being killed is still there. Immutable.
 */
class TripLog private constructor(
    private val store: TripLogStore,
    private val all: List<LoggedTrip>,
    private val running: Boolean,
) {
    /** The Trips with something in them, newest first. */
    val trips: List<LoggedTrip> get() = all.filter { it.stories.isNotEmpty() }

    fun startTrip(nowMillis: Long): TripLog = TripLog(store, listOf(LoggedTrip(nowMillis, null, emptyList())) + all, running = true)

    /** Adds a Story to the Trip that is running. With none running it is ignored. */
    fun recording(story: LoggedStory): TripLog {
        if (!running || all.isEmpty()) return this
        val current = all.first()
        return changed(listOf(current.copy(stories = current.stories + story)) + all.drop(1), running = true)
    }

    fun endTrip(nowMillis: Long): TripLog {
        if (!running || all.isEmpty()) return this
        return changed(listOf(all.first().copy(endedMillis = nowMillis)) + all.drop(1), running = false)
    }

    fun cleared(): TripLog {
        store.save(emptyList())
        return TripLog(store, emptyList(), running = false)
    }

    private fun changed(trips: List<LoggedTrip>, running: Boolean): TripLog {
        val kept = trips.filter { it.stories.isNotEmpty() || (running && it === trips.first()) }.take(MAX_TRIPS)
        store.save(kept.filter { it.stories.isNotEmpty() })
        return TripLog(store, kept, running)
    }

    companion object {
        private const val MAX_TRIPS = 50

        fun load(store: TripLogStore): TripLog = TripLog(store, store.load().filter { it.stories.isNotEmpty() }, running = false)
    }
}
