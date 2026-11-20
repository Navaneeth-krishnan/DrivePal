package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiSettingsTest {

    @Test
    fun `the Gemini preset points at Google's OpenAI-compatible address, with the tried model, and sends no reasoning effort`() {
        val gemini = AiSettings.preset(AiProvider.Gemini)

        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", gemini.baseUrl)
        assertEquals("gemini-3.5-flash-lite", gemini.model)
        assertEquals(null, gemini.chatSettings().reasoningEffort) // Gemini rejects the field
    }

    @Test
    fun `the Ollama preset needs no key and switches thinking off, which fixed empty answers`() {
        val ollama = AiSettings.preset(AiProvider.Ollama)

        assertEquals("gemma4:e4b", ollama.model)
        assertEquals("none", ollama.chatSettings().reasoningEffort)
        assertEquals(emptyList(), ollama.problems(apiKey = null))
    }

    @Test
    fun `a cloud provider without a key is rejected, with a reason that says what to do`() {
        val problems = AiSettings.preset(AiProvider.Gemini).problems(apiKey = "")

        assertEquals(listOf("Google Gemini needs an API key"), problems)
    }

    @Test
    fun `a key is never allowed to travel over plain http to a public address`() {
        val settings = AiSettings.preset(AiProvider.Custom).copy(baseUrl = "http://api.example.com/v1", model = "m")

        assertTrue(settings.problems(apiKey = "secret").any { "plain http" in it })
    }

    @Test
    fun `plain http to a computer on the local network is fine, and needs no key`() {
        val laptop = AiSettings.preset(AiProvider.Custom).copy(baseUrl = "http://192.168.1.20:11434/v1", model = "gemma4:e4b")

        assertEquals(emptyList(), laptop.problems(apiKey = null))
    }

    @Test
    fun `a model must be chosen`() {
        val openAi = AiSettings.preset(AiProvider.OpenAi)

        assertEquals("", openAi.model)
        assertEquals(listOf("Choose a model"), openAi.problems(apiKey = "secret"))
    }

    @Test
    fun `an address that is not a web address is rejected`() {
        val settings = AiSettings.preset(AiProvider.Custom).copy(baseUrl = "not a url", model = "m")

        assertTrue(settings.problems(apiKey = "secret").any { "web address" in it })
    }

    @Test
    fun `the chat address is the base address plus the completions path, however it was typed`() {
        val typed = AiSettings.preset(AiProvider.Ollama).copy(baseUrl = "  http://localhost:11434/v1/  ")

        assertEquals("http://localhost:11434/v1/chat/completions", typed.chatUrl())
    }

    @Test
    fun `a blank reasoning effort is left out of the request`() {
        assertEquals(null, AiSettings.preset(AiProvider.Ollama).copy(reasoningEffort = "  ").chatSettings().reasoningEffort)
    }

    @Test
    fun `every provider has a name to show`() {
        assertTrue(AiProvider.entries.all { it.label.isNotBlank() })
    }
}
