package app.drivepal.core

import kotlin.math.cos

/**
 * Places from a Region pack inside its region, with no network at all; anywhere else, from [online] as before.
 * A pack's places have no Wikipedia link, so each one is only ever announced by name.
 */
class PackFirst(private val pack: RegionPack, private val online: SubjectSource) : SubjectSource {
    private val areas = OfflineAreas(pack)

    override fun subjectsAround(position: Position, headingDegrees: Double?): SubjectsAround {
        if (!areas.covers(position)) return online.subjectsAround(position, headingDegrees)
        val subjects = pack.placesWithin(boxAround(position)).map { place ->
            val kind = if (place.type == JUNCTION_TYPE) SubjectKind.Junction else SubjectKind.Town
            Subject(place.id, place.name, kind, place.position, names = place.names)
        }
        return SubjectsAround(subjects, problem = null)
    }

    private fun boxAround(position: Position): BoundingBox {
        val latitudeMargin = REACH_METERS / METERS_PER_DEGREE
        val longitudeMargin = latitudeMargin / cos(Math.toRadians(position.latitude)).coerceAtLeast(MIN_COSINE)
        return BoundingBox(
            south = position.latitude - latitudeMargin,
            west = position.longitude - longitudeMargin,
            north = position.latitude + latitudeMargin,
            east = position.longitude + longitudeMargin,
        )
    }

    private companion object {
        const val JUNCTION_TYPE = "motorway_junction"
        const val REACH_METERS = 3_000.0 // the Passing range: everything that could be relevant from here
        const val METERS_PER_DEGREE = 111_195.0
        const val MIN_COSINE = 0.1
    }
}
