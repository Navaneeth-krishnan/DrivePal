package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineAreaTrackerTest {

    private val offline = OfflineAreas(FakeRegionPack("Kerala", areas = keralaAndNeighbours))
    private var nominatimRequests = 0
    private val karnataka = """{"address":{"state":"Karnataka","country":"India","country_code":"in"}}"""

    private fun tracker() = AreaTracker.starting(
        nominatim = { nominatimRequests++; karnataka },
        clockMillis = { 0 },
        offline = offline,
    )

    private val inKerala = Position(9.5, 76.95)
    private val inTamilNadu = Position(9.5, 77.05)
    private val beyondThePack = Position(9.5, 75.5)

    @Test
    fun `crossing from the region into a neighbour is an entry, worked out from the pack alone`() {
        val atTheStart = tracker().withPosition(inKerala)
        val acrossTheBorder = atTheStart.tracker.withPosition(inTamilNadu)

        assertEquals(Area("India", "Kerala"), atTheStart.area)
        assertEquals(listOf<AreaEvent>(AreaEvent.EnteredProvince("Tamil Nadu")), acrossTheBorder.events)
        assertTrue(acrossTheBorder.fromPack)
        assertEquals(0, nominatimRequests)
    }

    @Test
    fun `leaving the pack's boundaries goes online, and the change of service is not taken for an entry`() {
        val atTheStart = tracker().withPosition(inKerala)
        val beyond = atTheStart.tracker.withPosition(beyondThePack)

        assertEquals(Area("India", "Karnataka"), beyond.area)
        assertEquals(emptyList<AreaEvent>(), beyond.events)
        assertFalse(beyond.fromPack)
        assertEquals(1, nominatimRequests)
    }
}
