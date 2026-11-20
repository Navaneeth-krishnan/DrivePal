package app.drivepal.core

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ChatMessage(val role: String, val content: String)

/**
 * [reasoningEffort] "none" stops thinking models from spending the whole answer budget on hidden
 * reasoning. Set it to null for a provider that rejects the field.
 */
data class ChatSettings(val model: String, val maxTokens: Int = 600, val reasoningEffort: String? = "none")

/** [finishedNormally] is false when the model was cut off (out of tokens) or stopped by a content filter. */
data class ChatReply(val text: String, val finishedNormally: Boolean)

/** Sends a request body to an OpenAI-compatible `/chat/completions` endpoint and returns the response body. The network boundary. */
fun interface ChatTransport {
    fun post(requestJson: String): String
}

/** Talks to any OpenAI-compatible AI server (Ollama, Gemini, Groq, OpenAI and others). */
class ChatClient(private val transport: ChatTransport, private val settings: ChatSettings) {

    /** Returns the model's reply. Throws [IOException] if the request or the answer is unusable. */
    fun complete(messages: List<ChatMessage>): ChatReply = readReply(transport.post(requestJson(messages)))

    private fun requestJson(messages: List<ChatMessage>): String = buildJsonObject {
        put("model", settings.model)
        put("max_tokens", settings.maxTokens)
        put("stream", false)
        settings.reasoningEffort?.let { put("reasoning_effort", it) }
        put("messages", buildJsonArray {
            messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("content", message.content)
                })
            }
        })
    }.toString()

    private fun readReply(responseJson: String): ChatReply {
        val root = Json.parseToJsonElement(responseJson).jsonObject
        root["error"]?.let { error ->
            val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: error.toString()
            throw IOException("AI server error: $message")
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IOException("AI server sent no answer")
        val content = choice["message"]?.jsonObject?.get("content")?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        val reason = choice["finish_reason"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        return ChatReply(text = content.orEmpty(), finishedNormally = reason !in ABNORMAL_FINISHES)
    }

    private companion object {
        val ABNORMAL_FINISHES = setOf("length", "content_filter")
    }
}
