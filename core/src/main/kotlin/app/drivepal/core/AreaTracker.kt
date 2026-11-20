package app.drivepal.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reverse-geocodes a position with Nominatim and returns the raw JSON. The network boundary. */
fun interface NominatimClient {
    fun reverse(position: Position): String
}

/** Reverse-geocodes a position with Photon and returns the raw JSON. The fallback when Nominatim cannot answer. */
fun interface PhotonClient {
    fun reverse(position: Position): String
}

/** Where the driver is, at the Area levels that get an entry Story. */
data class Area(
    val country: String,
    val province: String?,
    /** The province's own Wikipedia page ("nl:Zuid-Holland") and Wikidata item, when Nominatim says: the exact Source. */
    val provinceWikipediaTag: String? = null,
    val provinceWikidataId: String? = null,
)

sealed interface AreaEvent {
    data class EnteredCountry(val name: String) : AreaEvent
    data class EnteredProvince(val name: String, val wikipediaTag: String? = null, val wikidataId: String? = null) : AreaEvent
}

/**
 * [area] is where the driver is now, if known. [events] are only the Areas newly *entered*: the place a Trip
 * starts in is known but is not an entry. [problem] says why the Area could not be worked out this time, if so.
 */
data class AreaUpdate(
    val tracker: AreaTracker,
    val events: List<AreaEvent>,
    val problem: String? = null,
    val area: Area? = null,
)

/**
 * Notices when the driver enters a new Country or Province. Nominatim is asked only after the
 * driver has moved a couple of kilometres, so we stay within its usage rules.
 * Immutable: every new position returns an updated tracker and the events it caused.
 */
@ConsistentCopyVisibility
data class AreaTracker private constructor(
    private val nominatim: NominatimClient,
    private val clockMillis: () -> Long,
    private val askedAt: Position?,
    private val area: Area?,
    private val failedAtMillis: Long?,
    private val fallback: PhotonClient?,
    /** Which service gave the current [area]. Two services name Areas differently, so a switch is not a crossing. */
    private val answeredBy: Provider?,
) {
    private enum class Provider { Nominatim, Photon }

    private class Lookup(val area: Area?, val problem: String?, val provider: Provider?)

    fun withPosition(position: Position): AreaUpdate {
        if (askedAt != null && distanceMeters(askedAt, position) < ASK_AGAIN_AFTER_METERS) {
            return AreaUpdate(this, emptyList(), area = area)
        }
        if (failedAtMillis != null && clockMillis() - failedAtMillis < RETRY_AFTER_MILLIS) {
            return AreaUpdate(this, emptyList(), area = area)
        }
        val lookup = lookUp(position)
        val found = lookup.area ?: return AreaUpdate(copy(failedAtMillis = clockMillis()), emptyList(), lookup.problem, area)
        // A different service answering is not a border crossing: they can name the same place differently.
        val events = if (lookup.provider == answeredBy) eventsFor(area, found) else emptyList()
        return AreaUpdate(
            copy(askedAt = position, area = found, failedAtMillis = null, answeredBy = lookup.provider),
            events,
            area = found,
        )
    }

    /** Nominatim first. If it fails or finds no address, Photon, when there is one. The problem names each that failed. */
    private fun lookUp(position: Position): Lookup {
        val (fromNominatim, nominatimProblem) = attempt { parseArea(nominatim.reverse(position)) }
        if (fromNominatim != null || fallback == null) return Lookup(fromNominatim, nominatimProblem, Provider.Nominatim)
        val (fromPhoton, photonProblem) = attempt { parsePhotonArea(fallback.reverse(position)) }
        if (fromPhoton != null) return Lookup(fromPhoton, null, Provider.Photon)
        return Lookup(null, "${nominatimProblem ?: "Nominatim found no address"}; Photon: ${photonProblem ?: "no address found"}", null)
    }

    private fun attempt(lookUp: () -> Area?): Pair<Area?, String?> = try {
        lookUp() to null
    } catch (e: java.io.IOException) {
        null to (e.message ?: e.javaClass.simpleName)
    } catch (e: IllegalArgumentException) {
        // Includes the JSON library's SerializationException: a response we cannot read.
        null to (e.message ?: e.javaClass.simpleName)
    }

    private fun eventsFor(before: Area?, after: Area): List<AreaEvent> {
        // The first place we learn is where the Trip started: the driver is already there, not entering it.
        if (before == null) return emptyList()
        val country = if (before.country != after.country) AreaEvent.EnteredCountry(after.country) else null
        // A province that was simply not known before (same country) is learned, not entered.
        val province = after.province
            ?.takeIf { before.country != after.country || (before.province != null && before.province != it) }
            ?.let { AreaEvent.EnteredProvince(it, after.provinceWikipediaTag, after.provinceWikidataId) }
        return listOfNotNull(country, province)
    }

    companion object {
        private const val ASK_AGAIN_AFTER_METERS = 2_000.0
        private const val RETRY_AFTER_MILLIS = 30_000L

        fun starting(nominatim: NominatimClient, clockMillis: () -> Long, fallback: PhotonClient? = null): AreaTracker =
            AreaTracker(nominatim, clockMillis, askedAt = null, area = null, failedAtMillis = null, fallback = fallback, answeredBy = null)
    }
}

/** Reads a Nominatim reverse-geocoding response. The Province is the first level below the Country. */
internal fun parseArea(json: String): Area? {
    val address = Json.parseToJsonElement(json).jsonObject["address"]?.jsonObject ?: return null
    fun part(key: String) = address[key]?.jsonPrimitive?.contentOrNull
    val country = part("country") ?: return null
    // At zoom 5 the answer is the province itself, so its extra tags (Wikipedia, Wikidata) are the province's.
    val tags = Json.parseToJsonElement(json).jsonObject["extratags"]?.jsonObject
    val isProvince = Json.parseToJsonElement(json).jsonObject["addresstype"]?.jsonPrimitive?.contentOrNull in PROVINCE_TYPES
    return Area(
        country = country,
        province = part("state") ?: part("province") ?: part("region"),
        provinceWikipediaTag = tags?.takeIf { isProvince }?.get("wikipedia")?.jsonPrimitive?.contentOrNull,
        provinceWikidataId = tags?.takeIf { isProvince }?.get("wikidata")?.jsonPrimitive?.contentOrNull,
    )
}

private val PROVINCE_TYPES = setOf("state", "province", "region")

/** Reads a Photon reverse-geocoding response (GeoJSON). Photon calls the Province "state". */
internal fun parsePhotonArea(json: String): Area? {
    val properties = Json.parseToJsonElement(json).jsonObject["features"]?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("properties")?.jsonObject ?: return null
    fun part(key: String) = properties[key]?.jsonPrimitive?.contentOrNull
    return Area(country = part("country") ?: return null, province = part("state"))
}
