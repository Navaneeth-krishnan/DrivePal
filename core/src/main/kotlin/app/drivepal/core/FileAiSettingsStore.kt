package app.drivepal.core

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Keeps the AI settings in one small JSON file. The API key is not in it. */
class FileAiSettingsStore(private val file: File) : AiSettingsStore {

    override fun load(): AiSettings? = try {
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        AiSettings(
            provider = AiProvider.entries.firstOrNull { it.name == json.getValue("provider").jsonPrimitive.content } ?: AiProvider.Custom,
            baseUrl = json.getValue("baseUrl").jsonPrimitive.content,
            model = json.getValue("model").jsonPrimitive.content,
            reasoningEffort = json["reasoningEffort"]?.jsonPrimitive?.contentOrNull,
        )
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        // Includes the JSON library's SerializationException, and a missing field: a damaged file.
        null
    } catch (e: NoSuchElementException) {
        null
    }

    override fun save(settings: AiSettings) = writeAtomically(
        file,
        buildJsonObject {
            put("provider", settings.provider.name)
            put("baseUrl", settings.baseUrl)
            put("model", settings.model)
            settings.reasoningEffort?.let { put("reasoningEffort", it) }
        }.toString(),
    )
}
