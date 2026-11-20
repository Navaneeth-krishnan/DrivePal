package app.drivepal.core

/** Something that could be told next. [key] is stable across Trips, so the Told memory can recognise it. */
data class Candidate(val key: String, val kind: StoryKind, val notability: Notability)

/**
 * Decides which Story to write and tell next, and when: one at a time, just in time. The order is entering a
 * Country, then a Province, then Towns and Landmarks by Notability. A gap of silence follows every Story, set by
 * Talkativeness. While the driver is standing still only Areas are announced. Immutable.
 */
@ConsistentCopyVisibility
data class StoryScheduler private constructor(
    val talkativeness: Talkativeness,
    private val lastEndedMillis: Long?,
) {
    fun withTalkativeness(talkativeness: Talkativeness): StoryScheduler = copy(talkativeness = talkativeness)

    /** A Story has just finished (told, skipped or cut off): the quiet gap starts now. */
    fun storyEnded(nowMillis: Long): StoryScheduler = copy(lastEndedMillis = nowMillis)

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
            .filter { isRelevant(it) && !isTold(it) && (!standstill || isArea(it.kind)) }
            .minWithOrNull(compareBy<Candidate> { rank(it.kind) }.thenByDescending { it.notability })
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
        operator fun invoke(talkativeness: Talkativeness): StoryScheduler = StoryScheduler(talkativeness, lastEndedMillis = null)
    }
}
