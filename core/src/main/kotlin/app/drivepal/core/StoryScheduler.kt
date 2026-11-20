package app.drivepal.core

/** Something that could be told next. [key] is stable across Trips, so the Told memory can recognise it. */
data class Candidate(val key: String, val kind: StoryKind, val notability: Notability)

/**
 * Decides which Story to write and tell next, and when: one at a time, just in time. The order is entering a
 * Country, then a Province, then Towns and Landmarks by Notability. A gap of silence follows every Story, set by
 * Talkativeness. While the driver is standing still only Areas (and Interludes) are told.
 *
 * An Interlude, a Story about an untold part of the Province or Country, fills a long silence: none in Quiet mode,
 * otherwise only after [interludeAfterMillis] (5 minutes) without a Story, counted from the last Story or, if there
 * has been none, from the start of the Trip. After three Interludes in a row that wait doubles, and after six it
 * doubles again; any other Story puts it back. Places and Areas always go first. Immutable.
 */
@ConsistentCopyVisibility
data class StoryScheduler private constructor(
    val talkativeness: Talkativeness,
    private val lastEndedMillis: Long?,
    private val startedMillis: Long,
    private val interludeAfterMillis: Long,
    private val interludesInARow: Int,
) {
    fun withTalkativeness(talkativeness: Talkativeness): StoryScheduler = copy(talkativeness = talkativeness)

    /** A Story of [kind] has just finished (told, skipped or cut off): the quiet gap starts now. */
    fun storyEnded(nowMillis: Long, kind: StoryKind? = null): StoryScheduler = copy(
        lastEndedMillis = nowMillis,
        interludesInARow = if (kind == StoryKind.Interlude) interludesInARow + 1 else 0,
    )

    /**
     * The one to tell now, or null. [busy] means a Story is already being written or spoken.
     * [isRelevant] and [isTold] come from the Passing detector and the Told memory.
     */
    fun next(
        nowMillis: Long,
        candidates: List<Candidate>,
        busy: Boolean,
        standstill: Boolean,
        isRelevant: (Candidate) -> Boolean,
        isTold: (Candidate) -> Boolean,
    ): Candidate? {
        if (busy) return null
        if (lastEndedMillis != null && nowMillis - lastEndedMillis < gapMillis(talkativeness)) return null
        return candidates
            .filter { isRelevant(it) && !isTold(it) && (!standstill || isArea(it.kind) || it.kind == StoryKind.Interlude) }
            .filter { it.kind != StoryKind.Interlude || interludeIsDue(nowMillis) }
            .minWithOrNull(compareBy<Candidate> { rank(it.kind) }.thenByDescending { it.notability })
    }

    private fun interludeIsDue(nowMillis: Long): Boolean {
        if (talkativeness == Talkativeness.Quiet) return false
        val doublings = (interludesInARow / INTERLUDES_PER_DOUBLING).coerceAtMost(MAX_DOUBLINGS)
        return nowMillis - (lastEndedMillis ?: startedMillis) >= interludeAfterMillis shl doublings
    }

    private fun isArea(kind: StoryKind) = kind == StoryKind.Country || kind == StoryKind.Province

    private fun rank(kind: StoryKind): Int = when (kind) {
        StoryKind.Country -> 0
        StoryKind.Province -> 1
        StoryKind.Town, StoryKind.Landmark -> 2
        StoryKind.Interlude -> 3
    }

    private fun gapMillis(talkativeness: Talkativeness): Long = when (talkativeness) {
        Talkativeness.Quiet -> 5 * 60_000L
        Talkativeness.Normal -> 60_000L
        Talkativeness.Chatty -> 20_000L
    }

    companion object {
        private const val INTERLUDES_PER_DOUBLING = 3
        private const val MAX_DOUBLINGS = 2 // 5, 10, then 20 minutes

        operator fun invoke(
            talkativeness: Talkativeness,
            startedMillis: Long = 0,
            interludeAfterMillis: Long = 5 * 60_000L,
        ): StoryScheduler = StoryScheduler(talkativeness, null, startedMillis, interludeAfterMillis, interludesInARow = 0)
    }
}
