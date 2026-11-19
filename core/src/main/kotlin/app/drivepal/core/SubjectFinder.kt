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

/** What [SubjectFinder] found, and what went wrong on the way (so the screen can say so), if anything. */
data class SubjectsAround(val subjects: List<Subject>, val problem: String?)

/**
 * Finds the Towns and Landmarks around the driver. The map is cut into tiles; each tile is
 * asked for once and remembered, which keeps us within the public Overpass usage rules.
 * The cache is the one piece of mutable state here, and it is thread-safe.
 */
class SubjectFinder(
    private val overpass: OverpassClient,
    private val clockMillis: () -> Long,
) {
    private val loadedTiles = ConcurrentHashMap<Tile, List<Subject>>()
    private val failures = ConcurrentHashMap<Tile, Pair<Long, String>>()

    /**
     * Everything in the tiles within about 3 km of [position], loading tiles not seen before.
     * A tile that fails to load is skipped, and not asked for again for a while; [SubjectsAround.problem] says why.
     */
    fun subjectsAround(position: Position): SubjectsAround {
        val tiles = tilesAround(position)
        val subjects = tiles.flatMap { tile -> loadedTiles[tile] ?: load(tile) }.distinctBy { it.id }
        return SubjectsAround(subjects, problem = tiles.firstNotNullOfOrNull { failures[it]?.second })
    }

    private fun load(tile: Tile): List<Subject> {
        val failure = failures[tile]
        if (failure != null && clockMillis() - failure.first < RETRY_AFTER_MILLIS) return emptyList()
        return try {
            parseOverpass(overpass.query(tile.box())).also {
                loadedTiles[tile] = it
                failures.remove(tile)
            }
        } catch (e: java.io.IOException) {
            failed(tile, e)
        } catch (e: IllegalArgumentException) {
            // Includes the JSON library's SerializationException: a response we cannot read.
            failed(tile, e)
        }
    }

    private fun failed(tile: Tile, cause: Exception): List<Subject> {
        failures[tile] = clockMillis() to (cause.message ?: cause.javaClass.simpleName)
        return emptyList()
    }

    private data class Tile(val row: Int, val column: Int) {
        fun box() = BoundingBox(
            south = row * TILE_DEGREES,
            west = column * TILE_DEGREES,
            north = (row + 1) * TILE_DEGREES,
            east = (column + 1) * TILE_DEGREES,
        )
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
        const val MIN_COSINE = 0.1 // keeps the tile count small near the poles
        const val METERS_PER_DEGREE = 111_195.0
    }
}
