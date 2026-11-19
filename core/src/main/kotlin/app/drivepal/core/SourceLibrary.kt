package app.drivepal.core

import java.util.concurrent.ConcurrentHashMap

/** Fetches a Wikipedia article as JSON: `action=query&prop=extracts|pageprops|langlinks`. The network boundary. */
fun interface WikipediaClient {
    fun extract(language: String, title: String): String
}

/** Fetches a Wikidata item's sitelinks as JSON: `action=wbgetentities&props=sitelinks`. The network boundary. */
fun interface WikidataClient {
    fun sitelinks(wikidataId: String): String
}

/** How well known a subject is: in how many language editions it has an article, then how long that article is. */
data class Notability(val languageEditions: Int, val words: Int) : Comparable<Notability> {
    override fun compareTo(other: Notability): Int =
        compareValuesBy(this, other, { it.languageEditions }, { it.words })
}

/** The article a Story is based on. */
data class Source(val language: String, val title: String, val text: String, val notability: Notability)

/** What looking up a subject's Source came to. */
sealed interface SourceLookup {
    /** The subject has an article rich enough for a Story. */
    data class Found(val source: Source) : SourceLookup

    /** There is no article, or it is too thin: this subject will not get a Story. */
    data object NotStoryWorthy : SourceLookup

    /** The lookup failed (offline, server busy). Ask again later: the subject may still get a Story. */
    data class Unavailable(val reason: String) : SourceLookup
}

/**
 * Finds the Source for a subject, or none if its article is too thin to base a Story on.
 * Every answer is remembered, so a subject is looked up once. A lookup that fails is retried after a while.
 * The caches are the only mutable state, and they are thread-safe.
 */
class SourceLibrary(
    private val wikipedia: WikipediaClient,
    private val wikidata: WikidataClient,
    private val clockMillis: () -> Long,
) {
    private val answers = ConcurrentHashMap<String, SourceLookup>()
    private val failures = ConcurrentHashMap<String, Pair<Long, SourceLookup.Unavailable>>()

    fun sourceFor(subject: Subject): SourceLookup {
        answers[subject.id]?.let { return it }
        failures[subject.id]?.let { (failedAt, failure) ->
            if (clockMillis() - failedAt < RETRY_AFTER_MILLIS) return failure
        }
        return try {
            (lookUp(subject)?.let { SourceLookup.Found(it) } ?: SourceLookup.NotStoryWorthy).also {
                answers[subject.id] = it
                failures.remove(subject.id)
            }
        } catch (e: java.io.IOException) {
            failed(subject, e)
        } catch (e: IllegalArgumentException) {
            // Includes the JSON library's SerializationException: a response we cannot read.
            failed(subject, e)
        }
    }

    private fun failed(subject: Subject, cause: Exception): SourceLookup.Unavailable {
        val failure = SourceLookup.Unavailable(cause.message ?: cause.javaClass.simpleName)
        failures[subject.id] = clockMillis() to failure
        return failure
    }

    private fun lookUp(subject: Subject): Source? {
        for ((language, title) in candidateArticles(subject)) {
            val article = parseArticle(wikipedia.extract(language, title)) ?: continue
            val words = article.text.split(WHITESPACE).count { it.isNotEmpty() }
            if (words >= MIN_WORDS) {
                return Source(language, article.title, article.text.take(MAX_SOURCE_CHARS), Notability(article.otherLanguages + 1, words))
            }
        }
        return null
    }

    /**
     * Which articles to try, best first. A well-formed Wikipedia tag names the article directly.
     * Otherwise Wikidata lists the language editions: English first, then a few others, never the
     * bot-written stub wikis. Asking Wikidata only happens when there is no usable tag.
     */
    private fun candidateArticles(subject: Subject): List<Pair<String, String>> {
        val tag = subject.wikipediaTag?.trim()?.takeIf { ':' in it }
        if (tag != null) return listOf(tag.substringBefore(':').trim().lowercase() to tag.substringAfter(':').trim())
        val wikidataId = subject.wikidataId?.substringBefore(';')?.trim() ?: return emptyList()
        val links = sitelinksOf(wikidataId).filterKeys { it !in BOT_WRITTEN_WIKIS }
        return (listOfNotNull(links["en"]?.let { "en" to it }) +
            links.entries.filter { it.key != "en" }.sortedBy { it.key }.map { it.key to it.value })
            .take(MAX_CANDIDATES)
    }

    private fun sitelinksOf(wikidataId: String): Map<String, String> =
        parseSitelinks(wikidata.sitelinks(wikidataId), wikidataId)

    private companion object {
        const val MIN_WORDS = 150
        const val MAX_SOURCE_CHARS = 20_000
        const val MAX_CANDIDATES = 3
        // Wikipedias made largely of bot-written stubs about places.
        val BOT_WRITTEN_WIKIS = setOf("ceb", "war", "arz")
        const val RETRY_AFTER_MILLIS = 30_000L
        val WHITESPACE = Regex("\\s+")
    }
}
