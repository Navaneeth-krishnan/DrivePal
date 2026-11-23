package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PackFirstTest {

    private val pullad = PackPlace("node/11", "Pullad", "village", Position(9.3555, 76.6761))
    private val muttumon = PackPlace("node/12", "Muttumon", "locality", Position(9.3601, 76.6830))
    private val junction = PackPlace("node/13", "Kumbanad", "motorway_junction", Position(9.3640, 76.6900))
    private val twentyKilometresAway = PackPlace("node/14", "Kozhencherry", "town", Position(9.5355, 76.6761))
    private val pack = FakeRegionPack("Kerala", places = listOf(pullad, muttumon, junction, twentyKilometresAway), areas = keralaAndNeighbours)

    private val fromOverpass = Subject("node/99", "Kambam", SubjectKind.Town, Position(9.5, 77.5))
    private var onlineRequests = 0
    private val online = object : SubjectSource {
        override fun subjectsAround(position: Position, headingDegrees: Double?): SubjectsAround {
            onlineRequests++
            return SubjectsAround(listOf(fromOverpass), problem = "HTTP 502 from overpass-api.de")
        }
    }

    @Test
    fun `inside the region the places come from the pack, and nothing is asked online`() {
        val found = PackFirst(pack, online).subjectsAround(Position(9.3570, 76.6780), headingDegrees = 45.0)

        assertEquals(
            listOf(
                Subject("node/11", "Pullad", SubjectKind.Town, Position(9.3555, 76.6761)),
                Subject("node/12", "Muttumon", SubjectKind.Town, Position(9.3601, 76.6830)),
                Subject("node/13", "Kumbanad", SubjectKind.Junction, Position(9.3640, 76.6900)),
            ),
            found.subjects,
        )
        assertEquals(null, found.problem)
        assertEquals(0, onlineRequests)
    }

    @Test
    fun `outside the region the online finder answers, and the pack is not asked for places`() {
        val found = PackFirst(pack, online).subjectsAround(Position(9.5, 77.5), headingDegrees = 90.0)

        assertEquals(listOf(fromOverpass), found.subjects)
        assertEquals("HTTP 502 from overpass-api.de", found.problem)
        assertEquals(1, onlineRequests)
        assertEquals(0, pack.placeQueries)
    }
}
