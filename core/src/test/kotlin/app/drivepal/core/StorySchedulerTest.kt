package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorySchedulerTest {

    private fun town(key: String, editions: Int, words: Int = 500) = Candidate(key, StoryKind.Town, Notability(editions, words))
    private fun landmark(key: String, editions: Int, words: Int = 500) = Candidate(key, StoryKind.Landmark, Notability(editions, words))
    private val province = Candidate("area/province/South Holland", StoryKind.Province, Notability(0, 0))
    private val country = Candidate("area/country/Netherlands", StoryKind.Country, Notability(0, 0))

    private val second = 1_000L
    private val minute = 60 * second

    private fun scheduler(talkativeness: Talkativeness = Talkativeness.Chatty) = StoryScheduler(talkativeness)

    private fun StoryScheduler.next(
        now: Long,
        vararg candidates: Candidate,
        busy: Boolean = false,
        standstill: Boolean = false,
        relevant: (Candidate) -> Boolean = { true },
        told: (Candidate) -> Boolean = { false },
        nameSaid: (Candidate) -> Boolean = { false },
    ): Candidate? = choose(now, *candidates, busy = busy, standstill = standstill, relevant = relevant, told = told, nameSaid = nameSaid)?.candidate

    private fun StoryScheduler.choose(
        now: Long,
        vararg candidates: Candidate,
        busy: Boolean = false,
        standstill: Boolean = false,
        relevant: (Candidate) -> Boolean = { true },
        told: (Candidate) -> Boolean = { false },
        nameSaid: (Candidate) -> Boolean = { false },
        metres: Map<String, Double> = emptyMap(),
    ): Choice? = next(now, candidates.toList(), busy, standstill, relevant, told, nameSaid) { metres[it.key] ?: 0.0 }

    @Test
    fun `entering a province comes before even a very famous town`() {
        val famous = town("node/1", editions = 90)

        assertEquals(province, scheduler().next(0, famous, province))
    }

    @Test
    fun `the country comes before its province`() {
        assertEquals(country, scheduler().next(0, province, country))
    }

    @Test
    fun `of two places passed together the more notable is told first`() {
        val obscure = landmark("node/1", editions = 3)
        val known = landmark("node/2", editions = 40)

        assertEquals(known, scheduler().next(0, obscure, known))
    }

    @Test
    fun `with equally many languages the longer article is more notable`() {
        val short = town("node/1", editions = 10, words = 200)
        val long = town("node/2", editions = 10, words = 2_000)

        assertEquals(long, scheduler().next(0, short, long))
    }

    @Test
    fun `nothing is told while something is already being written or spoken`() {
        assertNull(scheduler().next(0, town("node/1", 10), busy = true))
    }

    @Test
    fun `the very first story does not have to wait`() {
        assertEquals(town("node/1", 10), scheduler().next(0, town("node/1", 10)))
    }

    @Test
    fun `in chatty mode the next story waits a minute after the last one ended, and only the place's name may be said before`() {
        val ended = scheduler().storyEnded(10 * minute)
        val candidate = town("node/1", 10)

        assertEquals(Choice(candidate, announceOnly = true), ended.choose(10 * minute + 59 * second, candidate))
        assertEquals(Choice(candidate, announceOnly = false), ended.choose(10 * minute + 60 * second, candidate))
    }

    @Test
    fun `stories wait twenty seconds in names mode, five minutes in a normal drive and a minute in a chatty one`() {
        val candidate = town("node/1", 10)
        val names = scheduler(Talkativeness.Names).storyEnded(0)
        val normal = scheduler(Talkativeness.Normal).storyEnded(0)
        val chatty = scheduler(Talkativeness.Chatty).storyEnded(0)

        assertNull(names.choose(19 * second, candidate))
        assertEquals(Choice(candidate, announceOnly = true), names.choose(20 * second, candidate))
        assertEquals(Choice(candidate, announceOnly = true), normal.choose(4 * minute + 59 * second, candidate))
        assertEquals(Choice(candidate, announceOnly = false), normal.choose(5 * minute, candidate))
        assertEquals(Choice(candidate, announceOnly = true), chatty.choose(59 * second, candidate))
        assertEquals(Choice(candidate, announceOnly = false), chatty.choose(60 * second, candidate))
    }

    @Test
    fun `a place that is no longer relevant or was already told is passed over`() {
        val behind = town("node/1", editions = 90)
        val heardBefore = town("node/2", editions = 80)
        val fresh = town("node/3", editions = 5)

        val chosen = scheduler().next(
            0, behind, heardBefore, fresh,
            relevant = { it.key != "node/1" },
            told = { it.key == "node/2" },
        )

        assertEquals(fresh, chosen)
    }

    @Test
    fun `while standing still in a jam towns and landmarks wait but a new province is still announced`() {
        val candidate = town("node/1", 10)

        assertNull(scheduler().next(0, candidate, standstill = true))
        assertEquals(province, scheduler().next(0, candidate, province, standstill = true))
    }

    private val interlude = Candidate("interlude/area/province/South Holland/History", StoryKind.Interlude, Notability(0, 0))

    @Test
    fun `only a chatty drive gets interludes, however long the silence`() {
        assertNull(scheduler(Talkativeness.Normal).next(60 * minute, interlude))
        assertNull(scheduler(Talkativeness.Names).next(60 * minute, interlude))
        assertEquals(interlude, scheduler(Talkativeness.Chatty).next(60 * minute, interlude))
    }

    @Test
    fun `an interlude waits for five minutes of silence after the last story`() {
        val ended = scheduler().storyEnded(0)

        assertNull(ended.next(4 * minute + 59 * second, interlude))
        assertEquals(interlude, ended.next(5 * minute, interlude))
    }

    @Test
    fun `before any story the silence is counted from the start of the trip`() {
        val started = StoryScheduler(Talkativeness.Chatty, startedMillis = 100 * second)

        assertNull(started.next(100 * second + 299 * second, interlude))
        assertEquals(interlude, started.next(100 * second + 300 * second, interlude))
    }

    @Test
    fun `a place or an area is told before an interlude`() {
        val ended = scheduler().storyEnded(0)

        assertEquals(town("node/1", 5), ended.next(10 * minute, interlude, town("node/1", 5)))
        assertEquals(province, ended.next(10 * minute, interlude, province))
    }

    @Test
    fun `an interlude is still told while standing in a jam`() {
        assertEquals(interlude, scheduler().storyEnded(0).next(6 * minute, interlude, standstill = true))
    }

    @Test
    fun `after three interludes in a row the wait doubles, and after six it doubles again`() {
        fun afterInterludes(count: Int) = (1..count).fold(scheduler().storyEnded(0)) { scheduler, _ ->
            scheduler.storyEnded(0, StoryKind.Interlude)
        }

        assertEquals(interlude, afterInterludes(2).next(5 * minute, interlude))
        assertNull(afterInterludes(3).next(9 * minute + 59 * second, interlude))
        assertEquals(interlude, afterInterludes(3).next(10 * minute, interlude))
        assertNull(afterInterludes(6).next(19 * minute + 59 * second, interlude))
        assertEquals(interlude, afterInterludes(6).next(20 * minute, interlude))
        assertEquals(interlude, afterInterludes(30).next(20 * minute, interlude)) // never longer than twenty minutes
    }

    @Test
    fun `a real story puts the wait back to five minutes`() {
        val afterManyInterludes = (1..6).fold(scheduler().storyEnded(0)) { scheduler, _ -> scheduler.storyEnded(0, StoryKind.Interlude) }
        val afterARealStory = afterManyInterludes.storyEnded(0, StoryKind.Town)

        assertEquals(interlude, afterARealStory.next(5 * minute, interlude))
    }

    @Test
    fun `with nothing ready there is nothing to tell`() {
        assertNull(scheduler().next(0))
    }

    @Test
    fun `while a story's quiet gap runs a place in range is only announced, after twenty seconds of silence`() {
        val village = town("node/1", 10)
        val afterStory = scheduler(Talkativeness.Normal).storyEnded(0)

        assertNull(afterStory.choose(10 * second, village))
        assertEquals(Choice(village, announceOnly = true), afterStory.choose(25 * second, village))
        assertEquals(Choice(village, announceOnly = false), afterStory.choose(5 * minute, village))
    }

    @Test
    fun `a name already said is not announced again during the quiet gap`() {
        val village = town("node/1", 10)
        val afterStory = scheduler(Talkativeness.Normal).storyEnded(0)

        assertNull(afterStory.choose(25 * second, village, nameSaid = { true }))
    }

    @Test
    fun `an announcement starts only the twenty second gap, not the quiet gap of a story`() {
        val village = town("node/1", 10)
        val afterName = scheduler(Talkativeness.Normal).announcementEnded(0)

        assertNull(afterName.choose(19 * second, village))
        assertEquals(Choice(village, announceOnly = false), afterName.choose(20 * second, village))
    }

    @Test
    fun `during the quiet gap an interlude is not announced and entering a province is announced first`() {
        val village = town("node/1", 10)
        val interlude = Candidate("interlude/area/country/Netherlands/History", StoryKind.Interlude, Notability(0, 0))
        val afterStory = scheduler(Talkativeness.Chatty).storyEnded(0)

        assertNull(afterStory.choose(30 * second, interlude))
        assertEquals(Choice(province, announceOnly = true), afterStory.choose(30 * second, village, province))
    }

    @Test
    fun `an announcement goes to the closest place, not the most notable, and only once it is within a kilometre`() {
        val famousButFar = town("node/1", editions = 90)
        val obscureButClose = town("node/2", editions = 3)
        val afterStory = scheduler(Talkativeness.Normal).storyEnded(0)

        assertEquals(
            Choice(obscureButClose, announceOnly = true),
            afterStory.choose(25 * second, famousButFar, obscureButClose, metres = mapOf("node/1" to 900.0, "node/2" to 400.0)),
        )
        assertNull(afterStory.choose(25 * second, famousButFar, metres = mapOf("node/1" to 2_800.0)))
        assertEquals(
            Choice(famousButFar, announceOnly = true),
            afterStory.choose(25 * second, famousButFar, metres = mapOf("node/1" to 1_000.0)),
        )
    }

    @Test
    fun `names mode announces only within a kilometre, closest first`() {
        val far = town("node/1", editions = 90)
        val near = town("node/2", editions = 3)
        val names = scheduler(Talkativeness.Names)

        assertNull(names.choose(0, far, metres = mapOf("node/1" to 2_000.0)))
        assertEquals(Choice(near, announceOnly = true), names.choose(0, far, near, metres = mapOf("node/1" to 800.0, "node/2" to 300.0)))
    }

    @Test
    fun `a story prefers a place within a kilometre, and among those the most notable`() {
        val famousButFar = town("node/1", editions = 90)
        val obscureAndClose = town("node/2", editions = 3)
        val knownAndClose = town("node/3", editions = 40)

        assertEquals(
            Choice(obscureAndClose, announceOnly = false),
            scheduler().choose(0, famousButFar, obscureAndClose, metres = mapOf("node/1" to 2_500.0, "node/2" to 400.0)),
        )
        assertEquals(
            Choice(knownAndClose, announceOnly = false),
            scheduler().choose(0, obscureAndClose, knownAndClose, metres = mapOf("node/2" to 300.0, "node/3" to 800.0)),
        )
        assertEquals(
            Choice(famousButFar, announceOnly = false),
            scheduler().choose(0, famousButFar, obscureAndClose, metres = mapOf("node/1" to 2_500.0, "node/2" to 2_900.0)),
        )
    }

    @Test
    fun `a village without an article is only announced, within a kilometre, even when no quiet gap is running`() {
        val village = Candidate("node/5", StoryKind.Town, Notability(0, 0), nameOnly = true)

        assertNull(scheduler(Talkativeness.Normal).choose(0, village, metres = mapOf("node/5" to 2_000.0)))
        assertEquals(Choice(village, announceOnly = true), scheduler(Talkativeness.Normal).choose(0, village, metres = mapOf("node/5" to 600.0)))
    }

    @Test
    fun `a landmark is announced from up to three kilometres, a village only within one`() {
        val landmark = landmark("node/1", editions = 5)
        val village = town("node/2", editions = 5)
        val afterStory = scheduler(Talkativeness.Normal).storyEnded(0)

        assertEquals(Choice(landmark, announceOnly = true), afterStory.choose(25 * second, landmark, metres = mapOf("node/1" to 2_400.0)))
        assertNull(afterStory.choose(25 * second, village, metres = mapOf("node/2" to 2_400.0)))
        assertEquals(Choice(village, announceOnly = true), afterStory.choose(25 * second, landmark, village, metres = mapOf("node/1" to 2_400.0, "node/2" to 900.0)))
        assertEquals(Choice(landmark, announceOnly = true), scheduler(Talkativeness.Names).choose(0, landmark, metres = mapOf("node/1" to 2_400.0)))
    }
}
