package app.drivepal.core

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Keeps the Trip log in one JSON file. A missing or damaged file is an empty log; one bad story is skipped, not the whole log. */
class FileTripLogStore(private val file: File) : TripLogStore {

    override fun load(): List<LoggedTrip> = try {
        Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { runCatching { trip(it.jsonObject) }.getOrNull() }
    } catch (e: IOException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        // Includes the JSON library's SerializationException: a damaged file.
        emptyList()
    }

    override fun save(trips: List<LoggedTrip>) = writeAtomically(file, buildJsonArray { trips.forEach { add(json(it)) } }.toString())

    private fun trip(json: JsonObject) = LoggedTrip(
        startedMillis = json.getValue("startedMillis").jsonPrimitive.long,
        endedMillis = json["endedMillis"]?.jsonPrimitive?.long,
        stories = json.getValue("stories").jsonArray.mapNotNull { runCatching { story(it.jsonObject) }.getOrNull() },
    )

    private fun story(json: JsonObject) = LoggedStory(
        title = json.getValue("title").jsonPrimitive.content,
        kind = StoryKind.valueOf(json.getValue("kind").jsonPrimitive.content),
        text = json.getValue("text").jsonPrimitive.content,
        language = json.getValue("language").jsonPrimitive.content,
        outcome = StoryOutcome.valueOf(json.getValue("outcome").jsonPrimitive.content),
        sourceLanguage = json["sourceLanguage"]?.jsonPrimitive?.contentOrNull,
        sourceTitle = json["sourceTitle"]?.jsonPrimitive?.contentOrNull,
        atMillis = json.getValue("atMillis").jsonPrimitive.long,
    )

    private fun json(trip: LoggedTrip): JsonObject = buildJsonObject {
        put("startedMillis", trip.startedMillis)
        trip.endedMillis?.let { put("endedMillis", it) }
        put("stories", JsonArray(trip.stories.map { json(it) }))
    }

    private fun json(story: LoggedStory): JsonObject = buildJsonObject {
        put("title", story.title)
        put("kind", story.kind.name)
        put("text", story.text)
        put("language", story.language)
        put("outcome", story.outcome.name)
        story.sourceLanguage?.let { put("sourceLanguage", it) }
        story.sourceTitle?.let { put("sourceTitle", it) }
        put("atMillis", story.atMillis)
    }
}
