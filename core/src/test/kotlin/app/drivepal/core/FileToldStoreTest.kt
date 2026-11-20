package app.drivepal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileToldStoreTest {

    private fun newFile(): File = Files.createTempDirectory("drivepal").resolve("told.json").toFile()

    @Test
    fun `what was saved is loaded again`() {
        val file = newFile()

        FileToldStore(file).save(mapOf("node/1" to 1_000L, "area/country/Netherlands" to 2_000L))

        assertEquals(mapOf("node/1" to 1_000L, "area/country/Netherlands" to 2_000L), FileToldStore(file).load())
    }

    @Test
    fun `before anything was saved the memory is empty`() {
        assertEquals(emptyMap(), FileToldStore(newFile()).load())
    }

    @Test
    fun `a damaged file counts as an empty memory instead of stopping the drive`() {
        val file = newFile().also { it.writeText("{ this is not json") }

        assertEquals(emptyMap(), FileToldStore(file).load())
    }

    @Test
    fun `a file that cannot be replaced loses a little memory but never stops the drive`() {
        val directory = Files.createTempDirectory("drivepal")
        val file = directory.resolve("told.json").toFile()
        file.mkdir()                                  // a folder where the file should be: the rename cannot succeed
        File(file, "stuck").writeText("x")

        FileToldStore(file).save(mapOf("node/1" to 1L)) // must not throw

        assertEquals(emptyMap(), FileToldStore(file).load())
    }

    @Test
    fun `saving leaves one complete file and no temporary leftovers`() {
        val file = newFile()

        FileToldStore(file).save(mapOf("node/1" to 1L))
        FileToldStore(file).save(mapOf("node/2" to 2L))

        assertEquals(listOf("told.json"), file.parentFile.list()!!.toList())
        assertEquals(mapOf("node/2" to 2L), FileToldStore(file).load())
    }
}
