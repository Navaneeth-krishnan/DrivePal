package app.drivepal.core

/** Which Area a point is in, and whether the pack covers it, worked out from a Region pack's boundaries. */
class OfflineAreas(private val pack: RegionPack) {

    /** The Province and Country containing [position], or null where the pack knows no Area (the sea, beyond its neighbours). */
    fun areaAt(position: Position): Area? = containing(position)?.let { Area(country = it.country, province = it.name, provinceNames = it.names) }

    /** True inside the pack's own region (not a neighbour, not an enclave cut out of it). */
    fun covers(position: Position): Boolean = containing(position)?.isRegion == true

    private fun containing(position: Position): PackArea? =
        pack.areasAround(position).firstOrNull { area -> area.polygons.any { it.contains(position) } }
}

private fun PackPolygon.contains(position: Position): Boolean =
    insideRing(position, outer) && holes.none { insideRing(position, it) }

/** Ray casting along the longitude axis: count how many ring edges a line east of the point crosses. */
private fun insideRing(point: Position, ring: List<Position>): Boolean {
    var inside = false
    for (index in 0 until ring.size - 1) {
        val a = ring[index]
        val b = ring[index + 1]
        val straddles = (a.latitude > point.latitude) != (b.latitude > point.latitude)
        if (straddles) {
            val crossingLongitude = a.longitude + (point.latitude - a.latitude) / (b.latitude - a.latitude) * (b.longitude - a.longitude)
            if (point.longitude < crossingLongitude) inside = !inside
        }
    }
    return inside
}
