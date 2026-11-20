package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class InterludeSectionsTest {

    private val southHolland: String = InterludeSectionsTest::class.java
        .getResource("/real/wikipedia-en-south-holland.json")!!.readText()
        .let { raw -> kotlinx.serialization.json.Json.parseToJsonElement(raw) }
        .let { json ->
            (json as kotlinx.serialization.json.JsonObject)["query"]!!.let { it as kotlinx.serialization.json.JsonObject }["pages"]!!
                .let { it as kotlinx.serialization.json.JsonArray }[0].let { it as kotlinx.serialization.json.JsonObject }["extract"]!!
                .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        }

    private fun words(section: Section) = section.text.split(Regex("\\s+")).count { it.isNotEmpty() }

    @Test
    fun `a real article gives its history, geography and economy, and drops what is too short or only links`() {
        val sections = InterludeSections.of(southHolland)

        assertEquals(listOf("History", "Geography", "Economy"), sections.map { it.title })
        // Religion (40 words), Notable residents (51), See also, References and External links are not worth a Story.
        assertEquals(listOf(1079, 646, 235), sections.map(::words))
    }

    @Test
    fun `the opening of the article is not a section, because the Area's own Story already used it`() {
        assertFalse(InterludeSections.of(southHolland).any { "is a province of the Netherlands" in it.text })
    }

    @Test
    fun `heading markup is not left in the text to be read aloud`() {
        assertFalse(InterludeSections.of(southHolland).any { "==" in it.text })
    }

    @Test
    fun `sections already told are not offered again, and when all are told there is nothing left`() {
        val told = mutableSetOf<String>()
        val offered = mutableListOf<String>()

        while (true) {
            val next = InterludeSections.next(southHolland) { it.title in told } ?: break
            offered += next.title
            told += next.title
        }

        assertEquals(listOf("History", "Geography", "Economy"), offered)
        assertNull(InterludeSections.next(southHolland) { true })
    }

    @Test
    fun `boilerplate sections are skipped however long they are`() {
        val long = List(200) { "word$it" }.joinToString(" ")
        val article = "Lead text.\n\n== References ==\n$long\n\n== Further reading ==\n$long\n\n== Culture ==\n$long"

        assertEquals(listOf("Culture"), InterludeSections.of(article).map { it.title })
    }
}
