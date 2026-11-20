package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryDirectorTest {

    private class FakeStore(var saved: Map<String, Long> = emptyMap()) : ToldStore {
        override fun load() = saved
        override fun save(entries: Map<String, Long>) { saved = entries }
    }

    private val minute = 60_000L
    private val store = FakeStore()
    private var placesInRange: Set<String> = emptySet()

    private fun director(talkativeness: Talkativeness = Talkativeness.Normal, store: ToldStore = this.store) = StoryDirector(
        store = store,
        scheduler = StoryScheduler(talkativeness, startedMillis = 0, interludeAfterMillis = minute),
        isPlaceRelevant = { it in placesInRange },
        wallClockMillis = { 1_000_000_000L },
    )

    private fun article(vararg sections: String) =
        "The lead of the article.\n\n" + sections.joinToString("\n\n") { title -> "== $title ==\n" + List(100) { "word$it" }.joinToString(" ") }

    private fun source(vararg sections: String, title: String = "An area") =
        Source("en", title, article(*sections), Notability(50, 500))

    private val southHolland = Area("Netherlands", "South Holland", "nl:Zuid-Holland", "Q694")
    private val utrecht = Area("Netherlands", "Utrecht")

    /** Starts a trip in [area], looks up what the director asks for, and gives it a source for each. */
    private fun StoryDirector.startIn(area: Area, vararg sections: String): List<Subject> {
        val asked = areaChanged(area, events = emptyList())
        asked.forEach { sourceFound(it, source(*sections, title = it.name)) }
        return asked
    }

    private fun place(id: String, editions: Int) = Subject(id, "Place $id", SubjectKind.Town, Position(52.0, 4.0), wikipediaTag = "nl:Place $id")
        .also { placesInRange += id }
        .let { it to Source("nl", it.name, "Article of ${it.name}", Notability(editions, 500)) }

    @Test
    fun `the area a trip starts in is looked up, for interludes, but is not told as a story`() {
        val director = director()

        val asked = director.startIn(southHolland)

        assertEquals(listOf("area/country/Netherlands", "area/province/Netherlands/South Holland"), asked.map { it.id })
        assertNull(director.next(nowMillis = 0, Talkativeness.Normal, busy = false, standstill = false))
    }

    @Test
    fun `entering a province tells its story once its article has been found`() {
        val director = director().apply { startIn(southHolland) }

        val asked = director.areaChanged(utrecht, listOf(AreaEvent.EnteredProvince("Utrecht")))
        director.sourceFound(asked.single(), source(title = "Utrecht"))
        val pick = director.next(nowMillis = 0, Talkativeness.Normal, busy = false, standstill = false)

        assertEquals("area/province/Netherlands/Utrecht", pick?.key)
        assertEquals(StoryKind.Province, pick?.kind)
    }

    @Test
    fun `the same province name in two countries is two different areas`() {
        val dutchLimburg = Area("Netherlands", "Limburg")
        val belgianLimburg = Area("Belgium", "Limburg")
        val director = director().apply { startIn(dutchLimburg) }

        val asked = director.areaChanged(
            belgianLimburg,
            listOf(AreaEvent.EnteredCountry("Belgium"), AreaEvent.EnteredProvince("Limburg")),
        )

        // Both new areas need their own lookup: the Dutch province's article must not stand in for the Belgian one.
        assertEquals(listOf("area/country/Belgium", "area/province/Belgium/Limburg"), asked.map { it.id })
        asked.forEach { director.sourceFound(it, Source("en", it.name, "Article of ${it.id}", Notability(50, 500))) }
        val first = director.next(0, Talkativeness.Normal, busy = false, standstill = false)!!
        director.storyEnded(first.key, first.title, StoryOutcome.Told, nowMillis = 0)
        val second = director.next(minute, Talkativeness.Normal, busy = false, standstill = false)!!

        assertEquals("area/country/Belgium", first.key)
        assertEquals("Article of area/province/Belgium/Limburg", second.source.text)
    }

    @Test
    fun `an area's article is looked up only once while the driver stays in it`() {
        val director = director().apply { startIn(southHolland) }

        assertEquals(emptyList(), director.areaChanged(southHolland, emptyList()))
    }

    @Test
    fun `interludes come from the province's article, then the country's, and a told section is never repeated`() {
        val director = director().apply { startIn(southHolland, "History", "Geography") }
        val told = mutableListOf<String>()

        var now = 2 * minute
        while (true) {
            val pick = director.next(now, Talkativeness.Normal, busy = false, standstill = false) ?: break
            told += pick.title
            director.storyEnded(pick.key, pick.title, StoryOutcome.Told, now)
            now += 10 * minute
        }

        assertEquals(
            listOf("South Holland: History", "South Holland: Geography", "Netherlands: History", "Netherlands: Geography"),
            told,
        )
    }

    @Test
    fun `a section told on an earlier trip is not offered again after a restart`() {
        director().apply { startIn(southHolland, "History", "Geography") }.also { first ->
            val pick = first.next(2 * minute, Talkativeness.Normal, busy = false, standstill = false)!!
            first.storyEnded(pick.key, pick.title, StoryOutcome.Told, 2 * minute)
        }

        val afterRestart = director().apply { startIn(southHolland, "History", "Geography") }
        val pick = afterRestart.next(2 * minute, Talkativeness.Normal, busy = false, standstill = false)

        assertEquals("South Holland: Geography", pick?.title)
    }

    @Test
    fun `an area that has been left no longer gives interludes, and its late article is ignored`() {
        val director = director().apply { startIn(southHolland, "History") }
        director.areaChanged(utrecht, listOf(AreaEvent.EnteredProvince("Utrecht")))

        val lateArrival = Subject("area/province/Netherlands/South Holland", "South Holland", SubjectKind.Province, Position(0.0, 0.0))
        director.sourceFound(lateArrival, source("History"))
        val pick = director.next(2 * minute, Talkativeness.Normal, busy = false, standstill = false)

        // Only the country (still current) can give an interlude; nothing from the province that was left.
        assertEquals("Netherlands: History", pick?.title)
    }

    @Test
    fun `there are no interludes on a quiet drive`() {
        val director = director(Talkativeness.Quiet).apply { startIn(southHolland, "History") }

        assertNull(director.next(60 * minute, Talkativeness.Quiet, busy = false, standstill = false))
    }

    @Test
    fun `of two places in range the more notable is told first, and one that has fallen behind is forgotten`() {
        val director = director().apply { startIn(southHolland) }
        val (obscure, obscureSource) = place("node/1", editions = 3)
        val (known, knownSource) = place("node/2", editions = 40)
        director.sourceFound(obscure, obscureSource)
        director.sourceFound(known, knownSource)

        val first = director.next(0, Talkativeness.Normal, busy = false, standstill = false)
        placesInRange = setOf("node/1") // the famous one is now behind
        val second = director.next(0, Talkativeness.Normal, busy = false, standstill = false)

        assertEquals("node/2", first?.key)
        assertEquals("node/1", second?.key)
    }

    @Test
    fun `a story that was told is not told again, but one that was only interrupted can come back`() {
        val director = director().apply { startIn(southHolland) }
        val (town, townSource) = place("node/1", editions = 10)
        director.sourceFound(town, townSource)

        val first = director.next(0, Talkativeness.Normal, busy = false, standstill = false)!!
        director.storyEnded(first.key, first.title, StoryOutcome.Interrupted, 0)
        val again = director.next(minute, Talkativeness.Normal, busy = false, standstill = false)!!
        director.storyEnded(again.key, again.title, StoryOutcome.Told, minute + 1)

        assertEquals("node/1", again.key)
        assertNull(director.next(10 * minute, Talkativeness.Normal, busy = false, standstill = false))
    }

    @Test
    fun `a story that could not be spoken is dropped for this trip instead of being written again and again`() {
        val director = director().apply { startIn(southHolland, "History", "Geography") }
        val first = director.next(2 * minute, Talkativeness.Normal, busy = false, standstill = false)!!

        director.storyEnded(first.key, first.title, StoryOutcome.Dropped, 2 * minute)
        val next = director.next(10 * minute, Talkativeness.Normal, busy = false, standstill = false)

        assertTrue(next != null && next.key != first.key)
    }

    @Test
    fun `the titles told on this trip are remembered for framing the next story`() {
        val director = director().apply { startIn(southHolland, "History") }
        val first = director.next(2 * minute, Talkativeness.Normal, busy = false, standstill = false)!!

        director.storyEnded(first.key, first.title, StoryOutcome.Told, 2 * minute)

        assertEquals(listOf("South Holland: History"), director.earlierTitles(3))
    }
}
