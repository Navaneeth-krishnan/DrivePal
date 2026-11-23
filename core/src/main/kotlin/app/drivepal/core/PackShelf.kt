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
 * Finds the Region pack to use at the start of a Trip, in [open]'s folders in order: the first usable pack wins, so a
 * pack copied in by hand takes the place of the one that comes with the app. Each `name.drivepal` has a manifest
 * beside it, `name.json` (file, region, format_version, size, sha256), written by the builder: a pack that does not
 * match its manifest, or comes in a format this app does not read, is left unused, and the driver is told why.
 * Within one folder, the first pack by file name is used.
 */
object PackShelf {
    private const val PACK_EXTENSION = "drivepal"

    /** [openDatabase] opens a pack's file read-only: Android's SQLite on the phone, sqlite-jdbc in tests. */
    fun open(folders: List<File>, openDatabase: (File) -> PackDatabase): Shelf {
        val refusals = mutableListOf<String>()
        for (folder in folders) {
            val file = folder.listFiles { file -> file.isFile && file.extension == PACK_EXTENSION }?.minByOrNull { it.name } ?: continue
            try {
                val pack = openChecked(file, openDatabase)
                val notice = (listOf("Offline: ${pack.region}") + refusals.map { "$it, so it is not used" }).joinToString(". ")
                return Shelf(pack, notice)
            } catch (refused: PackRefused) {
                refusals += refused.message.orEmpty()
            }
        }
        return Shelf(pack = null, notice = refusals.firstOrNull()?.let { "$it, so online maps are used" })
    }

    private fun openChecked(file: File, openDatabase: (File) -> PackDatabase): RegionPack {
        val manifestFile = File(file.parentFile, "${file.nameWithoutExtension}.json")
        if (!manifestFile.isFile) throw PackRefused("${file.name} has no manifest")
        val manifest = readManifest(manifestFile) ?: throw PackRefused("${file.name} has an unreadable manifest")
        val region = manifest.string("region") ?: file.nameWithoutExtension
        val format = manifest["format_version"]?.jsonPrimitive?.intOrNull
        if (format == null || format !in SqlRegionPack.READABLE_FORMATS) throw PackRefused("The $region pack needs a newer DrivePal")
        val intact = manifest["size"]?.jsonPrimitive?.longOrNull == file.length() && manifest.string("sha256") == sha256(file)
        if (!intact) throw PackRefused("The $region pack is damaged")
        val pack = try {
            SqlRegionPack.open(openDatabase(file))
        } catch (unsupported: UnsupportedRegionPack) {
            throw PackRefused("The $region pack needs a newer DrivePal")
        } catch (broken: Exception) {
            throw PackRefused("The $region pack is damaged")
        }
        return pack
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
