package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineAreasTest {

    private val areas = OfflineAreas(FakeRegionPack("Kerala", areas = keralaAndNeighbours))

    private val inKerala = Position(9.5, 76.5)
    private val inMahe = Position(9.05, 76.45)
    private val inTamilNadu = Position(9.5, 77.5)
    private val atSea = Position(9.5, 75.5)

    @Test
    fun `a point in the region is in its province and country`() {
        assertEquals(Area(country = "India", province = "Kerala"), areas.areaAt(inKerala))
    }

    @Test
    fun `an enclave cut out of the region belongs to the province around it, not the region`() {
        assertEquals(Area(country = "India", province = "Puducherry"), areas.areaAt(inMahe))
    }

    @Test
    fun `a neighbouring province is known, and the sea is in no area`() {
        assertEquals(Area(country = "India", province = "Tamil Nadu"), areas.areaAt(inTamilNadu))
        assertNull(areas.areaAt(atSea))
    }

    @Test
    fun `only the region itself is covered by the pack, not its neighbours or an enclave`() {
        assertTrue(areas.covers(inKerala))
        assertFalse(areas.covers(inMahe))
        assertFalse(areas.covers(inTamilNadu))
        assertFalse(areas.covers(atSea))
    }
}
