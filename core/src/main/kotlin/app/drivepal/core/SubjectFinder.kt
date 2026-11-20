package app.drivepal.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.floor

/** A rectangle on the map, in degrees. */
data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double)

/** Asks Overpass for everything inside a box and returns the raw JSON. The one place the network is touched. */
fun interface OverpassClient {
    fun query(box: BoundingBox): String
}

/**
 * Asks Wikipedia (the English one) for the articles about places within [radiusMeters] of [center], as raw JSON.
 * The fallback when Overpass is down: each article is a subject that already names its own Source.
 */
fun interface WikipediaGeoSearchClient {
    fun search(center: Position, radiusMeters: Int): String
}

/** What [SubjectFinder] found, and what went wrong on the way (so the screen can say so), if anything. */
data class SubjectsAround(val subjects: List<Subject>, val problem: String?)

/**
 * Finds the Towns and Landmarks around the driver. The map is cut into tiles; each tile is
 * asked for once and remembered, which keeps us within the public Overpass usage rules.
 *
 * If Overpass cannot answer for a tile, Wikipedia's map search stands in for it (when given). That stand-in is
 * thinner (no Town or Landmark label, only subjects with an English article), so Overpass is tried again after a while.
 * The caches are the only mutable state here, and they are thread-safe.
 */
class SubjectFinder(
    private val overpass: OverpassClient,
    private val clockMillis: () -> Long,
    private val geoSearch: WikipediaGeoSearchClient? = null,
) {
    /** [note] explains, for a tile served by the fallback, why. */
    private class Loaded(val subjects: List<Subject>, val atMillis: Long, val fromFallback: Boolean, val note: String?)

    private val loadedTiles = ConcurrentHashMap<Tile, Loaded>()
    private val failures = ConcurrentHashMap<Tile, Pair<Long, String>>()

    /**
     * Everything in the tiles within about 3 km of [position], loading tiles not seen before.
     * A tile that cannot be loaded is skipped, and not asked for again for a while; [SubjectsAround.problem] says why.
     */
    fun subjectsAround(position: Position): SubjectsAround {
        val tiles = tilesAround(position)
        val now = clockMillis()
        val subjects = tiles.flatMap { tile -> upToDate(tile, now)?.subjects ?: load(tile, now) }.distinctBy { it.id }
        val problem = tiles.firstNotNullOfOrNull { tile ->
            failures[tile]?.second ?: loadedTiles[tile]?.takeIf { it.fromFallback }?.note
        }
        return SubjectsAround(subjects, problem)
    }

    /** A loaded tile, unless it came from the fallback long enough ago that Overpass deserves another try. */
    private fun upToDate(tile: Tile, now: Long): Loaded? =
        loadedTiles[tile]?.takeIf { !it.fromFallback || now - it.atMillis < FALLBACK_REFRESH_MILLIS }

    private fun load(tile: Tile, now: Long): List<Subject> {
        val stale = loadedTiles[tile]?.subjects.orEmpty()
        failures[tile]?.let { (failedAt, _) -> if (now - failedAt < RETRY_AFTER_MILLIS) return stale }

        val overpassProblem = try {
            val subjects = parseOverpass(overpass.query(tile.box()))
            loadedTiles[tile] = Loaded(subjects, now, fromFallback = false, note = null)
            failures.remove(tile)
            return subjects
        } catch (e: java.io.IOException) {
            describe(e)
        } catch (e: IllegalArgumentException) {
            // Includes the JSON library's SerializationException: a response we cannot read.
            describe(e)
        }

        val search = geoSearch ?: return failed(tile, now, overpassProblem, stale)
        return try {
            val subjects = parseGeoSearch(search.search(tile.center(), SEARCH_RADIUS_METERS))
            loadedTiles[tile] = Loaded(
                subjects, now, fromFallback = true,
                note = "Overpass failed ($overpassProblem); using Wikipedia's map search instead",
            )
            failures.remove(tile)
            subjects
        } catch (e: java.io.IOException) {
            failed(tile, now, "$overpassProblem; Wikipedia geosearch: ${describe(e)}", stale)
        } catch (e: IllegalArgumentException) {
            failed(tile, now, "$overpassProblem; Wikipedia geosearch: ${describe(e)}", stale)
        }
    }

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

    private fun tileIndex(degrees: Double): Int = floor(degrees / TILE_DEGREES).toInt()

    private companion object {
        const val TILE_DEGREES = 0.1
        const val REACH_METERS = 3_000.0
        const val RETRY_AFTER_MILLIS = 30_000L
        const val FALLBACK_REFRESH_MILLIS = 5 * 60_000L
        // Wikipedia's geosearch allows at most 10 km; 8 km covers a whole tile (about 11 km by 7 to 11 km) from its centre.
        const val SEARCH_RADIUS_METERS = 8_000
        const val MIN_COSINE = 0.1 // keeps the tile count small near the poles
        const val METERS_PER_DEGREE = 111_195.0
    }
}
