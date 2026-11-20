package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun `boilerplate sections are skipped however long they are`() {
        val long = List(200) { "word$it" }.joinToString(" ")
        val article = "Lead text.\n\n== References ==\n$long\n\n== Further reading ==\n$long\n\n== Culture ==\n$long"

        assertEquals(listOf("Culture"), InterludeSections.of(article).map { it.title })
    }

    @Test
    fun `boilerplate sections in Dutch, German, French and Spanish are skipped too`() {
        val long = List(200) { "woord$it" }.joinToString(" ")
        val article = "Inleiding.\n\n== Zie ook ==\n$long\n\n== Externe links ==\n$long\n\n== Literatuur ==\n$long\n\n" +
            "== Einzelnachweise ==\n$long\n\n== Voir aussi ==\n$long\n\n== Véase también ==\n$long\n\n== Geschiedenis ==\n$long"

        assertEquals(listOf("Geschiedenis"), InterludeSections.of(article).map { it.title })
    }
}
