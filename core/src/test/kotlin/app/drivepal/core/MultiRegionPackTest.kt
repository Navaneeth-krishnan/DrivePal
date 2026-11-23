package app.drivepal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pack built for two regions at once (Kerala and Tamil Nadu), from the same extract as the Kerala-only pack. */
class MultiRegionPackTest {

    private val folder = createTempDirectory("pack").toFile()
    private val databases = mutableListOf<JdbcPackDatabase>()

    @AfterTest
    fun cleanUp() {
        databases.forEach { it.close() }
        folder.deleteRecursively()
    }

    private fun openPack(): SqlRegionPack {
        val file = File(folder, "south-fixture.drivepal")
        copyFixturePack(file, fixtureName = "south-fixture")
        return SqlRegionPack.open(JdbcPackDatabase(file).also { databases += it })
    }

    private val noOnline = object : SubjectSource {
        override fun subjectsAround(position: Position, headingDegrees: Double?) = error("must not go online inside the pack's regions")
    }

    @Test
    fun `a pack for two regions is named for both together`() {
        assertEquals("South India", openPack().region)
    }

    @Test
    fun `places deep inside the second region come from the pack too, with no network`() {
        val found = PackFirst(openPack(), noOnline).subjectsAround(Position(9.5, 77.5))

        assertEquals(listOf(Subject("node/19", "Theni", SubjectKind.Town, Position(9.5, 77.5))), found.subjects)
    }

    @Test
    fun `both regions are covered, and a province that touches neither is still left out`() {
        val areas = OfflineAreas(openPack())

        assertTrue(areas.covers(Position(9.5, 76.5)))
        assertTrue(areas.covers(Position(9.5, 77.5)))
        assertFalse(areas.covers(Position(9.05, 76.45)))
        assertEquals(Area("India", "Tamil Nadu", provinceNames = mapOf("ml" to "തമിഴ്നാട്")), areas.areaAt(Position(9.5, 77.5)))
        assertEquals(null, areas.areaAt(Position(9.5, 78.75)))
    }
}
