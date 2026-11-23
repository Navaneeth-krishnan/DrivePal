package app.drivepal.core

/**
 * Something that could be told next. [key] is stable across Trips, so the Told memory can recognise it. With [nameOnly]
 * there is no Source, so all that can be said is the place's name.
 */
data class Candidate(val key: String, val kind: StoryKind, val notability: Notability, val nameOnly: Boolean = false)

/** What to tell next: with [announceOnly] just the place's name, because the quiet gap of the last Story is still running. */
data class Choice(val candidate: Candidate, val announceOnly: Boolean)

/**
 * Decides which Story to write and tell next, and when: one at a time, just in time. The order is entering a
 * Country, then a Province, then Towns and Landmarks by Notability. A gap of silence follows every Story, set by
 * Talkativeness; within that gap a place in range is still announced by name, as long as 20 seconds have passed since
 * anything was said. While the driver is standing still only Areas (and Interludes) are told.
 *
 * An Interlude, a Story about an untold part of the Province or Country, fills a long silence: only in Chatty mode,
 * otherwise only after [interludeAfterMillis] (5 minutes) without a Story, counted from the last Story or, if there
 * has been none, from the start of the Trip. After three Interludes in a row that wait doubles, and after six it
 * doubles again; any other Story puts it back. Places and Areas always go first. Immutable.
 */
@ConsistentCopyVisibility
data class StoryScheduler private constructor(
    val talkativeness: Talkativeness,
    private val lastEndedMillis: Long?,
    private val lastSpokeMillis: Long?,
    private val startedMillis: Long,
    private val interludeAfterMillis: Long,
    private val interludesInARow: Int,
) {
    fun withTalkativeness(talkativeness: Talkativeness): StoryScheduler = copy(talkativeness = talkativeness)

    /** A Story of [kind] has just finished (told, skipped or cut off): the quiet gap starts now. */
    fun storyEnded(nowMillis: Long, kind: StoryKind? = null): StoryScheduler = copy(
        lastEndedMillis = nowMillis,
        lastSpokeMillis = nowMillis,
        interludesInARow = if (kind == StoryKind.Interlude) interludesInARow + 1 else 0,
    )

    /** A place's name has just been said on its own: only the short silence follows, the Story gap does not start. */
    fun announcementEnded(nowMillis: Long): StoryScheduler = copy(lastSpokeMillis = nowMillis)

    /**
     * The one to tell now, or null. [busy] means a Story is already being written or spoken.
     * [isRelevant], [isTold] and [isNameSaid] come from the Passing detector and the Told memory; [distanceMeters] is how
     * far a place is from the driver now (0 for an Area or Interlude).
     */
    fun next(
        nowMillis: Long,
        candidates: List<Candidate>,
        busy: Boolean,
        standstill: Boolean,
        isRelevant: (Candidate) -> Boolean,
        isTold: (Candidate) -> Boolean,
        isNameSaid: (Candidate) -> Boolean,
        distanceMeters: (Candidate) -> Double,
    ): Choice? {
        if (busy) return null
        if (lastSpokeMillis != null && nowMillis - lastSpokeMillis < ANNOUNCEMENT_GAP_MILLIS) return null
        val eligible = candidates.filter { isRelevant(it) && (!standstill || isArea(it.kind) || it.kind == StoryKind.Interlude) }
        val storyGapIsOver = lastEndedMillis == null || nowMillis - lastEndedMillis >= gapMillis(talkativeness)
        val namesOnly = talkativeness == Talkativeness.Names
        // Only the name is said: in Names mode, for a place with no Source, and while the last Story's gap still runs.
        val announces = { it: Candidate -> !storyGapIsOver || namesOnly || it.nameOnly }
        val usable = eligible.filter {
            if (announces(it)) {
                it.kind != StoryKind.Interlude && distanceMeters(it) <= announcementRange(it.kind) && !isNameSaid(it) && !isTold(it)
            } else {
                !isTold(it) && (it.kind != StoryKind.Interlude || interludeIsDue(nowMillis))
            }
        }
        // A name goes to the closest place. A Story prefers a place within a kilometre, then the most notable.
        val order = if (storyGapIsOver && !namesOnly) {
            compareBy<Candidate> { rank(it.kind) }
                .thenBy { distanceMeters(it) > ANNOUNCEMENT_RANGE_METERS }
                .thenByDescending { it.notability }
                .thenBy { distanceMeters(it) }
        } else {
            compareBy<Candidate> { rank(it.kind) }.thenBy { distanceMeters(it) }
        }
        return usable.minWithOrNull(order)?.let { Choice(it, announceOnly = announces(it)) }
    }

    private fun interludeIsDue(nowMillis: Long): Boolean {
        if (talkativeness != Talkativeness.Chatty) return false
        val doublings = (interludesInARow / INTERLUDES_PER_DOUBLING).coerceAtMost(MAX_DOUBLINGS)
        return nowMillis - (lastEndedMillis ?: startedMillis) >= interludeAfterMillis shl doublings
    }

    /** A Landmark is announced from as far as it is relevant, saying how far; any other place only when it is close. */
    private fun announcementRange(kind: StoryKind) = if (kind == StoryKind.Landmark) LANDMARK_RANGE_METERS else ANNOUNCEMENT_RANGE_METERS

    private fun isArea(kind: StoryKind) = kind == StoryKind.Country || kind == StoryKind.Province

    private fun rank(kind: StoryKind): Int = when (kind) {
        StoryKind.Country -> 0
        StoryKind.Province -> 1
        StoryKind.Town, StoryKind.Landmark, StoryKind.Junction -> 2
        StoryKind.Interlude -> 3
    }

    private fun gapMillis(talkativeness: Talkativeness): Long = when (talkativeness) {
        Talkativeness.Names -> ANNOUNCEMENT_GAP_MILLIS
        Talkativeness.Normal -> 5 * 60_000L
        Talkativeness.Chatty -> 60_000L
    }

    companion object {
        private const val ANNOUNCEMENT_GAP_MILLIS = 20_000L
        private const val ANNOUNCEMENT_RANGE_METERS = 1_000.0
        private const val LANDMARK_RANGE_METERS = 3_000.0
        private const val INTERLUDES_PER_DOUBLING = 3
        private const val MAX_DOUBLINGS = 2 // 5, 10, then 20 minutes

        operator fun invoke(
            talkativeness: Talkativeness,
            startedMillis: Long = 0,
            interludeAfterMillis: Long = 5 * 60_000L,
        ): StoryScheduler = StoryScheduler(talkativeness, null, null, startedMillis, interludeAfterMillis, interludesInARow = 0)
    }
}
