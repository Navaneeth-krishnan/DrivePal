package app.drivepal.core

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** [otherLanguages] is how many other-language Wikipedias have an article on the same subject. */
internal data class Article(val title: String, val text: String, val wikidataId: String?, val otherLanguages: Int)

/** Reads a Wikipedia `prop=extracts|pageprops|langlinks` response. Returns null if the page doesn't exist. */
internal fun parseArticle(json: String): Article? {
    val root = Json.parseToJsonElement(json).jsonObject
    failOnApiError(root)
    val page = root["query"]?.jsonObject
        ?.get("pages")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    if (page["missing"] != null) return null
    val title = page["title"]?.jsonPrimitive?.contentOrNull ?: return null
    val text = page["extract"]?.jsonPrimitive?.contentOrNull ?: return null
    val wikidataId = page["pageprops"]?.jsonObject?.get("wikibase_item")?.jsonPrimitive?.contentOrNull
    val otherLanguages = page["langlinks"]?.jsonArray?.size ?: 0
    return Article(title, text, wikidataId, otherLanguages)
}

/**
 * Reads a Wikidata `wbgetentities&props=sitelinks` response into language code -> article title.
 * Only Wikipedia editions count: Commons, Wikidata itself and the other project wikis are skipped.
 */
internal fun parseSitelinks(json: String, wikidataId: String): Map<String, String> {
    val root = Json.parseToJsonElement(json).jsonObject
    failOnApiError(root)
    val links = root["entities"]?.jsonObject
        ?.get(wikidataId)?.jsonObject?.get("sitelinks")?.jsonObject ?: return emptyMap()
    return links.entries.mapNotNull { (site, link) ->
        val title = link.jsonObject["title"]?.jsonPrimitive?.contentOrNull
        if (site.endsWith("wiki") && site !in NON_WIKIPEDIA_SITES && title != null) {
            // Wikidata writes zh-min-nan as zh_min_nan; the web address uses hyphens.
            site.removeSuffix("wiki").replace('_', '-') to title
        } else {
            null
        }
    }.toMap()
}

private val NON_WIKIPEDIA_SITES = setOf(
    "commonswiki", "wikidatawiki", "specieswiki", "metawiki", "mediawikiwiki",
    "outreachwiki", "incubatorwiki", "foundationwiki", "wikimaniawiki", "sourceswiki",
)

/** The MediaWiki API reports errors such as "ratelimited" with HTTP 200 and an `error` object. */
internal fun failOnApiError(root: kotlinx.serialization.json.JsonObject) {
    val error = root["error"]?.jsonObject ?: return
    throw IOException("Wikimedia API error: ${error["code"]?.jsonPrimitive?.contentOrNull ?: "unknown"}")
}

/**
 * Reads a Wikipedia `list=geosearch` response into subjects. The article is the Source, so the subject
 * carries its English Wikipedia tag. Nothing says whether it is a Town or a Landmark, so it counts as a Landmark.
 * A bracketed note in the title ("Lek (river)") is not part of the name.
 */
internal fun parseGeoSearch(json: String): List<Subject> {
    val root = Json.parseToJsonElement(json).jsonObject
    failOnApiError(root)
    val results = root["query"]?.jsonObject?.get("geosearch")?.jsonArray ?: return emptyList()
    return results.mapNotNull { result ->
        val article = result.jsonObject
        val pageId = article["pageid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val title = article["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val latitude = article["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val longitude = article["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        Subject(
            id = "en.wikipedia/$pageId",
            name = title.substringBefore(" ("),
            kind = SubjectKind.Landmark,
            position = Position(latitude, longitude),
            wikipediaTag = "en:$title",
            wikidataId = null,
        )
    }
}
