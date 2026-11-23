package app.drivepal.core

/** An in-memory Region pack, honouring the same contract as the SQLite one: rows only, no decisions. */
class FakeRegionPack(
    override val region: String,
    private val places: List<PackPlace> = emptyList(),
    private val areas: List<PackArea> = emptyList(),
) : RegionPack {
    var placeQueries = 0
        private set

    override fun placesWithin(box: BoundingBox): List<PackPlace> {
        placeQueries++
        return places.filter {
            it.position.latitude in box.south..box.north && it.position.longitude in box.west..box.east
        }
    }

    override fun areasAround(position: Position): List<PackArea> = areas.filter { area ->
        area.polygons.any { polygon ->
            position.latitude in polygon.outer.minOf { it.latitude }..polygon.outer.maxOf { it.latitude } &&
                position.longitude in polygon.outer.minOf { it.longitude }..polygon.outer.maxOf { it.longitude }
        }
    }
}

/** A rectangle as a closed ring, south-west corner first. */
fun ring(south: Double, west: Double, north: Double, east: Double): List<Position> = listOf(
    Position(south, west), Position(south, east), Position(north, east), Position(north, west), Position(south, west),
)

/** Kerala with a Mahé-shaped hole, Puducherry's Mahé filling it, and Tamil Nadu to the east. */
val keralaAndNeighbours = listOf(
    PackArea(
        name = "Kerala", country = "India", isRegion = true,
        polygons = listOf(PackPolygon(outer = ring(8.0, 76.0, 10.0, 77.0), holes = listOf(ring(9.0, 76.4, 9.1, 76.5)))),
    ),
    PackArea(name = "Puducherry", country = "India", isRegion = false, polygons = listOf(PackPolygon(ring(9.0, 76.4, 9.1, 76.5)))),
    PackArea(name = "Tamil Nadu", country = "India", isRegion = false, polygons = listOf(PackPolygon(ring(8.0, 77.0, 10.0, 78.0)))),
)
