package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripLogTest {

    private class FakeStore(var saved: List<LoggedTrip> = emptyList()) : TripLogStore {
        override fun load() = saved
        override fun save(trips: List<LoggedTrip>) { saved = trips }
    }

    private val hour = 3_600_000L

    private fun story(title: String, outcome: StoryOutcome = StoryOutcome.Told, at: Long = 0, sourceTitle: String? = title) = LoggedStory(
        title = title, kind = StoryKind.Town, text = "The story of $title.", language = "en", outcome = outcome,
        sourceLanguage = "nl", sourceTitle = sourceTitle, atMillis = at,
    )

    private fun TripLog.trip(start: Long, vararg stories: LoggedStory): TripLog =
        stories.fold(startTrip(start)) { log, story -> log.recording(story) }.endTrip(start + hour)

    @Test
    fun `the stories of a trip, told or skipped, are there after the app restarts`() {
        val store = FakeStore()
        TripLog.load(store).trip(0, story("Kinderdijk"), story("Ridderkerk", StoryOutcome.Skipped))

        val trips = TripLog.load(store).trips

        assertEquals(1, trips.size)
        assertEquals(listOf("Kinderdijk" to StoryOutcome.Told, "Ridderkerk" to StoryOutcome.Skipped), trips.single().stories.map { it.title to it.outcome })
    }

    @Test
    fun `a trip in which nothing was told is not kept`() {
        val log = TripLog.load(FakeStore()).startTrip(0).endTrip(hour)

        assertEquals(emptyList(), log.trips)
    }

    @Test
    fun `the newest trip comes first`() {
        val log = TripLog.load(FakeStore()).trip(0, story("Old")).trip(10 * hour, story("New"))

        assertEquals(listOf("New", "Old"), log.trips.map { it.stories.single().title })
    }

    @Test
    fun `the fifty-first trip pushes out the oldest`() {
        val log = (0..50).fold(TripLog.load(FakeStore())) { log, i -> log.trip(i * 2 * hour, story("Trip $i")) }

        assertEquals(50, log.trips.size)
        assertEquals("Trip 50", log.trips.first().stories.single().title)
        assertEquals("Trip 1", log.trips.last().stories.single().title)
    }

    @Test
    fun `a story that arrives with no trip running is ignored`() {
        val log = TripLog.load(FakeStore()).recording(story("Late"))

        assertEquals(emptyList(), log.trips)
    }

    @Test
    fun `clearing the log forgets every trip, also in the store`() {
        val store = FakeStore()
        val cleared = TripLog.load(store).trip(0, story("A")).cleared()

        assertEquals(emptyList(), cleared.trips)
        assertEquals(emptyList(), store.saved)
    }

    @Test
    fun `a trip that was cut short by the app being killed is still shown`() {
        val store = FakeStore()
        TripLog.load(store).startTrip(0).recording(story("Kinderdijk")) // never ended

        val trips = TripLog.load(store).trips

        assertEquals("Kinderdijk", trips.single().stories.single().title)
        assertNull(trips.single().endedMillis)
    }

    @Test
    fun `the source link points at the Wikipedia article, written the way Wikipedia writes its addresses`() {
        assertEquals("https://nl.wikipedia.org/wiki/Kinderdijk", story("Kinderdijk").sourceUrl)
        assertEquals("https://en.wikipedia.org/wiki/Lek_(river)", story("Lek", sourceTitle = "Lek (river)").copy(sourceLanguage = "en").sourceUrl)
        assertEquals("https://nl.wikipedia.org/wiki/Huis_van_Zessen_(Alblasserdam)", story("x", sourceTitle = "Huis van Zessen (Alblasserdam)").sourceUrl)
        assertEquals("https://en.wikipedia.org/wiki/AT%26T_%3F", story("x", sourceTitle = "AT&T ?").copy(sourceLanguage = "en").sourceUrl)
        assertNull(story("No source", sourceTitle = null).sourceUrl)
    }

    @Test
    fun `a story is logged without the announcement that was spoken before it`() {
        val utterance = Utterance(
            id = "node/1", text = "The mill grinds grain.", language = "en", title = "Mill", kind = StoryKind.Landmark,
            sourceLanguage = "nl", sourceTitle = "De Hoop", announcement = "Coming up: Mill.",
        )

        val logged = LoggedStory.ofOrNull(utterance, StoryOutcome.Told, atMillis = 5)

        assertEquals("The mill grinds grain.", logged?.text)
    }

    @Test
    fun `a name said on its own is not a story and is not logged`() {
        val nameOnly = Utterance("name/node/9", "", "en", title = "Kozhencherry", announcement = "Approaching Kozhencherry junction.")

        assertEquals(null, LoggedStory.ofOrNull(nameOnly, StoryOutcome.Told, atMillis = 5))
    }

    @Test
    fun `an interlude is logged under the article it was taken from, not the section`() {
        val utterance = Utterance(
            id = "interlude/area/province/Netherlands/South Holland/History", text = "History text.", language = "en",
            title = "South Holland: History", kind = StoryKind.Interlude, sourceLanguage = "en", sourceTitle = "South Holland",
        )

        val logged = LoggedStory.of(utterance, StoryOutcome.Told, atMillis = 5)

        assertEquals("https://en.wikipedia.org/wiki/South_Holland", logged.sourceUrl)
        assertEquals("South Holland: History", logged.title)
        assertEquals(StoryKind.Interlude, logged.kind)
    }
}
