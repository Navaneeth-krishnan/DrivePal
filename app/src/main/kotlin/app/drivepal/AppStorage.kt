package app.drivepal

import android.content.Context
import app.drivepal.core.AiProvider
import app.drivepal.core.AiSettings
import app.drivepal.core.FileAiSettingsStore
import app.drivepal.core.FileToldStore
import app.drivepal.core.FileTripLogStore
import java.io.File

/** The AI server to talk to right now: the settings, and the API key for that provider (if any). */
class AiConnection(val settings: AiSettings, val apiKey: String?)

/**
 * Where DrivePal keeps what it remembers, all in the app's private storage: the AI settings, the encrypted API keys,
 * the Told memory and the Trip log. The build-time AI values (from the Gradle properties) are only the defaults, used
 * until the driver chooses something in Settings.
 */
class AppStorage(context: Context) {
    private val app = context.applicationContext

    val aiSettings = FileAiSettingsStore(File(app.filesDir, "ai.json"))
    val keys = KeystoreApiKeyStore(app)
    val told = FileToldStore(File(app.filesDir, "told.json"))
    val tripLog = FileTripLogStore(File(app.filesDir, "triplog.json"))

    companion object {
        @Volatile private var instance: AppStorage? = null

        fun of(context: Context): AppStorage =
            instance ?: synchronized(this) { instance ?: AppStorage(context).also { instance = it } }
    }

    /** The saved settings, or the build-time defaults; with the saved key, or else the build-time key. */
    fun aiConnection(): AiConnection {
        val settings = aiSettings.load() ?: buildDefaults()
        val key = keys.load(settings.provider) ?: BuildConfig.AI_API_KEY.takeIf { it.isNotBlank() }
        return AiConnection(settings, key)
    }

    fun buildDefaults(): AiSettings {
        val url = BuildConfig.AI_BASE_URL
        val provider = AiProvider.entries.firstOrNull { it != AiProvider.Custom && AiSettings.preset(it).baseUrl.trimEnd('/') == url.trimEnd('/') }
            ?: AiProvider.Custom
        return AiSettings(provider, url, BuildConfig.AI_MODEL, BuildConfig.AI_REASONING_EFFORT.ifBlank { null })
    }
}
