package app.drivepal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlRegionPackTest {

    private val folder = createTempDirectory("pack").toFile()
    private val databases = mutableListOf<JdbcPackDatabase>()

    @AfterTest
    fun cleanUp() {
        databases.forEach { it.close() }
        folder.deleteRecursively()
    }

    private fun openPack(formatVersion: Int = 1): SqlRegionPack {
        val file = File(folder, "kerala-$formatVersion.drivepal")
        copyFixturePack(file, formatVersion)
        return SqlRegionPack.open(JdbcPackDatabase(file).also { databases += it })
    }

    private val noOnline = object : SubjectSource {
        override fun subjectsAround(position: Position, headingDegrees: Double?) = error("must not go online inside the region")
    }

    @Test
    fun `a pack names its region`() {
        assertEquals("Kerala", openPack().region)
    }

    @Test
    fun `the places within reach come from the pack, also across a grid cell edge, and not one twenty kilometres away`() {
        val found = PackFirst(openPack(), noOnline).subjectsAround(Position(9.3570, 76.6780), headingDegrees = 45.0)

        assertEquals(
            listOf(
                Subject("node/11", "Pullad", SubjectKind.Town, Position(9.3555, 76.6761)),
                Subject("node/12", "Muttumon", SubjectKind.Town, Position(9.3601, 76.6830)),
                Subject("node/13", "Kumbanad", SubjectKind.Junction, Position(9.3640, 76.6900)),
                Subject("node/15", "Thottabhagam", SubjectKind.Town, Position(9.3490, 76.6750)),
                Subject("way/21", "Kattode", SubjectKind.Town, Position(9.362, 76.672)),
            ),
            found.subjects,
        )
    }

    @Test
    fun `the areas read from the pack give the same answers as the boundaries they were written from`() {
        val areas = OfflineAreas(openPack())

        assertEquals(Area("India", "Kerala"), areas.areaAt(Position(9.5, 76.5)))
        assertEquals(Area("India", "Puducherry"), areas.areaAt(Position(9.05, 76.45)))
        assertEquals(Area("India", "Tamil Nadu"), areas.areaAt(Position(9.5, 77.5)))
        assertNull(areas.areaAt(Position(9.5, 75.5)))
        // A province that does not touch the region is left out of its pack.
        assertNull(areas.areaAt(Position(9.5, 78.75)))
        assertTrue(areas.covers(Position(9.5, 76.5)))
        assertFalse(areas.covers(Position(9.05, 76.45)))
    }

    @Test
    fun `places just beyond the region's border are in the pack, and places far beyond it are not`() {
        val pack = openPack()

        val nearTheBorder = pack.placesWithin(BoundingBox(south = 9.49, west = 77.01, north = 9.51, east = 77.03))
        val farBeyond = pack.placesWithin(BoundingBox(south = 9.49, west = 77.49, north = 9.51, east = 77.51))

        assertEquals(listOf(PackPlace("node/18", "Bodinayakanur", "town", Position(9.5, 77.02))), nearTheBorder)
        assertEquals(emptyList(), farBeyond)
    }

    @Test
    fun `a pack in a format this app does not know is refused, saying why`() {
        val refused = assertFailsWith<UnsupportedRegionPack> { openPack(formatVersion = 2) }

        assertEquals("Region pack format 2 is not supported (this app reads format 1)", refused.message)
    }
}
