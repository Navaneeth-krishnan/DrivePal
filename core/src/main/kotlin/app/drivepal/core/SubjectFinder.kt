package app.drivepal.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** A rectangle on the map, in degrees. */
data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double)

/** Asks Overpass for everything inside a box and returns the raw JSON. The one place the network is touched. */
fun interface OverpassClient {
    fun query(box: BoundingBox): String
}

/**
 * Asks Wikipedia (the English one) for the articles about places within [radiusMeters] of [center], as raw JSON.
 * Each article is a Landmark that already names its own Source. Asked for every tile, to add what Overpass lacks, and
 * the stand-in when Overpass is down.
 */
fun interface WikipediaGeoSearchClient {
    fun search(center: Position, radiusMeters: Int): String
}

/** Where the places around the driver come from: online ([SubjectFinder]) or a Region pack ([PackFirst]). */
interface SubjectSource {
    /** The places within reach of [position]; with a [headingDegrees], a source may also load what lies ahead. */
    fun subjectsAround(position: Position, headingDegrees: Double? = null): SubjectsAround
}

/** What [SubjectFinder] found, and what went wrong on the way (so the screen can say so), if anything. */
data class SubjectsAround(val subjects: List<Subject>, val problem: String?)

/**
 * Finds the Towns and Landmarks around the driver. The map is cut into tiles; each tile is
 * asked for once and remembered, which keeps us within the public Overpass usage rules.
 *
 * Wikipedia's map search (when given) is asked for every tile too: it adds landmarks (temples, bridges, palaces) that
 * OpenStreetMap does not link to an article, minus schools and the like and anything Overpass already has by name.
 * If one of the two cannot answer, the other serves the tile, which is tried again after a minute.
 * The caches are the only mutable state here, and they are thread-safe.
 */
class SubjectFinder(
    private val overpass: OverpassClient,
    private val clockMillis: () -> Long,
    private val geoSearch: WikipediaGeoSearchClient? = null,
) : SubjectSource {
    /** [note] explains, for a tile served by the fallback, why. */
    /** [note] explains, for a tile that is [incomplete] (one of its two sources failed), why. */
    private class Loaded(val subjects: List<Subject>, val atMillis: Long, val incomplete: Boolean, val note: String?)

    private val loadedTiles = ConcurrentHashMap<Tile, Loaded>()
    private val failures = ConcurrentHashMap<Tile, Pair<Long, String>>()

    /**
     * Everything in the tiles within about 3 km of [position], loading tiles not seen before. With a [headingDegrees]
     * the tile about 5 km ahead is loaded too, so a slow or failing Overpass has time to answer before the driver gets there.
     * A tile that cannot be loaded is skipped, and not asked for again for a while; [SubjectsAround.problem] says why.
     */
    override fun subjectsAround(position: Position, headingDegrees: Double?): SubjectsAround {
        val tiles = (tilesAround(position) + listOfNotNull(headingDegrees?.let { tileAhead(position, it) })).distinct()
        val now = clockMillis()
        val subjects = tiles.flatMap { tile -> upToDate(tile, now)?.subjects ?: load(tile, now) }.distinctBy { it.id }
        val problem = tiles.firstNotNullOfOrNull { tile ->
            failures[tile]?.second ?: loadedTiles[tile]?.takeIf { it.incomplete }?.note
        }
        return SubjectsAround(subjects, problem)
    }

    /** A loaded tile, unless one of its sources failed long enough ago that it deserves another try. */
    private fun upToDate(tile: Tile, now: Long): Loaded? =
        loadedTiles[tile]?.takeIf { !it.incomplete || now - it.atMillis < INCOMPLETE_REFRESH_MILLIS }

    /**
     * A tile is Overpass's subjects plus the articles Wikipedia's map search knows (when given), which add landmarks
     * Overpass does not link to an article. If one of the two fails the other still serves the tile, and the tile is
     * tried again after a minute; if both fail the tile is skipped for now.
     */
    private fun load(tile: Tile, now: Long): List<Subject> {
        val stale = loadedTiles[tile]?.subjects.orEmpty()
        failures[tile]?.let { (failedAt, _) -> if (now - failedAt < RETRY_AFTER_MILLIS) return stale }

        val overpassAnswer = attempt { parseOverpass(overpass.query(tile.box())) }
        val search = geoSearch
        val searchAnswer = search?.let { client ->
            attempt { parseGeoSearch(client.search(tile.center(), SEARCH_RADIUS_METERS)).filterNot { isInstitution(it.name) } }
        }
        val overpassSubjects = overpassAnswer.subjects
        val searchSubjects = searchAnswer?.subjects

        val (subjects, note) = when {
            overpassSubjects != null && (searchAnswer == null || searchSubjects != null) ->
                merge(overpassSubjects, searchSubjects.orEmpty()) to null
            overpassSubjects != null -> overpassSubjects to "Wikipedia geosearch: ${searchAnswer?.problem}"
            searchSubjects != null -> searchSubjects to "Overpass failed (${overpassAnswer.problem}); using Wikipedia's map search instead"
            else -> return failed(
                tile, now,
                if (searchAnswer == null) overpassAnswer.problem.orEmpty() else "${overpassAnswer.problem}; Wikipedia geosearch: ${searchAnswer.problem}",
                stale,
            )
        }
        loadedTiles[tile] = Loaded(subjects, now, incomplete = note != null, note = note)
        failures.remove(tile)
        return subjects
    }

    private class Answer(val subjects: List<Subject>?, val problem: String?)

    private fun attempt(request: () -> List<Subject>): Answer = try {
        Answer(request(), null)
    } catch (e: java.io.IOException) {
        Answer(null, describe(e))
    } catch (e: IllegalArgumentException) {
        // Includes the JSON library's SerializationException: a response we cannot read.
        Answer(null, describe(e))
    }

    /** Overpass's subjects, then the articles it lacks: Wikipedia's results naming something Overpass already has are dropped. */
    private fun merge(fromOverpass: List<Subject>, fromSearch: List<Subject>): List<Subject> {
        val names = fromOverpass.map { it.name.lowercase() }.toSet()
        val tags = fromOverpass.mapNotNull { it.wikipediaTag }.toSet()
        return fromOverpass + fromSearch.filter { it.name.lowercase() !in names && it.wikipediaTag !in tags }
    }

    /** Schools, colleges, constituencies and the like are not places to point out from the road. */
    private fun isInstitution(title: String) = INSTITUTION.containsMatchIn(title)

    private fun failed(tile: Tile, now: Long, problem: String, stale: List<Subject>): List<Subject> {
        failures[tile] = now to problem
        return stale
    }

    private fun describe(cause: Exception) = cause.message ?: cause.javaClass.simpleName

    private data class Tile(val row: Int, val column: Int) {
        fun box() = BoundingBox(
            south = row * TILE_DEGREES,
            west = column * TILE_DEGREES,
            north = (row + 1) * TILE_DEGREES,
            east = (column + 1) * TILE_DEGREES,
        )

        fun center() = Position((row + 0.5) * TILE_DEGREES, (column + 0.5) * TILE_DEGREES)
    }

    private fun tilesAround(position: Position): List<Tile> {
        val latMargin = REACH_METERS / METERS_PER_DEGREE
        val lonMargin = latMargin / cos(Math.toRadians(position.latitude)).coerceAtLeast(MIN_COSINE)
        val rows = tileIndex(position.latitude - latMargin)..tileIndex(position.latitude + latMargin)
        val columns = tileIndex(position.longitude - lonMargin)..tileIndex(position.longitude + lonMargin)
        return rows.flatMap { row -> columns.map { column -> Tile(row, column) } }
    }

    private fun tileAhead(position: Position, headingDegrees: Double): Tile {
        val heading = Math.toRadians(headingDegrees)
        val latitude = position.latitude + cos(heading) * LOOK_AHEAD_METERS / METERS_PER_DEGREE
        val metresPerLongitudeDegree = METERS_PER_DEGREE * cos(Math.toRadians(position.latitude)).coerceAtLeast(MIN_COSINE)
        val longitude = position.longitude + sin(heading) * LOOK_AHEAD_METERS / metresPerLongitudeDegree
        return Tile(tileIndex(latitude), tileIndex(longitude))
    }

    private fun tileIndex(degrees: Double): Int = floor(degrees / TILE_DEGREES).toInt()

    private companion object {
        val INSTITUTION = Regex("\\b(school|college|university|institute|polytechnic|academy|hospital|constituency|assembly)\\b", RegexOption.IGNORE_CASE)
        const val TILE_DEGREES = 0.1
        const val REACH_METERS = 3_000.0
        const val LOOK_AHEAD_METERS = 5_000.0
        const val RETRY_AFTER_MILLIS = 30_000L
        const val INCOMPLETE_REFRESH_MILLIS = 60_000L
                // Wikipedia's geosearch allows at most 10 km; 8 km covers a whole tile (about 11 km by 7 to 11 km) from its centre.
        const val SEARCH_RADIUS_METERS = 8_000
        const val MIN_COSINE = 0.1 // keeps the tile count small near the poles
        const val METERS_PER_DEGREE = 111_195.0
    }
}
