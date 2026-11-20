package app.drivepal.core

/** One part of an Area's article, ready to become an Interlude. [text] has no heading markup. */
data class Section(val title: String, val text: String)

/**
 * Cuts an Area's article (Wikipedia plain text, where `== History ==` marks a section) into the parts worth an
 * Interlude. The opening before the first heading is left out, because the Area's own Story already used it, as are
 * boilerplate sections (References, See also, ...) and sections too short to tell.
 */
object InterludeSections {
    private val SECTION_HEADING = Regex("^== (.+?) ==[ \\t]*$", RegexOption.MULTILINE)
    private val SUBSECTION_HEADING = Regex("^={3,6} (.+?) ={3,6}[ \\t]*$", RegexOption.MULTILINE)
    private val BLANK_LINES = Regex("\\n{3,}")
    private const val MIN_WORDS = 80

    private val BOILERPLATE = setOf(
        "see also", "references", "notes", "footnotes", "citations", "external links", "further reading",
        "bibliography", "sources", "gallery", "explanatory notes", "works cited", "general references",
    )

    fun of(article: String): List<Section> {
        val headings = SECTION_HEADING.findAll(article).toList()
        return headings.mapIndexedNotNull { index, heading ->
            val title = heading.groupValues[1].trim()
            val end = headings.getOrNull(index + 1)?.range?.first ?: article.length
            val text = article.substring(heading.range.last + 1, end)
                .replace(SUBSECTION_HEADING, "$1")
                .replace(BLANK_LINES, "\n\n")
                .trim()
            if (title.lowercase() in BOILERPLATE || text.split(Regex("\\s+")).count { it.isNotEmpty() } < MIN_WORDS) null else Section(title, text)
        }
    }

    /** The first section, in article order, that has not been told yet; null when all have. */
    fun next(article: String, isTold: (Section) -> Boolean): Section? = of(article).firstOrNull { !isTold(it) }
}
