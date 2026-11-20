package app.drivepal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileTripLogStoreTest {

    private fun newFile(): File = Files.createTempDirectory("drivepal").resolve("triplog.json").toFile()

    private val trip = LoggedTrip(
        startedMillis = 1_000, endedMillis = 2_000,
        stories = listOf(
            LoggedStory("Kinderdijk", StoryKind.Town, "Text with \"quotes\", an é, and\na new line.", "en", StoryOutcome.Told, "nl", "Kinderdijk", 1_500),
            LoggedStory("Nothing known", StoryKind.Landmark, "More.", "en", StoryOutcome.Skipped, null, null, 1_800),
        ),
    )

    @Test
    fun `everything about a trip survives being saved and loaded, awkward text included`() {
        val file = newFile()

        FileTripLogStore(file).save(listOf(trip))

        assertEquals(listOf(trip), FileTripLogStore(file).load())
    }

    @Test
    fun `before anything was saved the log is empty`() {
        assertEquals(emptyList(), FileTripLogStore(newFile()).load())
    }

    @Test
    fun `a damaged file counts as an empty log instead of stopping the app`() {
        val file = newFile().also { it.writeText("[{ not json") }

        assertEquals(emptyList(), FileTripLogStore(file).load())
    }

    @Test
    fun `one story with an unknown outcome is skipped, the rest of the log is kept`() {
        val file = newFile()
        FileTripLogStore(file).save(listOf(trip))
        file.writeText(file.readText().replace("\"Skipped\"", "\"SomethingNew\""))

        val loaded = FileTripLogStore(file).load()

        assertEquals(listOf("Kinderdijk"), loaded.single().stories.map { it.title })
    }

    @Test
    fun `saving leaves one complete file and no temporary leftovers`() {
        val file = newFile()

        FileTripLogStore(file).save(listOf(trip))
        FileTripLogStore(file).save(emptyList())

        assertEquals(listOf("triplog.json"), file.parentFile.list()!!.toList())
        assertEquals(emptyList(), FileTripLogStore(file).load())
    }
}
