package app.drivepal.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Keeps the Told memory in one small JSON file. A missing or damaged file simply means nothing has been told yet. */
class FileToldStore(private val file: File) : ToldStore {

    override fun load(): Map<String, Long> = try {
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.long }
    } catch (e: IOException) {
        emptyMap()
    } catch (e: IllegalArgumentException) {
        // Includes the JSON library's SerializationException: a damaged file.
        emptyMap()
    }

    /** Written to a temporary file first and then moved into place, so a crash cannot leave half a file. */
    override fun save(entries: Map<String, Long>) {
        val json = buildJsonObject { entries.forEach { (key, toldAt) -> put(key, toldAt) } }.toString()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.fd.sync() // on disk before it replaces the old file
            }
            if (!temporary.renameTo(file)) {
                file.delete()
                if (!temporary.renameTo(file)) temporary.delete()
            }
        } catch (e: IOException) {
            temporary.delete() // not being able to remember is a small loss; never stop a drive for it
        }
    }
}
