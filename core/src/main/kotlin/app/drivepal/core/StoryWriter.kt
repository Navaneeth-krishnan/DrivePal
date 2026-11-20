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

    /** Returns null when this drive should get no Story of this kind (no Interludes unless Chatty). */
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
        val shownToModel = StoryPrompt.sourceTextFor(brief)
        val first = readReply(chat.complete(messages), words.last) ?: return fallback(brief, words, "The AI server gave no story")
        if (first.isEmpty()) return fallback(brief, words, CUT_OFF)
        val firstMistakes = mistakesIn(shownToModel, first)
        if (firstMistakes.isEmpty()) return StoryResult.Written(first)

        val second = readReply(chat.complete(withCorrection(messages, firstMistakes)), words.last)
            ?: return fallback(brief, words, "The AI server gave no story")
        if (second.isEmpty()) return fallback(brief, words, CUT_OFF)
        val secondMistakes = mistakesIn(shownToModel, second)
        return if (secondMistakes.isEmpty()) {
            StoryResult.Written(second)
        } else {
            fallback(brief, words, "The story contained ${secondMistakes.joinToString(" and ") { it.inReason }}")
        }
    }

    /**
     * The reply made speakable. Null if there was no text at all. Empty if the model was cut off before finishing its
     * first sentence; a reply cut off later is trimmed back to its last whole sentence instead of stopping mid-word.
     */
    private fun readReply(reply: ChatReply, maxWords: Int): String? {
        val text = speakable(reply.text, maxWords)
        if (text.isEmpty()) return null
        return if (reply.finishedNormally) text else lastWholeSentences(text)
    }

    private fun lastWholeSentences(text: String): String {
        val end = SENTENCE_END_AT_TAIL.findAll(text).lastOrNull() ?: return ""
        return text.substring(0, end.range.last + 1)
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
     * Cheap checks for the slips models make most: a number or year the model was not given, a legend it was not given,
     * and telling the driver what to do (which a vandalised article could try to make it say). They catch some
     * mistakes, not all. [shown] is the text the model was given, not the whole article.
     */
    private fun mistakesIn(shown: String, story: String): List<Mistake> = listOfNotNull(
        numbersNotIn(shown, story).takeIf { it.isNotEmpty() }?.let { numbers ->
            Mistake(
                inReason = "numbers that are not in the article: ${numbers.joinToString()}",
                correction = "Your previous attempt contained numbers that are not in the SOURCE: ${numbers.joinToString()}. " +
                    "Use only numbers and years that appear in the SOURCE.",
            )
        },
        if (LEGEND_IN_STORY.containsMatchIn(story) && !LEGEND_IN_SOURCE.containsMatchIn(shown)) {
            Mistake(
                inReason = "a legend that the article does not tell",
                correction = "Your previous attempt told a legend, but the SOURCE tells none. Do not mention legends, myths, folklore or rumours.",
            )
        } else {
            null
        },
        if (DRIVING_INSTRUCTION.containsMatchIn(story)) {
            Mistake(
                inReason = "driving instructions",
                correction = "Your previous attempt contained driving instructions. Never tell the driver what to do; only tell the Story.",
            )
        } else {
            null
        },
    )

    private fun numbersNotIn(shown: String, story: String): List<String> {
        val known = NUMBER.findAll(shown).map { it.value }.toSet()
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
        const val CUT_OFF = "The story was cut off before its first sentence ended"
        const val OVERRUN_ALLOWED = 1.2
        val STAGE_DIRECTION = Regex("\\([^)]*\\)")
        val MARKDOWN = Regex("[*`]|^#+\\s*|^[-•>]\\s+", RegexOption.MULTILINE)
        val WHITESPACE = Regex("\\s+")
        val SENTENCE_END = Regex("(?<=[.!?])\\s+")
        val NUMBER = Regex("\\d+")
        val SENTENCE_END_AT_TAIL = Regex("[.!?](?=\\s|$)")
        val DRIVING_INSTRUCTION = Regex(
            "\\b(turn (left|right)|pull over|take the (next )?exit|slow down|speed up|stop the car|brake now|keep (left|right)|make a u-turn)\\b",
            RegexOption.IGNORE_CASE,
        )
        val LEGEND_IN_STORY = Regex("legend|myth|folklore|folk tale|rumou?r", RegexOption.IGNORE_CASE)
        // The same idea in the languages the articles are likely to be in (English, Dutch, German, French, Spanish...).
        val LEGEND_IN_SOURCE = Regex(
            "legend|\\bsage\\b|\\bsagen\\b|myth|folklor|overlever|volksverhaal|märchen|légende|leyenda|rumou?r|gerucht",
            RegexOption.IGNORE_CASE,
        )
    }
}
