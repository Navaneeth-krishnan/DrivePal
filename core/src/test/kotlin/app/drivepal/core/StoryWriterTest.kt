package app.drivepal.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class StoryWriterTest {

    private val source = Source(
        language = "en", title = "Kinderdijk",
        text = "Kinderdijk is a village in the Netherlands. It has 19 monumental windmills built around 1740. " +
            "They drain the Alblasserwaard polder. It became a UNESCO World Heritage Site in 1997.",
        notability = Notability(languageEditions = 43, words = 30),
    )

    private val brief = StoryBrief(
        subjectName = "Kinderdijk", kind = StoryKind.Landmark, source = source,
        talkativeness = Talkativeness.Normal, relation = Relation.Ahead, earlierStories = emptyList(),
    )

    /** A writer whose AI server gives these answers in turn; an exception in the list is a failed request. */
    private fun writerAnswering(vararg answers: Any, sent: MutableList<String> = mutableListOf()): StoryWriter {
        val remaining = answers.toMutableList()
        val client = ChatClient(
            transport = { request ->
                sent += request
                when (val next = remaining.removeFirst()) {
                    is IOException -> throw next
                    is String -> if (next.startsWith("{")) next else chatAnswer(next)
                    else -> error("unexpected test answer")
                }
            },
            settings = ChatSettings(model = "test-model"),
        )
        return StoryWriter(client)
    }

    @Test
    fun `a clean story from the model is told as written`() {
        val story = writerAnswering("Look, we're passing Kinderdijk! Nineteen windmills stand here, built around 1740.").write(brief)

        assertEquals(StoryResult.Written("Look, we're passing Kinderdijk! Nineteen windmills stand here, built around 1740."), story)
    }

    @Test
    fun `stage directions, markdown and extra spaces are removed before the story is spoken`() {
        val story = writerAnswering("(Leans over, pointing ahead)  **Look**, we're passing Kinderdijk!  *Nineteen* windmills.").write(brief)

        assertEquals(StoryResult.Written("Look, we're passing Kinderdijk! Nineteen windmills."), story)
    }

    @Test
    fun `a model that spends every token thinking falls back to the start of the article`() {
        val story = writerAnswering(recordedChat("ollama-empty-length.json")).write(brief)

        assertEquals(
            StoryResult.Fallback(text = source.text, language = "en", reason = "The AI server gave no story"),
            story,
        )
    }

    @Test
    fun `an unreachable AI server falls back to the article instead of failing`() {
        val story = writerAnswering(IOException("Connection refused")).write(brief)

        assertEquals(StoryResult.Fallback(text = source.text, language = "en", reason = "Connection refused"), story)
    }

    @Test
    fun `a fallback is kept within the length limit by stopping at a sentence`() {
        val longArticle = source.copy(text = List(40) { "Sentence number $it has a few plain words in it." }.joinToString(" "))

        val story = writerAnswering(IOException("offline")).write(brief.copy(source = longArticle)) as StoryResult.Fallback

        assertTrue(story.text.split(" ").size <= 112)
        assertTrue(story.text.endsWith("in it."))
    }

    @Test
    fun `a story that runs on is cut at a sentence end within the word limit`() {
        val rambling = List(20) { "This sentence has exactly ten plain words in it, nothing more." }.joinToString(" ")

        val story = writerAnswering(rambling).write(brief) as StoryResult.Written

        assertTrue(story.text.split(" ").size <= 112)
        assertTrue(story.text.endsWith("nothing more."))
    }

    @Test
    fun `a year that is not in the article gets one retry that names the mistake`() {
        val sent = mutableListOf<String>()
        val writer = writerAnswering(
            "The windmills were built around 1743 to drain the polder.",
            "The windmills were built around 1740 to drain the polder.",
            sent = sent,
        )

        val story = writer.write(brief)

        assertEquals(StoryResult.Written("The windmills were built around 1740 to drain the polder."), story)
        assertEquals(2, sent.size)
        assertTrue("1743" in sent[1])
    }

    @Test
    fun `if the retry also invents numbers the article itself is told instead`() {
        val story = writerAnswering(
            "The windmills date from 1743.",
            "The windmills date from 1745.",
        ).write(brief)

        assertEquals(
            StoryResult.Fallback(text = source.text, language = "en", reason = "The story contained numbers that are not in the article: 1745"),
            story,
        )
    }

    @Test
    fun `numbers that do appear in the article are fine`() {
        val story = writerAnswering("It has 19 windmills, from 1740, and was honoured in 1997.").write(brief)

        assertEquals(StoryResult.Written("It has 19 windmills, from 1740, and was honoured in 1997."), story)
    }

    @Test
    fun `a quiet drive gets no interlude, so nothing is written`() {
        val interlude = brief.copy(kind = StoryKind.Interlude, talkativeness = Talkativeness.Quiet, relation = null)

        assertNull(writerAnswering("never asked").write(interlude))
    }

    @Test
    fun `a legend the article never mentions gets one retry that names the problem`() {
        val sent = mutableListOf<String>()
        val writer = writerAnswering(
            "A legend has it that giants dug the polder, and the windmills date from 1740.",
            "The windmills date from 1740 and drain the polder.",
            sent = sent,
        )

        val story = writer.write(brief)

        assertEquals(StoryResult.Written("The windmills date from 1740 and drain the polder."), story)
        assertTrue("legend" in sent[1])
    }

    @Test
    fun `a legend is fine when the article tells it`() {
        val withLegend = brief.copy(source = source.copy(text = source.text + " According to a local legend, giants dug the polder."))

        val story = writerAnswering("Local legend says giants dug the polder.").write(withLegend)

        assertEquals(StoryResult.Written("Local legend says giants dug the polder."), story)
    }

    @Test
    fun `a legend is fine when a Dutch article tells it in Dutch`() {
        val dutch = brief.copy(source = source.copy(language = "nl", text = "Volgens de legende groeven reuzen de polder. De molens dateren uit 1740."))

        val story = writerAnswering("Legend says giants dug the polder, and the mills date from 1740.").write(dutch)

        assertEquals(StoryResult.Written("Legend says giants dug the polder, and the mills date from 1740."), story)
    }

    @Test
    fun `if the retry still invents a legend the article itself is told`() {
        val story = writerAnswering(
            "A legend says the mill was moved by a storm.",
            "Folklore says the mill was moved by a storm.",
        ).write(brief)

        assertEquals(
            StoryResult.Fallback(text = source.text, language = "en", reason = "The story contained a legend that the article does not tell"),
            story,
        )
    }
}

private fun chatAnswer(text: String): String = buildJsonObject {
    put("choices", buildJsonArray {
        add(buildJsonObject {
            put("index", 0)
            put("message", buildJsonObject { put("role", "assistant"); put("content", text) })
            put("finish_reason", "stop")
        })
    })
}.toString()

private fun recordedChat(name: String): String =
    StoryWriterTest::class.java.getResource("/real/$name")!!.readText()
