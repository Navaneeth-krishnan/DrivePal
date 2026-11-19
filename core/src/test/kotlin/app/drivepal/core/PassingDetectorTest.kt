package app.drivepal.core

import kotlin.math.cos
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassingDetectorTest {

    private val castle = Subject(
        id = "castle",
        name = "Castle",
        kind = SubjectKind.Landmark,
        position = ROAD_START.moved(northMeters = 10_000.0, eastMeters = 2_500.0),
    )

    @Test
    fun `a landmark two and a half kilometres beside the motorway is passed`() {
        val (_, events) = PassingDetector.watching(listOf(castle))
            .driven(roadNorth(fromMeters = 0, toMeters = 20_000))

        assertEquals(listOf<PassingEvent>(PassingEvent.Passing(castle)), events)
    }

    @Test
    fun `a landmark four kilometres from the motorway is never passed`() {
        val faraway = castle.copy(position = ROAD_START.moved(northMeters = 10_000.0, eastMeters = 4_000.0))

        val (_, events) = PassingDetector.watching(listOf(faraway))
            .driven(roadNorth(fromMeters = 0, toMeters = 20_000))

        assertEquals(emptyList<PassingEvent>(), events)
    }

    @Test
    fun `a landmark is relevant while within three kilometres and drops out once left behind`() {
        val atTheCastle = PassingDetector.watching(listOf(castle))
            .driven(roadNorth(fromMeters = 0, toMeters = 10_000)).first
        val wellPastTheCastle = atTheCastle
            .driven(roadNorth(fromMeters = 10_140, toMeters = 20_000)).first

        assertEquals(listOf(castle), atTheCastle.relevantSubjects)
        assertEquals(emptyList<Subject>(), wellPastTheCastle.relevantSubjects)
    }

    @Test
    fun `the heading is unknown until the driver has moved`() {
        val atTheStart = PassingDetector.watching(emptyList()).withPosition(ROAD_START).detector

        assertNull(atTheStart.headingDegrees)
    }

    @Test
    fun `the heading follows the road when it turns from north to east`() {
        val heading = PassingDetector.watching(emptyList())
        val goingNorth = heading.driven(roadNorth(fromMeters = 0, toMeters = 2_000)).first
        val corner = ROAD_START.moved(northMeters = 2_000.0)
        val goingEast = goingNorth.driven((140..2_000 step 140).map { corner.moved(eastMeters = it.toDouble()) }).first

        assertEquals(0.0, goingNorth.headingDegrees!!, 1.0)
        assertEquals(90.0, goingEast.headingDegrees!!, 1.0)
    }

    @Test
    fun `GPS jitter while standing in a jam does not turn the heading`() {
        val road = roadNorth(fromMeters = 0, toMeters = 2_000)
        val jitter = listOf(3.0, -3.0, 2.0, -3.0, 3.0).map { road.last().moved(eastMeters = it) }

        val inTheJam = PassingDetector.watching(emptyList())
            .driven(road).first
            .driven(jitter).first

        assertEquals(0.0, inTheJam.headingDegrees!!, 1.0)
    }

    @Test
    fun `a landmark is ahead before the driver reaches it and behind once passed`() {
        val shortOfTheCastle = PassingDetector.watching(listOf(castle))
            .driven(roadNorth(fromMeters = 0, toMeters = 7_500)).first
        val pastTheCastle = shortOfTheCastle
            .driven(roadNorth(fromMeters = 7_640, toMeters = 12_500)).first

        assertTrue(shortOfTheCastle.isAhead(castle))
        assertFalse(pastTheCastle.isAhead(castle))
    }

    @Test
    fun `starting a trip just past a landmark passes it and reports it behind`() {
        val (detector, events) = PassingDetector.watching(listOf(castle))
            .driven(roadNorth(fromMeters = 11_000, toMeters = 11_400))

        assertEquals(listOf<PassingEvent>(PassingEvent.Passing(castle)), events)
        assertFalse(detector.isAhead(castle))
    }

    @Test
    fun `subjects loaded while driving are passed once and never announced again`() {
        val beforeTheCastleIsKnown = PassingDetector.watching(emptyList())
            .driven(roadNorth(fromMeters = 0, toMeters = 7_000)).first

        val (afterPassing, events) = beforeTheCastleIsKnown
            .withSubjects(listOf(castle))
            .driven(roadNorth(fromMeters = 7_140, toMeters = 12_000))
        val (_, eventsAfterReload) = afterPassing
            .withSubjects(listOf(castle))
            .driven(roadNorth(fromMeters = 12_140, toMeters = 13_000))

        assertEquals(listOf<PassingEvent>(PassingEvent.Passing(castle)), events)
        assertEquals(emptyList<PassingEvent>(), eventsAfterReload)
    }

    @Test
    fun `a landmark beside the road is still passed when GPS goes quiet for ten kilometres`() {
        val route = roadNorth(fromMeters = 0, toMeters = 5_000) + roadNorth(fromMeters = 15_000, toMeters = 16_000)

        val (_, events) = PassingDetector.watching(listOf(castle)).driven(route)

        assertEquals(listOf<PassingEvent>(PassingEvent.Passing(castle)), events)
    }

    @Test
    fun `GPS noise of a dozen metres while standing does not turn the heading`() {
        val road = roadNorth(fromMeters = 0, toMeters = 2_000)
        val noise = listOf(12.0, -12.0, 9.0, -11.0, 12.0).map { road.last().moved(eastMeters = it) }

        val standing = PassingDetector.watching(emptyList()).driven(road).first.driven(noise).first

        assertEquals(0.0, standing.headingDegrees!!, 1.0)
    }
}

private const val METERS_PER_DEGREE = 111_195.0
private val ROAD_START = Position(latitude = 52.0, longitude = 4.0)

/** A flat-earth offset: independent of the haversine maths the detector uses. */
private fun Position.moved(northMeters: Double = 0.0, eastMeters: Double = 0.0) = Position(
    latitude = latitude + northMeters / METERS_PER_DEGREE,
    longitude = longitude + eastMeters / (METERS_PER_DEGREE * cos(latitude * PI / 180)),
)

/** Positions along a straight road going north, one every [stepMeters] (about 5 s at 100 km/h). */
private fun roadNorth(fromMeters: Int, toMeters: Int, stepMeters: Int = 140): List<Position> =
    (fromMeters..toMeters step stepMeters).map { ROAD_START.moved(northMeters = it.toDouble()) }

private fun PassingDetector.driven(route: List<Position>): Pair<PassingDetector, List<PassingEvent>> =
    route.fold(this to emptyList()) { (detector, events), position ->
        val update = detector.withPosition(position)
        update.detector to events + update.events
    }
