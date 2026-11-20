package app.drivepal.core

/** Where [ToldMemory] keeps what the driver has heard, so it survives the app closing. */
interface ToldStore {
    /** Story key to the time it was told, in milliseconds since 1970. */
    fun load(): Map<String, Long>

    fun save(entries: Map<String, Long>)
}

/**
 * Remembers which Stories the driver has already heard, across Trips, for 30 days. A Story that was read to the
 * end or Skipped is Told; one that was Interrupted or Dropped is not, so it can still come back.
 * Every change is saved straight away. Immutable.
 */
class ToldMemory private constructor(private val store: ToldStore, private val toldAt: Map<String, Long>) {

    /** [nowMillis] must come from the wall clock, since this outlives a single Trip. */
    fun isTold(key: String, nowMillis: Long): Boolean = toldAt[key]?.let { nowMillis - it <= REMEMBERED_FOR_MILLIS } ?: false

    fun recording(outcome: StoryOutcome, key: String, nowMillis: Long): ToldMemory {
        if (outcome != StoryOutcome.Told && outcome != StoryOutcome.Skipped) return this
        val kept = toldAt.filterValues { nowMillis - it <= REMEMBERED_FOR_MILLIS } + (key to nowMillis)
        store.save(kept)
        return ToldMemory(store, kept)
    }

    companion object {
        private const val REMEMBERED_FOR_MILLIS = 30 * 24 * 60 * 60 * 1000L

        fun load(store: ToldStore): ToldMemory = ToldMemory(store, store.load())
    }
}
