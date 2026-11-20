package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryDirectorAnnouncementTest {

    private class FakeStore(var saved: Map<String, Long> = emptyMap()) : ToldStore {
        override fun load() = saved
        override fun save(entries: Map<String, Long>) { saved = entries }
    }

    private val minute = 60_000L
    private val store = FakeStore()
    private var inRange: Set<String> = emptySet()

    private fun director() = StoryDirector(
        store = store,
        scheduler = StoryScheduler(Talkativeness.Chatty, startedMillis = 0, interludeAfterMillis = 60 * minute),
        isPlaceRelevant = { it in inRange },
        wallClockMillis = { 1_000_000_000L },
    )

    private val junction = Subject("node/9", "Kozhencherry", SubjectKind.Junction, Position(9.33, 76.70))
    private val villageWithoutArticle = Subject("node/8", "Eraviperoor", SubjectKind.Town, Position(9.36, 76.64))
    private val windmill = Subject("node/7", "Kortlandse Molen", SubjectKind.Landmark, Position(51.87, 4.67), wikipediaTag = "nl:Kortlandse Molen")
    private val windmillSource = Source("nl", "Kortlandse Molen", "Een molen.", Notability(5, 100))

    private fun StoryDirector.pickAt(nowMillis: Long, talk: Talkativeness) =
        next(nowMillis, talk, busy = false, standstill = false)

    @Test
    fun `a named junction nearby is announced by name and has nothing to tell`() {
        val director = director()
        inRange = setOf("node/9")

        director.placeFound(junction)
        val pick = director.pickAt(0, Talkativeness.Chatty)!!

        assertEquals(StoryKind.Junction, pick.kind)
        assertNull(pick.source)
        assertTrue(pick.announceOnly)
    }

    @Test
    fun `a village without an article is announced by name even though it has no Source`() {
        val director = director()
        inRange = setOf("node/8")

        director.placeFound(villageWithoutArticle)
        val pick = director.pickAt(0, Talkativeness.Normal)!!

        assertEquals("Eraviperoor", pick.title)
        assertTrue(pick.announceOnly)
    }

    @Test
    fun `an announced junction is not announced again, and one already left behind is not announced at all`() {
        val director = director()
        inRange = setOf("node/9")
        director.placeFound(junction)

        val first = director.pickAt(0, Talkativeness.Chatty)!!
        director.storyEnded(first.key, first.title, StoryOutcome.Told, nowMillis = 0)
        val again = director.pickAt(10 * minute, Talkativeness.Chatty)
        inRange = emptySet()

        assertNull(again)
        assertNull(director().apply { placeFound(junction) }.pickAt(0, Talkativeness.Chatty))
    }

    @Test
    fun `names only announces a place that has a Source without telling its story`() {
        val director = director()
        inRange = setOf("node/7")
        director.sourceFound(windmill, windmillSource)

        val pick = director.pickAt(0, Talkativeness.Names)!!

        assertEquals("Kortlandse Molen", pick.title)
        assertTrue(pick.announceOnly)
    }

    @Test
    fun `a place only announced in names mode still gets its story once the driver chooses a talkative mode`() {
        val director = director()
        inRange = setOf("node/7")
        director.sourceFound(windmill, windmillSource)

        val announced = director.pickAt(0, Talkativeness.Names)!!
        director.storyEnded(announced.key, announced.title, StoryOutcome.Told, nowMillis = 0)
        val inNamesAgain = director.pickAt(10 * minute, Talkativeness.Names)
        val inNormal = director.pickAt(10 * minute, Talkativeness.Normal)!!

        assertNull(inNamesAgain)
        assertEquals(false, inNormal.announceOnly)
        assertEquals(windmillSource, inNormal.source)
    }

    @Test
    fun `a place whose story was told is not announced again in names mode`() {
        val director = director()
        inRange = setOf("node/7")
        director.sourceFound(windmill, windmillSource)

        val story = director.pickAt(0, Talkativeness.Normal)!!
        director.storyEnded(story.key, story.title, StoryOutcome.Told, nowMillis = 0)

        assertNull(director.pickAt(10 * minute, Talkativeness.Names))
    }

    @Test
    fun `the Told memory remembers an announcement under its own name, apart from the story`() {
        val director = director()
        inRange = setOf("node/9")
        director.placeFound(junction)

        val pick = director.pickAt(0, Talkativeness.Chatty)!!
        director.storyEnded(pick.key, pick.title, StoryOutcome.Told, nowMillis = 0)

        assertEquals(listOf("name/node/9"), store.saved.keys.toList())
    }
}
