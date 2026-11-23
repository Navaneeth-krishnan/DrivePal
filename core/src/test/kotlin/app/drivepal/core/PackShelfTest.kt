package app.drivepal.core

import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackShelfTest {

    private val folder = createTempDirectory("packs").toFile()
    private val databases = mutableListOf<JdbcPackDatabase>()
    private val openDatabase = { file: File -> JdbcPackDatabase(file).also { databases += it } }

    @AfterTest
    fun cleanUp() {
        databases.forEach { it.close() }
        folder.deleteRecursively()
    }

    private val pack = File(folder, "kerala-fixture.drivepal")
    private val manifest = File(folder, "kerala-fixture.json")

    private fun copyManifest() {
        PackShelfTest::class.java.getResourceAsStream("/packs/kerala-fixture.json")!!.use { input ->
            manifest.outputStream().use { input.copyTo(it) }
        }
    }

    @Test
    fun `a pack that matches its manifest is used, and the driver is told the trip is offline in its region`() {
        copyFixturePack(pack)
        copyManifest()

        val shelf = PackShelf.open(folder, openDatabase)

        assertEquals("Kerala", shelf.pack?.region)
        assertEquals("Offline: Kerala", shelf.notice)
    }

    @Test
    fun `a pack changed since it was built is not used, and the driver is told why`() {
        copyFixturePack(pack)
        copyManifest()
        pack.appendBytes(byteArrayOf(0))

        val shelf = PackShelf.open(folder, openDatabase)

        assertNull(shelf.pack)
        assertEquals("The Kerala pack is damaged, so online maps are used", shelf.notice)
    }

    @Test
    fun `a pack without its manifest is not used, and the driver is told why`() {
        copyFixturePack(pack)

        val shelf = PackShelf.open(folder, openDatabase)

        assertNull(shelf.pack)
        assertEquals("kerala-fixture.drivepal has no manifest, so online maps are used", shelf.notice)
    }

    @Test
    fun `a pack from a newer builder is not used, and the driver is told to update the app`() {
        copyFixturePack(pack, formatVersion = 2)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(pack.readBytes()).joinToString("") { "%02x".format(it) }
        manifest.writeText(
            """{"file": "kerala-fixture.drivepal", "region": "Kerala", "format_version": 2, "size": ${pack.length()}, "sha256": "$sha256"}""",
        )

        val shelf = PackShelf.open(folder, openDatabase)

        assertNull(shelf.pack)
        assertEquals("The Kerala pack needs a newer DrivePal, so online maps are used", shelf.notice)
    }

    @Test
    fun `with no packs folder the trip goes online as before, with nothing to tell the driver`() {
        val shelf = PackShelf.open(File(folder, "missing"), openDatabase)

        assertEquals(Shelf(pack = null, notice = null), shelf)
    }
}
