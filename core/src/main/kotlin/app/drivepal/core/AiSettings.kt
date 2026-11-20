package app.drivepal.core

import java.net.URI

/** The AI servers DrivePal knows how to talk to. All of them speak the OpenAI-compatible chat protocol. */
enum class AiProvider(val label: String, val needsKey: Boolean) {
    Ollama("Ollama (on a computer of yours)", needsKey = false),
    Gemini("Google Gemini", needsKey = true),
    OpenAi("OpenAI", needsKey = true),
    Groq("Groq", needsKey = true),
    OpenRouter("OpenRouter", needsKey = true),
    Custom("Another OpenAI-compatible server", needsKey = true),
}

/**
 * Which AI writes the Stories: the provider, its address, the model and the reasoning effort to ask for (null or blank
 * leaves the field out, which providers such as Gemini require). The API key is deliberately not part of this: it is
 * kept apart, encrypted, and never written to the settings file.
 */
data class AiSettings(
    val provider: AiProvider,
    val baseUrl: String,
    val model: String,
    val reasoningEffort: String?,
) {
    fun chatSettings() = ChatSettings(model = model.trim(), reasoningEffort = reasoningEffort?.trim()?.takeIf { it.isNotEmpty() })

    /** The address chat requests are sent to, however the base address was typed. */
    fun chatUrl(): String = baseUrl.trim().trimEnd('/') + "/chat/completions"

    /**
     * What is wrong with these settings, in words the driver can act on; empty if they can be used. [apiKey] is the key
     * kept for this provider, if any.
     */
    fun problems(apiKey: String?): List<String> {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host
        if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty()) {
            return listOf("The address must be a web address, starting with https://")
        }
        val onLocalNetwork = LocalAddress.isLocal(host)
        return buildList {
            if (scheme == "http" && !onLocalNetwork) {
                add("Use https: a key and your Stories would travel over plain http to a public address")
            }
            if (model.isBlank()) add("Choose a model")
            if (provider.needsKey && !onLocalNetwork && apiKey.isNullOrBlank()) add("${provider.label} needs an API key")
        }
    }

    companion object {
        /** The starting values for [provider]. Models are only filled in where one has been tried. */
        fun preset(provider: AiProvider): AiSettings = when (provider) {
            // localhost is the computer itself on the emulator; for a phone, give the computer's address instead.
            AiProvider.Ollama -> AiSettings(provider, "http://localhost:11434/v1", "gemma4:e4b", reasoningEffort = "none")
            AiProvider.Gemini -> AiSettings(provider, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.5-flash-lite", null)
            AiProvider.OpenAi -> AiSettings(provider, "https://api.openai.com/v1", "", null)
            AiProvider.Groq -> AiSettings(provider, "https://api.groq.com/openai/v1", "", null)
            AiProvider.OpenRouter -> AiSettings(provider, "https://openrouter.ai/api/v1", "", null)
            AiProvider.Custom -> AiSettings(provider, "", "", null)
        }
    }
}

/** Where the AI settings are kept, apart from the key. */
interface AiSettingsStore {
    /** The saved settings, or null if there are none (or they are damaged), in which case the defaults apply. */
    fun load(): AiSettings?

    fun save(settings: AiSettings)
}

/** Where the API keys are kept, one per provider. The real implementation encrypts them. */
interface ApiKeyStore {
    fun load(provider: AiProvider): String?

    /** True if the key was stored. Failing must be visible: a key that silently was not saved is worse than an error. */
    fun save(provider: AiProvider, key: String): Boolean

    fun forget(provider: AiProvider)
}
