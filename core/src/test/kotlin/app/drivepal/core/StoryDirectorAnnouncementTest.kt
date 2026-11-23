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
    private var metres: Map<String, Double> = emptyMap()

    private fun director() = StoryDirector(
        store = store,
        scheduler = StoryScheduler(Talkativeness.Chatty, startedMillis = 0, interludeAfterMillis = 60 * minute),
        isPlaceRelevant = { it in inRange },
        distanceMeters = { metres[it.id] ?: 0.0 },
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

    private val chapel = Subject("node/6", "Kapel", SubjectKind.Landmark, Position(51.88, 4.68), wikipediaTag = "nl:Kapel")
    private val chapelSource = Source("nl", "Kapel", "Een kapel.", Notability(1, 100))

    @Test
    fun `during the quiet gap of a story a place is only announced, and its story follows later without announcing it twice`() {
        val director = director()
        inRange = setOf("node/7", "node/6")
        director.sourceFound(windmill, windmillSource)
        director.sourceFound(chapel, chapelSource)

        val first = director.pickAt(0, Talkativeness.Normal)!!
        director.storyEnded(first.key, first.title, StoryOutcome.Told, nowMillis = 0)
        val duringTheGap = director.pickAt(30_000, Talkativeness.Normal)!!
        director.storyEnded(duringTheGap.key, duringTheGap.title, StoryOutcome.Told, nowMillis = 30_000)
        val afterTheGap = director.pickAt(5 * minute + 30_000, Talkativeness.Normal)!!

        assertEquals("Kortlandse Molen", first.title)
        assertEquals(false, first.announceOnly)
        assertEquals("Kapel", duringTheGap.title)
        assertTrue(duringTheGap.announceOnly)
        assertEquals("Kapel", afterTheGap.title)
        assertEquals(false, afterTheGap.announceOnly)
        assertEquals(chapelSource, afterTheGap.source)
        assertTrue(afterTheGap.nameAlreadySaid)
        assertEquals(false, first.nameAlreadySaid)
    }

    @Test
    fun `an announcement starts only the short silence, so a story can follow twenty seconds later`() {
        val director = director()
        inRange = setOf("node/8")
        director.placeFound(villageWithoutArticle)

        val name = director.pickAt(0, Talkativeness.Normal)!!
        inRange = setOf("node/8", "node/7")
        director.sourceFound(windmill, windmillSource)
        director.storyEnded(name.key, name.title, StoryOutcome.Told, nowMillis = 0)
        val tooSoon = director.pickAt(10_000, Talkativeness.Normal)
        val story = director.pickAt(25_000, Talkativeness.Normal)!!

        assertEquals("Eraviperoor", name.title)
        assertNull(tooSoon)
        assertEquals("Kortlandse Molen", story.title)
        assertEquals(false, story.announceOnly)
    }

    @Test
    fun `a name is said for the closest place in range, and not before it is within a kilometre`() {
        val director = director()
        inRange = setOf("node/7")
        metres = mapOf("node/7" to 500.0)
        director.sourceFound(windmill, windmillSource)
        val story = director.pickAt(0, Talkativeness.Normal)!!
        director.storyEnded(story.key, story.title, StoryOutcome.Told, nowMillis = 0)

        inRange = setOf("node/6", "node/8")
        director.sourceFound(chapel, chapelSource)
        director.placeFound(villageWithoutArticle)
        metres = mapOf("node/6" to 900.0, "node/8" to 2_800.0)
        val chapelFirst = director.pickAt(30_000, Talkativeness.Normal)!!
        director.storyEnded(chapelFirst.key, chapelFirst.title, StoryOutcome.Told, nowMillis = 30_000)
        val villageTooFar = director.pickAt(60_000, Talkativeness.Normal)
        metres = mapOf("node/6" to 1_500.0, "node/8" to 700.0)
        val villageNow = director.pickAt(60_000, Talkativeness.Normal)!!

        assertEquals("Kapel", chapelFirst.title)
        assertTrue(chapelFirst.announceOnly)
        assertNull(villageTooFar)
        assertEquals("Eraviperoor", villageNow.title)
        assertTrue(villageNow.announceOnly)
    }

    @Test
    fun `a story is told about a place within a kilometre before a more notable one further out`() {
        val director = director()
        inRange = setOf("node/7", "node/6")
        metres = mapOf("node/7" to 2_500.0, "node/6" to 400.0)
        director.sourceFound(windmill, windmillSource)
        director.sourceFound(chapel, chapelSource)

        val pick = director.pickAt(0, Talkativeness.Normal)!!

        assertEquals("Kapel", pick.title)
        assertEquals(false, pick.announceOnly)
        assertEquals(chapelSource, pick.source)
    }

    @Test
    fun `a place whose article is too thin for a story is still announced by name`() {
        val director = director()
        inRange = setOf("node/7")
        metres = mapOf("node/7" to 600.0)

        director.lookupDone(windmill, SourceLookup.NotStoryWorthy)
        val pick = director.pickAt(0, Talkativeness.Normal)!!

        assertEquals("Kortlandse Molen", pick.title)
        assertTrue(pick.announceOnly)
        assertNull(pick.source)
    }

    @Test
    fun `a failed lookup says nothing yet, and a found article becomes a story`() {
        val director = director()
        inRange = setOf("node/7", "node/6")
        metres = mapOf("node/7" to 600.0, "node/6" to 700.0)

        director.lookupDone(windmill, SourceLookup.Unavailable("HTTP 503"))
        director.lookupDone(chapel, SourceLookup.Found(chapelSource))
        val pick = director.pickAt(0, Talkativeness.Normal)!!

        assertEquals("Kapel", pick.title)
        assertEquals(false, pick.announceOnly)
        assertEquals(chapelSource, pick.source)
    }

    @Test
    fun `a landmark two and a half kilometres away is announced with its distance`() {
        val director = director()
        inRange = setOf("node/7")
        metres = mapOf("node/7" to 2_500.0)
        director.sourceFound(windmill, windmillSource)

        val pick = director.pickAt(0, Talkativeness.Names)!!

        assertEquals("Kortlandse Molen", pick.title)
        assertTrue(pick.announceOnly)
        assertEquals(2_500.0, pick.distanceMeters)
    }

    @Test
    fun `an Area named by a Region pack is announced by name, and no article is looked up for it`() {
        val director = director()

        val atTheStart = director.areaChanged(Area("India", "Kerala"), emptyList(), fromPack = true)
        val acrossTheBorder = director.areaChanged(
            Area("India", "Tamil Nadu"), listOf(AreaEvent.EnteredProvince("Tamil Nadu")), fromPack = true,
        )
        val pick = director.pickAt(0, Talkativeness.Normal)!!

        assertEquals(emptyList<Subject>(), atTheStart)
        assertEquals(emptyList<Subject>(), acrossTheBorder)
        assertEquals(StoryKind.Province, pick.kind)
        assertEquals("Tamil Nadu", pick.title)
        assertTrue(pick.announceOnly)
        assertNull(pick.source)
    }

    @Test
    fun `a state entered with a Malayalam name is announced with that name when speaking Malayalam`() {
        val director = director()
        val tamilNadu = Area("India", "Tamil Nadu", provinceNames = mapOf("ml" to "തമിഴ്നാട്"))

        director.areaChanged(Area("India", "Kerala"), emptyList(), fromPack = true)
        director.areaChanged(tamilNadu, listOf(AreaEvent.EnteredProvince("Tamil Nadu", names = mapOf("ml" to "തമിഴ്നാട്"))), fromPack = true)
        val pick = director.pickAt(0, Talkativeness.Names)!!

        assertEquals("തമിഴ്നാട്", pick.subject.nameIn(SpokenLanguage.Malayalam))
        assertEquals("Tamil Nadu", pick.subject.nameIn(SpokenLanguage.English))
    }
}
