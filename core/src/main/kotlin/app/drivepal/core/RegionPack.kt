package app.drivepal.core

/**
 * A Region pack, as the app reads it: rows, no decisions. It is the SQLite file on the phone, or a fake in tests.
 * Which Area a point is in, and what counts as near, is decided in `:core` from these rows.
 */
interface RegionPack {
    /** The State or Province the pack is for, such as "Kerala". */
    val region: String

    /** The places and named Junctions inside [box]. */
    fun placesWithin(box: BoundingBox): List<PackPlace>

    /** The Areas whose bounding box contains [position]: candidates, to be checked against their polygons. */
    fun areasAround(position: Position): List<PackArea>
}

/** A named place or Junction from a pack. [id] is the OpenStreetMap id ("node/123"), as online. [type] is its OSM tag value. */
data class PackPlace(val id: String, val name: String, val type: String, val position: Position)

/** A Province's boundary from a pack. [isRegion] is true for the pack's own State or Province, not its neighbours. */
data class PackArea(val name: String, val country: String, val isRegion: Boolean, val polygons: List<PackPolygon>)

/** One part of a boundary: a closed [outer] ring and the [holes] cut out of it (an enclave such as Mahé). */
data class PackPolygon(val outer: List<Position>, val holes: List<List<Position>> = emptyList())
