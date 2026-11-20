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
data class Area(val country: String, val province: String?)

sealed interface AreaEvent {
    data class EnteredCountry(val name: String) : AreaEvent
    data class EnteredProvince(val name: String) : AreaEvent
}

/** [problem] says why the Area could not be worked out this time, if that is so. */
data class AreaUpdate(val tracker: AreaTracker, val events: List<AreaEvent>, val problem: String? = null)

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
) {
    fun withPosition(position: Position): AreaUpdate {
        if (askedAt != null && distanceMeters(askedAt, position) < ASK_AGAIN_AFTER_METERS) {
            return AreaUpdate(this, emptyList())
        }
        if (failedAtMillis != null && clockMillis() - failedAtMillis < RETRY_AFTER_MILLIS) {
            return AreaUpdate(this, emptyList())
        }
        val (found, problem) = lookUp(position)
        if (found == null) return AreaUpdate(copy(failedAtMillis = clockMillis()), emptyList(), problem)
        return AreaUpdate(copy(askedAt = position, area = found, failedAtMillis = null), eventsFor(area, found))
    }

    /** Nominatim first. If it fails or finds no address, Photon, when there is one. The problem names each that failed. */
    private fun lookUp(position: Position): Pair<Area?, String?> {
        val (fromNominatim, nominatimProblem) = attempt { parseArea(nominatim.reverse(position)) }
        if (fromNominatim != null || fallback == null) return fromNominatim to nominatimProblem
        val (fromPhoton, photonProblem) = attempt { parsePhotonArea(fallback.reverse(position)) }
        if (fromPhoton != null) return fromPhoton to null
        return null to "${nominatimProblem ?: "Nominatim found no address"}; Photon: ${photonProblem ?: "no address found"}"
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
        val country = if (before?.country != after.country) AreaEvent.EnteredCountry(after.country) else null
        val province = after.province
            ?.takeIf { before?.country != after.country || before.province != it }
            ?.let { AreaEvent.EnteredProvince(it) }
        return listOfNotNull(country, province)
    }

    companion object {
        private const val ASK_AGAIN_AFTER_METERS = 2_000.0
        private const val RETRY_AFTER_MILLIS = 30_000L

        fun starting(nominatim: NominatimClient, clockMillis: () -> Long, fallback: PhotonClient? = null): AreaTracker =
            AreaTracker(nominatim, clockMillis, askedAt = null, area = null, failedAtMillis = null, fallback = fallback)
    }
}

/** Reads a Nominatim reverse-geocoding response. The Province is the first level below the Country. */
internal fun parseArea(json: String): Area? {
    val address = Json.parseToJsonElement(json).jsonObject["address"]?.jsonObject ?: return null
    fun part(key: String) = address[key]?.jsonPrimitive?.contentOrNull
    val country = part("country") ?: return null
    return Area(country, province = part("state") ?: part("province") ?: part("region"))
}

/** Reads a Photon reverse-geocoding response (GeoJSON). Photon calls the Province "state". */
internal fun parsePhotonArea(json: String): Area? {
    val properties = Json.parseToJsonElement(json).jsonObject["features"]?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("properties")?.jsonObject ?: return null
    fun part(key: String) = properties[key]?.jsonPrimitive?.contentOrNull
    return Area(country = part("country") ?: return null, province = part("state"))
}
