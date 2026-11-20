package app.drivepal.core

enum class Talkativeness { Quiet, Normal, Chatty }

enum class StoryKind { Landmark, Town, Province, Country, Interlude }

/** Where a subject is relative to the driver. Areas and Interludes have none. */
enum class Relation { Ahead, Behind }

/** Everything the writer needs to know to tell one Story. */
data class StoryBrief(
    val subjectName: String,
    val kind: StoryKind,
    val source: Source,
    val talkativeness: Talkativeness,
    val relation: Relation?,
    /** Short names of Stories already told on this Trip, offered for Framing. */
    val earlierStories: List<String>,
)

/** How long a Story should be, in words, for its kind and the driver's Talkativeness. Null if there should be none. */
fun wordRange(kind: StoryKind, talkativeness: Talkativeness): IntRange? {
    val seconds = when (talkativeness) {
        Talkativeness.Quiet -> when (kind) {
            StoryKind.Landmark -> 20..30
            StoryKind.Town -> 25..35
            StoryKind.Province, StoryKind.Country -> 40..50
            StoryKind.Interlude -> return null
        }
        Talkativeness.Normal -> when (kind) {
            StoryKind.Landmark -> 30..45
            StoryKind.Town -> 45..60
            StoryKind.Province, StoryKind.Country, StoryKind.Interlude -> 60..90
        }
        Talkativeness.Chatty -> when (kind) {
            StoryKind.Landmark -> 45..60
            StoryKind.Town -> 60..75
            StoryKind.Province, StoryKind.Country, StoryKind.Interlude -> 90..120
        }
    }
    return (seconds.first * WORDS_PER_SECOND).toInt()..(seconds.last * WORDS_PER_SECOND).toInt()
}

private const val WORDS_PER_SECOND = 2.5 // an unhurried speaking voice
private const val MAX_SOURCE_CHARS_IN_PROMPT = 6_000

/** Builds the messages that ask a model for one Story. */
object StoryPrompt {

    /** Returns null when this drive should get no Story of this kind (no Interludes in Quiet mode). */
    fun messages(brief: StoryBrief): List<ChatMessage>? {
        val words = wordRange(brief.kind, brief.talkativeness) ?: return null
        return listOf(
            ChatMessage("system", rules(brief, words)),
            ChatMessage("user", sourceMessage(brief)),
        )
    }

    private fun rules(brief: StoryBrief, words: IntRange): String = buildString {
        appendLine("You are a warm, vivid storyteller riding along with a driver. Your Story will be read aloud by a text-to-speech voice while they drive.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Use ONLY facts stated in the SOURCE. Never add facts from memory, and keep every number and year exactly as the SOURCE gives it.")
        appendLine("- Include a legend or tall tale only if the SOURCE itself reports it, and tell it as a legend.")
        appendLine("- The SOURCE may be in another language. Write the Story in English.")
        appendLine("- The storytelling lives in the telling: a hook, pacing, vivid wording. You may add atmosphere and speak to the driver, but no new facts.")
        appendLine("- Never say whether something is to the left or right, or which side of the road it is on: you do not know.")
        appendLine("- Plain spoken prose only: no stage directions, no parentheses, no markdown, no lists, no headings, no emoji.")
        appendLine("- Length: between ${words.first} and ${words.last} words. A shorter Story is fine if the SOURCE has little to say. Never pad it with invented facts.")
        if (brief.relation == Relation.Behind) {
            appendLine("- The driver has already passed ${brief.subjectName}. Begin with a very short lead-in such as \"Back there, behind us…\".")
        }
        if (brief.earlierStories.isNotEmpty()) {
            appendLine("- Earlier on this drive you told the driver about: ${brief.earlierStories.joinToString("; ")}. You may link back to one of them if it genuinely connects. Never repeat what you said.")
        }
        append("Reply with the Story text only.")
    }

    private fun sourceMessage(brief: StoryBrief): String {
        val source = brief.source
        return "Subject: ${brief.subjectName} (${brief.kind.name.lowercase()})\n" +
            "SOURCE (${source.language} Wikipedia, article \"${source.title}\"):\n" +
            source.text.cutAtWord(MAX_SOURCE_CHARS_IN_PROMPT)
    }

    private fun String.cutAtWord(maxChars: Int): String =
        if (length <= maxChars) this else take(maxChars).substringBeforeLast(' ')
}
