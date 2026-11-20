package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceLibraryTest {

    private val castle = Subject(
        id = "node/1001", name = "Example Castle", kind = SubjectKind.Landmark,
        position = Position(52.0455, 4.0431),
        wikipediaTag = "nl:Kasteel Voorbeeld", wikidataId = "Q111",
    )

    @Test
    fun `a landmark with a rich article gets that article as its Source`() {
        val library = SourceLibrary(
            wikipedia = { _, title -> articleJson(title, words = 200, wikidataId = "Q111", otherLanguages = 2) },
            wikidata = { error("a subject with a Wikipedia tag needs no Wikidata") },
            clockMillis = { 0 },
        )

        val source = library.found(castle)

        assertNotNull(source)
        assertEquals("nl", source.language)
        assertEquals("Kasteel Voorbeeld", source.title)
        assertEquals(Notability(languageEditions = 3, words = 200), source.notability)
    }

    @Test
    fun `a landmark whose article is only a stub gets no Source`() {
        val library = SourceLibrary(
            wikipedia = { _, title -> articleJson(title, words = 40, wikidataId = "Q111") },
            wikidata = { error("a subject with a Wikipedia tag needs no Wikidata") },
            clockMillis = { 0 },
        )

        assertEquals(SourceLookup.NotStoryWorthy, library.sourceFor(castle))
    }

    @Test
    fun `a landmark with articles in thirty languages is more notable than one in three`() {
        fun libraryWithEditions(editions: Int) = SourceLibrary(
            wikipedia = { _, title -> articleJson(title, words = 200, wikidataId = "Q111", otherLanguages = editions - 1) },
            wikidata = { error("a subject with a Wikipedia tag needs no Wikidata") },
            clockMillis = { 0 },
        )

        val famous = libraryWithEditions(30).found(castle)!!.notability
        val obscure = libraryWithEditions(3).found(castle)!!.notability

        assertEquals(true, famous > obscure)
    }

    @Test
    fun `a subject known only through Wikidata uses its English article`() {
        val onlyWikidata = castle.copy(wikipediaTag = null)
        var asked: Pair<String, String>? = null
        val library = SourceLibrary(
            wikipedia = { language, title -> asked = language to title; articleJson(title, words = 200, wikidataId = "Q111") },
            wikidata = { sitelinksJson("Q111", "nlwiki", "enwiki", "dewiki") },
            clockMillis = { 0 },
        )

        val source = library.found(onlyWikidata)

        assertEquals("en" to "T enwiki", asked)
        assertEquals("en", source?.language)
    }

    @Test
    fun `looking up the same landmark again fetches nothing, even when its article was a stub`() {
        var fetches = 0
        val library = SourceLibrary(
            wikipedia = { _, title -> fetches++; articleJson(title, words = 40, wikidataId = "Q111") },
            wikidata = { fetches++; sitelinksJson("Q111", "nlwiki") },
            clockMillis = { 0 },
        )

        library.sourceFor(castle)
        val fetchesAfterFirstLookup = fetches
        library.sourceFor(castle)

        assertEquals(fetchesAfterFirstLookup, fetches)
    }

    @Test
    fun `a failed lookup is not repeated for thirty seconds and then tried again`() {
        var nowMillis = 0L
        var wikipediaIsDown = true
        var fetches = 0
        val library = SourceLibrary(
            wikipedia = { _, title ->
                fetches++
                if (wikipediaIsDown) throw java.io.IOException("offline") else articleJson(title, 200, "Q111")
            },
            wikidata = { sitelinksJson("Q111", "nlwiki") },
            clockMillis = { nowMillis },
        )

        val whileDown = library.sourceFor(castle)
        nowMillis = 10_000
        library.sourceFor(castle)
        val fetchesAfterTenSeconds = fetches
        wikipediaIsDown = false
        nowMillis = 31_000
        val afterRecovery = library.sourceFor(castle)

        assertEquals(SourceLookup.Unavailable("offline"), whileDown)
        assertEquals(1, fetchesAfterTenSeconds)
        assertTrue(afterRecovery is SourceLookup.Found)
    }

    @Test
    fun `real Wikipedia and Wikidata responses for Kinderdijk make a Source known in 43 language editions`() {
        val kinderdijk = Subject(
            id = "relation/1", name = "Kinderdijk", kind = SubjectKind.Landmark,
            position = Position(51.8826, 4.6403), wikipediaTag = "nl:Kinderdijk", wikidataId = "Q740066",
        )
        val library = SourceLibrary(
            wikipedia = { _, _ -> recorded("wikipedia-nl-kinderdijk.json") },
            wikidata = { error("a subject with a Wikipedia tag needs no Wikidata") },
            clockMillis = { 0 },
        )

        val source = library.found(kinderdijk)

        assertEquals(Notability(languageEditions = 43, words = 757), source?.notability)
    }

    @Test
    fun `a subject known only through Wikidata skips the stub and uses the first real article`() {
        val onlyWikidata = castle.copy(wikipediaTag = null, wikidataId = "Q740066")
        val library = SourceLibrary(
            wikipedia = { language, title ->
                articleJson(title, words = if (language == "az") 40 else 200, wikidataId = "Q740066")
            },
            wikidata = { recorded("wikidata-sitelinks-q740066-without-en.json") },
            clockMillis = { 0 },
        )

        val source = library.found(onlyWikidata)

        assertEquals("de", source?.language)
    }

    @Test
    fun `a bot-written Cebuano stub is never chosen when Wikidata is all we have`() {
        val onlyWikidata = castle.copy(wikipediaTag = null, wikidataId = "Q111")
        val askedLanguages = mutableListOf<String>()
        val library = SourceLibrary(
            wikipedia = { language, title -> askedLanguages += language; articleJson(title, words = 40, wikidataId = "Q111") },
            wikidata = { sitelinksJson("Q111", "cebwiki", "warwiki", "nlwiki") },
            clockMillis = { 0 },
        )

        library.sourceFor(onlyWikidata)

        assertEquals(listOf("nl"), askedLanguages)
    }

    @Test
    fun `a Wikipedia error answered with HTTP 200 is a failed lookup, not a missing article`() {
        val library = SourceLibrary(
            wikipedia = { _, _ -> """{"error":{"code":"ratelimited","info":"Too many requests"}}""" },
            wikidata = { error("not needed") },
            clockMillis = { 0 },
        )

        assertTrue(library.sourceFor(castle) is SourceLookup.Unavailable)
    }

    @Test
    fun `a Wikipedia tag written in capitals still finds the article`() {
        var asked: String? = null
        val library = SourceLibrary(
            wikipedia = { language, title -> asked = language; articleJson(title, words = 200, wikidataId = "Q111") },
            wikidata = { error("not needed") },
            clockMillis = { 0 },
        )

        library.sourceFor(castle.copy(wikipediaTag = "NL:Kasteel Voorbeeld"))

        assertEquals("nl", asked)
    }

    @Test
    fun `a Wikipedia tag without a language falls back to Wikidata`() {
        var asked: Pair<String, String>? = null
        val library = SourceLibrary(
            wikipedia = { language, title -> asked = language to title; articleJson(title, words = 200, wikidataId = "Q111") },
            wikidata = { sitelinksJson("Q111", "enwiki") },
            clockMillis = { 0 },
        )

        library.sourceFor(castle.copy(wikipediaTag = "Kasteel Voorbeeld"))

        assertEquals("en" to "T enwiki", asked)
    }

    @Test
    fun `Wikidata writes zh-min-nan with underscores but its address uses hyphens`() {
        var asked: String? = null
        val library = SourceLibrary(
            wikipedia = { language, title -> asked = language; articleJson(title, words = 200, wikidataId = "Q111") },
            wikidata = { sitelinksJson("Q111", "zh_min_nanwiki") },
            clockMillis = { 0 },
        )

        library.sourceFor(castle.copy(wikipediaTag = null))

        assertEquals("zh-min-nan", asked)
    }

    @Test
    fun `a province keeps far more of its article than a landmark, since its later sections become interludes`() {
        val library = SourceLibrary(
            wikipedia = { _, title -> articleJson(title, words = 30_000, wikidataId = "Q111") },
            wikidata = { error("not needed") },
            clockMillis = { 0 },
        )

        val province = (library.sourceFor(castle.copy(id = "area/province/X", kind = SubjectKind.Province)) as SourceLookup.Found).source
        val landmark = (library.sourceFor(castle) as SourceLookup.Found).source

        assertTrue(province.text.length > 100_000)
        assertTrue(landmark.text.length <= 20_000)
    }

    @Test
    fun `a very long article is kept short but its real length still counts for Notability`() {
        val library = SourceLibrary(
            wikipedia = { _, title -> articleJson(title, words = 30_000, wikidataId = "Q111") },
            wikidata = { error("not needed") },
            clockMillis = { 0 },
        )

        val source = library.found(castle)!!

        assertTrue(source.text.length <= 20_000)
        assertEquals(30_000, source.notability.words)
    }
}

/** Shaped like a real Wikipedia `action=query&prop=extracts|pageprops&formatversion=2` response. */
private fun articleJson(title: String, words: Int, wikidataId: String, otherLanguages: Int = 0): String {
    val text = List(words) { "word$it" }.joinToString(" ")
    val links = List(otherLanguages) { """{"lang":"l$it","title":"$title"}""" }.joinToString(",")
    return """{"batchcomplete":true,"query":{"pages":[{"pageid":1,"ns":0,"title":"$title",
        "extract":"$text","pageprops":{"wikibase_item":"$wikidataId"},"langlinks":[$links]}]}}"""
}

/** Shaped like a real Wikidata `wbgetentities&props=sitelinks` response. */
private fun sitelinksJson(wikidataId: String, vararg sites: String): String {
    val links = sites.joinToString(",") { """"$it":{"site":"$it","title":"T $it","badges":[]}""" }
    return """{"entities":{"$wikidataId":{"type":"item","id":"$wikidataId","sitelinks":{$links}}},"success":1}"""
}

private fun SourceLibrary.found(subject: Subject): Source? = (sourceFor(subject) as? SourceLookup.Found)?.source

private fun recorded(name: String): String =
    SourceLibraryTest::class.java.getResource("/real/$name")!!.readText()
