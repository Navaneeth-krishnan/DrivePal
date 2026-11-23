package app.drivepal.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.floor

/** Runs one read-only query on a Region pack's SQLite file: Android's SQLite on the phone, sqlite-jdbc in tests. */
interface PackDatabase {
    /** Each row as its column values by name, for [sql] with its `?` placeholders bound to [args] in order. */
    fun query(sql: String, args: List<Any>): List<Map<String, Any?>>
}

/** Thrown for a file that is not a Region pack this app can read. */
class UnsupportedRegionPack(message: String) : Exception(message)

/**
 * A Region pack read with SQL (formats 1 and 2). The schema, which the builder script writes:
 *
 * - `meta (key, value)`: `format_version`, `region`, `country`, and when and from what it was built.
 * - `places (id, name, type, lat, lon, cell, wikipedia, wikidata)`: `cell` is a 0.05-degree grid square,
 *   `floor((lat + 90) / 0.05) * 7200 + floor((lon + 180) / 0.05)`, with an index, so a box is a few cells.
 * - `areas (id, name, country, is_region, south, west, north, east, polygons, wikipedia, wikidata)`: `polygons` is
 *   GeoJSON MultiPolygon coordinates ([longitude, latitude]; each polygon's first ring is its outline, then holes).
 * - Format 2 adds `names` to `places` and `areas`: a JSON object of the name in other languages by language code,
 *   `{"ml": "കേരളം"}`, or NULL when there is none.
 */
class SqlRegionPack private constructor(
    private val database: PackDatabase,
    override val region: String,
    format: Int,
) : RegionPack {
    private val namesColumn = if (format >= NAMES_FORMAT) "names" else "NULL AS names"

    override fun placesWithin(box: BoundingBox): List<PackPlace> {
        val cells = (row(box.south)..row(box.north)).flatMap { row -> (column(box.west)..column(box.east)).map { row * COLUMNS + it } }
        val sql = "SELECT id, name, type, lat, lon, $namesColumn FROM places WHERE cell IN (${cells.joinToString(",") { "?" }}) " +
            "AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY id"
        return database.query(sql, cells + listOf(box.south, box.north, box.west, box.east)).map { row ->
            PackPlace(
                id = row.getValue("id") as String,
                name = row.getValue("name") as String,
                type = row.getValue("type") as String,
                position = Position(row.number("lat"), row.number("lon")),
                names = names(row["names"] as String?),
            )
        }
    }

    override fun areasAround(position: Position): List<PackArea> =
        database.query(
            "SELECT name, country, is_region, polygons, $namesColumn FROM areas WHERE south <= ? AND north >= ? AND west <= ? AND east >= ? ORDER BY id",
            listOf(position.latitude, position.latitude, position.longitude, position.longitude),
        ).map { row ->
            PackArea(
                name = row.getValue("name") as String,
                country = row.getValue("country") as String,
                isRegion = row.number("is_region") == 1.0,
                polygons = polygons(row.getValue("polygons") as String),
                names = names(row["names"] as String?),
            )
        }

    companion object {
        /** The format the builder writes now. Packs from before Malayalam names (format 1) are still read. */
        const val FORMAT_VERSION = 2
        val READABLE_FORMATS = 1..FORMAT_VERSION
        private const val NAMES_FORMAT = 2
        private const val CELL_DEGREES = 0.05
        private const val COLUMNS = 7_200 // 360 degrees of longitude in 0.05-degree cells

        /** Opens the pack in [database], refusing one whose format this app does not read. */
        fun open(database: PackDatabase): SqlRegionPack {
            val meta = database.query("SELECT key, value FROM meta", emptyList())
                .associate { it.getValue("key") as String to it.getValue("value") as String }
            val format = meta["format_version"] ?: throw UnsupportedRegionPack("Region pack has no format version")
            val version = format.toIntOrNull()
            if (version == null || version !in READABLE_FORMATS) {
                throw UnsupportedRegionPack(
                    "Region pack format $format is not supported (this app reads formats ${READABLE_FORMATS.joinToString(" and ")})",
                )
            }
            val region = meta["region"] ?: throw UnsupportedRegionPack("Region pack does not name its region")
            return SqlRegionPack(database, region, version)
        }

        private fun row(latitude: Double) = floor((latitude + 90) / CELL_DEGREES).toInt()

        private fun column(longitude: Double) = floor((longitude + 180) / CELL_DEGREES).toInt()

        private fun Map<String, Any?>.number(column: String) = (getValue(column) as Number).toDouble()

        private fun names(json: String?): Map<String, String> =
            json?.let { Json.parseToJsonElement(it).jsonObject.mapValues { (_, name) -> name.jsonPrimitive.content } }.orEmpty()

        private fun polygons(json: String): List<PackPolygon> = Json.parseToJsonElement(json).jsonArray.map { polygon ->
            val rings = polygon.jsonArray.map { ring ->
                ring.jsonArray.map { point -> point.jsonArray.let { Position(it[1].jsonPrimitive.double, it[0].jsonPrimitive.double) } }
            }
            PackPolygon(outer = rings.first(), holes = rings.drop(1))
        }
    }
}
