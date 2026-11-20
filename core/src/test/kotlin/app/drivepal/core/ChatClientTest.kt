package app.drivepal.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatClientTest {

    private val messages = listOf(
        ChatMessage("system", "You are a storyteller."),
        ChatMessage("user", "Tell me about Kinderdijk."),
    )

    @Test
    fun `a real Ollama answer gives the story text and says it finished normally`() {
        val client = ChatClient(transport = { recorded("ollama-story.json") }, settings = ChatSettings(model = "gemma4:e4b"))

        val reply = client.complete(messages)

        assertEquals(
            ChatReply(
                text = "(Leans over, pointing ahead)  Look, we're passing Kinderdijk! Imagine, right here in South Holland, " +
                    "there's this charming village that's packed with history. Back around 1740, people built nineteen " +
                    "monumental windmills here, all used to drain the Alblasserwaard polder. It’s quite a sight, and it was " +
                    "recognized as a UNESCO World Heritage Site back in 1997.",
                finishedNormally = true,
            ),
            reply,
        )
    }

    @Test
    fun `a real Ollama answer where thinking used up every token comes back empty and unfinished`() {
        val client = ChatClient(transport = { recorded("ollama-empty-length.json") }, settings = ChatSettings(model = "gemma4:e4b"))

        assertEquals(ChatReply(text = "", finishedNormally = false), client.complete(messages))
    }

    @Test
    fun `the request names the model and messages and switches thinking off`() {
        var sent: String? = null
        val client = ChatClient(
            transport = { sent = it; recorded("ollama-story.json") },
            settings = ChatSettings(model = "gemma4:e4b", maxTokens = 500, reasoningEffort = "none"),
        )

        client.complete(messages)

        val request = Json.parseToJsonElement(sent!!).jsonObject
        assertEquals("gemma4:e4b", request["model"]!!.jsonPrimitive.content)
        assertEquals("500", request["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("none", request["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("false", request["stream"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("system" to "You are a storyteller.", "user" to "Tell me about Kinderdijk."),
            request["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content to it.jsonObject["content"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `providers that reject reasoning effort are sent a request without it`() {
        var sent: String? = null
        val client = ChatClient(
            transport = { sent = it; recorded("ollama-story.json") },
            settings = ChatSettings(model = "some-model", reasoningEffort = null),
        )

        client.complete(messages)

        assertEquals(null, Json.parseToJsonElement(sent!!).jsonObject["reasoning_effort"])
    }

    @Test
    fun `an error answered with HTTP 200 is a failed request`() {
        val client = ChatClient(
            transport = { """{"error":{"message":"model 'x' not found","type":"not_found_error"}}""" },
            settings = ChatSettings(model = "x"),
        )

        val failure = assertFailsWith<IOException> { client.complete(messages) }

        assertEquals("AI server error: model 'x' not found", failure.message)
    }
}

private fun recorded(name: String): String =
    ChatClientTest::class.java.getResource("/real/$name")!!.readText()
