package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryPromptTest {

    private val source = Source(
        language = "nl", title = "Kinderdijk", text = "Kinderdijk is een dorp met negentien windmolens.",
        notability = Notability(languageEditions = 43, words = 757),
    )

    private fun brief(
        kind: StoryKind = StoryKind.Landmark,
        talkativeness: Talkativeness = Talkativeness.Normal,
        relation: Relation? = Relation.Ahead,
        earlierStories: List<String> = emptyList(),
        source: Source = this.source,
    ) = StoryBrief("Kinderdijk", kind, source, talkativeness, relation, earlierStories)

    private fun system(brief: StoryBrief) = StoryPrompt.messages(brief)!!.first { it.role == "system" }.content
    private fun user(brief: StoryBrief) = StoryPrompt.messages(brief)!!.first { it.role == "user" }.content

    @Test
    fun `a landmark story in normal mode is asked to run 30 to 45 seconds, about 75 to 112 words`() {
        assertTrue("between 75 and 112 words" in system(brief()))
    }

    @Test
    fun `a chatty landmark story is longer and a quiet one shorter`() {
        assertTrue("between 112 and 150 words" in system(brief(talkativeness = Talkativeness.Chatty)))
        assertTrue("between 50 and 75 words" in system(brief(talkativeness = Talkativeness.Quiet)))
    }

    @Test
    fun `entering a province in normal mode is asked to run 60 to 90 seconds, about 150 to 225 words`() {
        assertTrue("between 150 and 225 words" in system(brief(kind = StoryKind.Province, relation = null)))
    }

    @Test
    fun `a quiet drive gets no interludes at all`() {
        assertNull(StoryPrompt.messages(brief(kind = StoryKind.Interlude, talkativeness = Talkativeness.Quiet, relation = null)))
    }

    @Test
    fun `the model is told to use only the source and to write plain speakable English`() {
        val rules = system(brief())

        assertTrue("ONLY facts" in rules)
        assertTrue("in English" in rules)
        assertTrue("no stage directions" in rules)
        assertTrue("legend" in rules)
    }

    @Test
    fun `the model is not allowed to say which side of the road something is on`() {
        val rules = system(brief())

        assertTrue("left or right" in rules)
        assertFalse("on your left" in rules)
    }

    @Test
    fun `the model is told to vary its openings and not to start with the same stock phrase every time`() {
        val rules = system(brief())

        assertTrue("Keep your eyes on the road" in rules)
        assertTrue("different way" in rules)
    }

    @Test
    fun `the article, its language and its title are handed over as the source`() {
        val message = user(brief())

        assertTrue("Kinderdijk is een dorp met negentien windmolens." in message)
        assertTrue("nl" in message && "Kinderdijk" in message)
    }

    @Test
    fun `a huge article is cut down so the request stays small and fast`() {
        val huge = source.copy(text = List(30_000) { "word$it" }.joinToString(" "))

        val message = user(brief(source = huge))

        assertTrue(message.length < 8_000)
        assertTrue("word0 word1 word2" in message)
    }

    @Test
    fun `a subject already passed asks for a lead-in, one still ahead does not`() {
        assertTrue("Back there, behind us" in system(brief(relation = Relation.Behind)))
        assertFalse("Back there, behind us" in system(brief(relation = Relation.Ahead)))
    }

    @Test
    fun `earlier stories on the trip are offered for framing, and only when there are some`() {
        val withEarlier = system(brief(earlierStories = listOf("Ridderkerk", "Alblasserdam")))
        val withoutEarlier = system(brief())

        assertTrue("Ridderkerk" in withEarlier && "Alblasserdam" in withEarlier)
        assertFalse("Earlier on this drive" in withoutEarlier)
    }

    @Test
    fun `the prompt is made of one system message and one user message`() {
        assertEquals(listOf("system", "user"), StoryPrompt.messages(brief())!!.map { it.role })
    }
}
