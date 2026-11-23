package app.drivepal.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The Region pack a Trip uses, if any, and what the driver is told about it ("Offline: Kerala", or why not). */
data class Shelf(val pack: RegionPack?, val notice: String?)

/**
 * Finds the Region pack in the packs folder at the start of a Trip. Each `name.drivepal` has a manifest beside it,
 * `name.json` (file, region, format_version, size, sha256), written by the builder: a pack that does not match its
 * manifest, or comes in a format this app does not read, is left unused, so the Trip goes online as before.
 * With more than one pack, the first by file name is used.
 */
object PackShelf {
    private const val PACK_EXTENSION = "drivepal"

    /** [openDatabase] opens a pack's file read-only: Android's SQLite on the phone, sqlite-jdbc in tests. */
    fun open(folder: File, openDatabase: (File) -> PackDatabase): Shelf {
        val pack = folder.listFiles { file -> file.isFile && file.extension == PACK_EXTENSION }
            ?.minByOrNull { it.name }
            ?: return Shelf(pack = null, notice = null)
        return try {
            openChecked(pack, openDatabase)
        } catch (refused: PackRefused) {
            Shelf(pack = null, notice = "${refused.message}, so online maps are used")
        }
    }

    private fun openChecked(file: File, openDatabase: (File) -> PackDatabase): Shelf {
        val manifestFile = File(file.parentFile, "${file.nameWithoutExtension}.json")
        if (!manifestFile.isFile) throw PackRefused("${file.name} has no manifest")
        val manifest = readManifest(manifestFile) ?: throw PackRefused("${file.name} has an unreadable manifest")
        val region = manifest.string("region") ?: file.nameWithoutExtension
        val format = manifest["format_version"]?.jsonPrimitive?.intOrNull
        if (format != SqlRegionPack.FORMAT_VERSION) throw PackRefused("The $region pack needs a newer DrivePal")
        val intact = manifest["size"]?.jsonPrimitive?.longOrNull == file.length() && manifest.string("sha256") == sha256(file)
        if (!intact) throw PackRefused("The $region pack is damaged")
        val pack = try {
            SqlRegionPack.open(openDatabase(file))
        } catch (unsupported: UnsupportedRegionPack) {
            throw PackRefused("The $region pack needs a newer DrivePal")
        } catch (broken: Exception) {
            throw PackRefused("The $region pack is damaged")
        }
        return Shelf(pack, notice = "Offline: ${pack.region}")
    }

    private fun readManifest(file: File): JsonObject? =
        try {
            Json.parseToJsonElement(file.readText()) as? JsonObject
        } catch (malformed: IllegalArgumentException) {
            null
        }

    private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class PackRefused(message: String) : Exception(message)
}
