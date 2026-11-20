package app.drivepal.core

import java.io.IOException

sealed interface StoryResult {
    /** A Story the model wrote, cleaned for speaking. */
    data class Written(val text: String) : StoryResult

    /** The model could not give a usable Story, so this is the start of the article itself. [language] is the article's. */
    data class Fallback(val text: String, val language: String, val reason: String) : StoryResult
}

/**
 * Asks a model for a Story and makes sure what comes back can be spoken and is grounded:
 * stage directions and markdown are removed, a story that runs on is cut at a sentence, and a
 * year or number that is not in the Source gets one retry before the article itself is used.
 */
class StoryWriter(private val chat: ChatClient) {

    /** Returns null when this drive should get no Story of this kind (no Interludes in Quiet mode). */
    fun write(brief: StoryBrief): StoryResult? {
        val messages = StoryPrompt.messages(brief) ?: return null
        val words = wordRange(brief.kind, brief.talkativeness) ?: return null
        return try {
            tell(brief, messages, words)
        } catch (e: IOException) {
            fallback(brief, words, e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            // Includes the JSON library's SerializationException: an answer we cannot read.
            fallback(brief, words, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun tell(brief: StoryBrief, messages: List<ChatMessage>, words: IntRange): StoryResult {
        val first = speakable(chat.complete(messages).text, words.last)
        if (first.isEmpty()) return fallback(brief, words, "The AI server gave no story")
        val firstMistakes = mistakesIn(brief.source, first)
        if (firstMistakes.isEmpty()) return StoryResult.Written(first)

        val second = speakable(chat.complete(withCorrection(messages, firstMistakes)).text, words.last)
        if (second.isEmpty()) return fallback(brief, words, "The AI server gave no story")
        val secondMistakes = mistakesIn(brief.source, second)
        return if (secondMistakes.isEmpty()) {
            StoryResult.Written(second)
        } else {
            fallback(brief, words, "The story contained ${secondMistakes.joinToString(" and ") { it.inReason }}")
        }
    }

    private fun withCorrection(messages: List<ChatMessage>, mistakes: List<Mistake>): List<ChatMessage> =
        messages.map {
            if (it.role != "system") it else it.copy(content = it.content + "\n" + mistakes.joinToString("\n") { m -> m.correction })
        }

    private fun fallback(brief: StoryBrief, words: IntRange, reason: String) = StoryResult.Fallback(
        text = cutAtSentence(brief.source.text.collapseSpaces(), words.last),
        language = brief.source.language,
        reason = reason,
    )

    /** Removes what a voice must not read (stage directions, markdown) and trims a rambling story. */
    private fun speakable(raw: String, maxWords: Int): String {
        val plain = raw
            .replace(STAGE_DIRECTION, " ")
            .replace(MARKDOWN, "")
            .collapseSpaces()
        return if (plain.wordCount() > maxWords * OVERRUN_ALLOWED) cutAtSentence(plain, maxWords) else plain
    }

    /** A claim in the story that the article does not back up: [inReason] for the log, [correction] for the retry. */
    private class Mistake(val inReason: String, val correction: String)

    /**
     * Cheap checks for the two slips models make most: a number or year the article never mentions, and a
     * legend the article never tells. They catch some invented facts, not all of them.
     */
    private fun mistakesIn(source: Source, story: String): List<Mistake> = listOfNotNull(
        numbersNotIn(source, story).takeIf { it.isNotEmpty() }?.let { numbers ->
            Mistake(
                inReason = "numbers that are not in the article: ${numbers.joinToString()}",
                correction = "Your previous attempt contained numbers that are not in the SOURCE: ${numbers.joinToString()}. " +
                    "Use only numbers and years that appear in the SOURCE.",
            )
        },
        if (LEGEND_IN_STORY.containsMatchIn(story) && !LEGEND_IN_SOURCE.containsMatchIn(source.text)) {
            Mistake(
                inReason = "a legend that the article does not tell",
                correction = "Your previous attempt told a legend, but the SOURCE tells none. Do not mention legends, myths, folklore or rumours.",
            )
        } else {
            null
        },
    )

    private fun numbersNotIn(source: Source, story: String): List<String> {
        val known = NUMBER.findAll(source.text).map { it.value }.toSet()
        return NUMBER.findAll(story).map { it.value }.filter { it !in known }.distinct().toList()
    }

    private fun cutAtSentence(text: String, maxWords: Int): String {
        if (text.wordCount() <= maxWords) return text
        val sentences = text.split(SENTENCE_END)
        val kept = mutableListOf<String>()
        var count = 0
        for (sentence in sentences) {
            if (count + sentence.wordCount() > maxWords) break
            kept += sentence
            count += sentence.wordCount()
        }
        return if (kept.isEmpty()) text.split(" ").take(maxWords).joinToString(" ") else kept.joinToString(" ")
    }

    private fun String.collapseSpaces() = replace(WHITESPACE, " ").trim()
    private fun String.wordCount() = split(" ").count { it.isNotEmpty() }

    private companion object {
        const val OVERRUN_ALLOWED = 1.2
        val STAGE_DIRECTION = Regex("\\([^)]*\\)")
        val MARKDOWN = Regex("[*`]|^#+\\s*|^[-•>]\\s+", RegexOption.MULTILINE)
        val WHITESPACE = Regex("\\s+")
        val SENTENCE_END = Regex("(?<=[.!?])\\s+")
        val NUMBER = Regex("\\d+")
        val LEGEND_IN_STORY = Regex("legend|myth|folklore|folk tale|rumou?r", RegexOption.IGNORE_CASE)
        // The same idea in the languages the articles are likely to be in (English, Dutch, German, French, Spanish...).
        val LEGEND_IN_SOURCE = Regex(
            "legend|\\bsage\\b|\\bsagen\\b|myth|folklor|overlever|volksverhaal|märchen|légende|leyenda|rumou?r|gerucht",
            RegexOption.IGNORE_CASE,
        )
    }
}
