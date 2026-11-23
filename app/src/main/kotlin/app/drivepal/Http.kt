package app.drivepal

import android.os.SystemClock
import app.drivepal.core.BoundingBox
import app.drivepal.core.ChatTransport
import app.drivepal.core.Endpoints
import app.drivepal.core.NominatimClient
import app.drivepal.core.OverpassClient
import app.drivepal.core.PhotonClient
import app.drivepal.core.Position
import app.drivepal.core.WikidataClient
import app.drivepal.core.WikipediaClient
import app.drivepal.core.WikipediaGeoSearchClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The real network clients for OpenStreetMap, Wikipedia and Wikidata. The core classes cache and back
 * off per request; this file adds what only the network layer can know: requests are spaced out, a server
 * that says "slow down" (HTTP 429 or 503) is left alone for as long as it asks, responses are size-capped,
 * and redirects are never followed. The User-Agent names the app, and a contact only if you build one in.
 */
private val USER_AGENT = "DrivePal/0.1 (personal hobby project" +
    (if (BuildConfig.CONTACT.isBlank()) "" else "; ${BuildConfig.CONTACT}") + ")"
private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val MIN_GAP_BETWEEN_REQUESTS_MILLIS = 300L
private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
private const val DEFAULT_COOL_DOWN_SECONDS = 60L
private const val MAX_COOL_DOWN_SECONDS = 300L

private val spacing = Any()
private var lastRequestAtMillis = 0L // guarded by `spacing`; monotonic clock, immune to clock changes
private val coolDownUntilMillis = ConcurrentHashMap<String, Long>()

/** Keeps requests at least a moment apart, so a burst of lookups never hammers a public server. */
private fun waitYourTurn() = synchronized(spacing) {
    val wait = (lastRequestAtMillis + MIN_GAP_BETWEEN_REQUESTS_MILLIS - SystemClock.elapsedRealtime())
        .coerceIn(0, MIN_GAP_BETWEEN_REQUESTS_MILLIS)
    if (wait > 0) Thread.sleep(wait)
    lastRequestAtMillis = SystemClock.elapsedRealtime()
}

private const val FORM = "application/x-www-form-urlencoded"

private fun request(
    url: String,
    body: String? = null,
    contentType: String = FORM,
    headers: Map<String, String> = emptyMap(),
    readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    userAgent: String = USER_AGENT,
): String {
    val host = URL(url).host
    val now = SystemClock.elapsedRealtime()
    coolDownUntilMillis[host]?.takeIf { now < it }?.let {
        throw IOException("$host asked us to slow down; waiting ${(it - now) / 1000} s")
    }
    waitYourTurn()
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent)
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        if (code == 429 || code == 503) {
            val seconds = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                ?.coerceIn(1, MAX_COOL_DOWN_SECONDS) ?: DEFAULT_COOL_DOWN_SECONDS
            coolDownUntilMillis[host] = SystemClock.elapsedRealtime() + seconds * 1000
        }
        if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from $host")
        return connection.inputStream.use(::readCapped)
    } finally {
        connection.disconnect()
    }
}

/** Reads a response, refusing anything bigger than [MAX_RESPONSE_BYTES] rather than filling the phone's memory. */
private fun readCapped(stream: InputStream): String {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        if (out.size() + read > MAX_RESPONSE_BYTES) throw IOException("Response too large")
        out.write(chunk, 0, read)
    }
    return out.toString(Charsets.UTF_8.name())
}

private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

private fun degrees(value: Double, decimals: Int = 5) = String.format(Locale.US, "%.${decimals}f", value)

// The public Overpass server often needs 25 to 40 seconds for a tile when it is busy; giving up at 30 meant no villages at all.
private const val OVERPASS_SERVER_TIMEOUT_SECONDS = 55
private const val OVERPASS_READ_TIMEOUT_MILLIS = 65_000

object OverpassHttp : OverpassClient {
    override fun query(box: BoundingBox): String {
        val area = "(${degrees(box.south)},${degrees(box.west)},${degrees(box.north)},${degrees(box.east)})"
        val withWikiLink = listOf("wikipedia", "wikidata")
        val query = "[out:json][timeout:$OVERPASS_SERVER_TIMEOUT_SECONDS];(" +
            withWikiLink.joinToString("") { """nwr["historic"]["name"]["$it"]$area;""" } +
            withWikiLink.joinToString("") { """nwr["tourism"~"^(attraction|viewpoint|museum)$"]["name"]["$it"]$area;""" } +
            withWikiLink.joinToString("") { """nwr["man_made"~"^(lighthouse|windmill)$"]["name"]["$it"]$area;""" } +
            """nwr["place"~"^(city|town|village)$"]["name"]$area;""" + // with or without an article: announced by name
            """node["highway"="motorway_junction"]["name"]$area;""" +
            ");out center tags;"
        return request(BuildConfig.OVERPASS_URL, body = "data=${encode(query)}", readTimeoutMillis = OVERPASS_READ_TIMEOUT_MILLIS)
    }
}

object NominatimHttp : NominatimClient {
    override fun reverse(position: Position): String = request(Endpoints.nominatimReverse(position))
}

object PhotonHttp : PhotonClient {
    // The fallback for Nominatim.
    override fun reverse(position: Position): String = request(Endpoints.photonReverse(position))
}

object WikipediaGeoSearchHttp : WikipediaGeoSearchClient {
    // The fallback for Overpass: the English Wikipedia articles about places near the middle of a map tile.
    override fun search(center: Position, radiusMeters: Int): String = request(Endpoints.wikipediaGeoSearch(center, radiusMeters))
}

object WikipediaHttp : WikipediaClient {
    // The language comes from OpenStreetMap data, so it must not be allowed to choose the server.
    private val LANGUAGE = Regex("[a-z]{2,12}(-[a-z]{1,12})*")

    override fun extract(language: String, title: String): String {
        require(LANGUAGE.matches(language)) { "Unexpected Wikipedia language code" }
        return request(
            "https://$language.wikipedia.org/w/api.php?action=query&prop=extracts%7Cpageprops%7Clanglinks&lllimit=500" +
                "&explaintext=1&redirects=1&format=json&formatversion=2&titles=${encode(title)}",
        )
    }
}

object WikidataHttp : WikidataClient {
    private val ITEM_ID = Regex("Q[0-9]+")

    override fun sitelinks(wikidataId: String): String {
        require(ITEM_ID.matches(wikidataId)) { "Unexpected Wikidata id" }
        return request("https://www.wikidata.org/w/api.php?action=wbgetentities&props=sitelinks&format=json&ids=$wikidataId")
    }
}

/** The AI server that writes the Stories (any OpenAI-compatible `/chat/completions` endpoint), as currently set up. */
class AiHttp(private val connection: () -> AiConnection) : ChatTransport {
    override fun post(requestJson: String): String {
        val now = connection()
        val url = now.settings.chatUrl()
        // The key goes only over an encrypted connection; a local Ollama over plain HTTP needs none.
        val key = now.apiKey?.takeIf { it.isNotBlank() && url.startsWith("https://") }
        return request(
            url = url,
            body = requestJson,
            contentType = "application/json",
            headers = if (key == null) emptyMap() else mapOf("Authorization" to "Bearer $key"),
            readTimeoutMillis = AI_READ_TIMEOUT_MILLIS,
            userAgent = "DrivePal/0.1", // the AI provider has no use for the optional contact
        )
    }

    private companion object {
        const val AI_READ_TIMEOUT_MILLIS = 90_000 // a local model's first answer can take 15 s or more
    }
}
