package app.drivepal.core

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val TOWN_PLACES = setOf("city", "town", "village")

/**
 * Turns an Overpass JSON response into Subjects. Elements without a name, a position, or a
 * Wikipedia or Wikidata link are skipped: without a Source there is no Story.
 */
internal fun parseOverpass(json: String): List<Subject> {
    val root = Json.parseToJsonElement(json).jsonObject
    // Overpass reports timeouts and running out of memory with HTTP 200, a `remark`, and a short or empty list.
    val remark = root["remark"]?.jsonPrimitive?.contentOrNull
    if (remark != null && remark.contains("runtime error", ignoreCase = true)) throw IOException("Overpass: $remark")
    val elements = root["elements"]?.jsonArray ?: return emptyList()
    return elements.mapNotNull { subjectOf(it.jsonObject) }
}

private fun subjectOf(element: JsonObject): Subject? {
    val tags = element["tags"]?.jsonObject ?: return null
    fun tag(key: String) = tags[key]?.jsonPrimitive?.contentOrNull

    val name = tag("name:en") ?: tag("name") ?: return null
    val wikipedia = tag("wikipedia")
    val wikidata = tag("wikidata")
    if (wikipedia == null && wikidata == null) return null

    val place = element["center"]?.jsonObject ?: element
    val latitude = place["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
    val longitude = place["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
    val id = "${element["type"]?.jsonPrimitive?.contentOrNull}/${element["id"]?.jsonPrimitive?.contentOrNull}"

    return Subject(
        id = id,
        name = name,
        kind = if (tag("place") in TOWN_PLACES) SubjectKind.Town else SubjectKind.Landmark,
        position = Position(latitude, longitude),
        wikipediaTag = wikipedia,
        wikidataId = wikidata,
    )
}
