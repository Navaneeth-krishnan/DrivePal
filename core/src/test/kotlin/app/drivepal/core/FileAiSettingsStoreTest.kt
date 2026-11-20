package app.drivepal.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileAiSettingsStoreTest {

    private fun newFile(): File = Files.createTempDirectory("drivepal").resolve("ai.json").toFile()

    @Test
    fun `a provider, address, model and reasoning effort survive a restart`() {
        val file = newFile()
        val chosen = AiSettings(AiProvider.Groq, "https://api.groq.com/openai/v1", "some-model", reasoningEffort = "low")

        FileAiSettingsStore(file).save(chosen)

        assertEquals(chosen, FileAiSettingsStore(file).load())
    }

    @Test
    fun `no reasoning effort stays no reasoning effort`() {
        val file = newFile()
        val gemini = AiSettings.preset(AiProvider.Gemini)

        FileAiSettingsStore(file).save(gemini)

        assertNull(FileAiSettingsStore(file).load()!!.reasoningEffort)
    }

    @Test
    fun `before anything was saved there are no settings, so the defaults apply`() {
        assertNull(FileAiSettingsStore(newFile()).load())
    }

    @Test
    fun `a damaged file means the defaults apply instead of stopping the app`() {
        assertNull(FileAiSettingsStore(newFile().also { it.writeText("{ nope") }).load())
    }

    @Test
    fun `a provider this version does not know is treated as another OpenAI-compatible server`() {
        val file = newFile()
        FileAiSettingsStore(file).save(AiSettings(AiProvider.Groq, "https://x.example/v1", "m", null))
        file.writeText(file.readText().replace("\"Groq\"", "\"SomethingFuture\""))

        assertEquals(AiProvider.Custom, FileAiSettingsStore(file).load()!!.provider)
    }

    @Test
    fun `the API key is never written to the settings file`() {
        val file = newFile()
        FileAiSettingsStore(file).save(AiSettings.preset(AiProvider.Gemini))

        assertEquals(false, file.readText().contains("key", ignoreCase = true))
    }
}
