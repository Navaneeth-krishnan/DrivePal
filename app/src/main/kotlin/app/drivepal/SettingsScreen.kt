package app.drivepal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.drivepal.core.AiProvider
import app.drivepal.core.AiSettings
import app.drivepal.core.ChatClient
import app.drivepal.core.SpokenLanguage
import android.os.Handler
import android.os.Looper
import app.drivepal.core.ChatMessage

/** Which AI writes the Stories, and the two "forget" buttons. Changes apply from the next Trip. */
@Composable
fun SettingsScreen(back: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { AppStorage.of(context) }
    val start = remember { storage.aiConnection() }
    var provider by remember { mutableStateOf(start.settings.provider) }
    var address by remember { mutableStateOf(start.settings.baseUrl) }
    var model by remember { mutableStateOf(start.settings.model) }
    var effort by remember { mutableStateOf(start.settings.reasoningEffort.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun settings() = AiSettings(provider, address, model, effort.ifBlank { null })
    fun keyInUse() = key.ifBlank { null } ?: storage.keys.load(provider)
    fun problems() = settings().problems(keyInUse())

    Column(
        modifier = Modifier.statusBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = back) { Text("Back") }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("AI that writes the Stories", style = MaterialTheme.typography.titleMedium)
        AiProvider.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { choice ->
                    FilterChip(
                        selected = provider == choice,
                        onClick = {
                            provider = choice
                            val preset = AiSettings.preset(choice)
                            address = preset.baseUrl; model = preset.model; effort = preset.reasoningEffort.orEmpty()
                            key = ""; message = null
                        },
                        label = { Text(choice.name) },
                    )
                }
            }
        }
        Text(provider.label)
        OutlinedTextField(address, { address = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            effort, { effort = it }, label = { Text("Reasoning effort (leave empty for Gemini)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (provider.needsKey) {
            val saved = storage.keys.load(provider) != null
            OutlinedTextField(
                key, { key = it },
                label = { Text(if (saved) "API key (saved; type to replace)" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        problems().forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = problems().isEmpty(), onClick = {
                val saved = key.isBlank() || storage.keys.save(provider, key.trim())
                if (saved) {
                    storage.aiSettings.save(settings())
                    key = ""
                    message = "Saved. It applies from the next Trip."
                } else {
                    message = "The key could not be stored on this phone"
                }
            }) { Text("Save") }
            OutlinedButton(enabled = problems().isEmpty(), onClick = {
                message = "Trying…"
                val connection = AiConnection(settings(), keyInUse())
                Thread {
                    val result = tryIt(connection)
                    Handler(Looper.getMainLooper()).post { message = result }
                }.start()
            }) { Text("Try it") }
        }
        message?.let { Text(it) }

        Text("Spoken language", style = MaterialTheme.typography.titleMedium)
        Text("The language place names are announced in, from the next Trip. Stories stay in English.")
        val preferences = remember { AppPreferences(context) }
        var spoken by remember { mutableStateOf(preferences.spokenLanguage) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpokenLanguage.entries.forEach { choice ->
                FilterChip(
                    selected = spoken == choice,
                    onClick = { spoken = choice; preferences.spokenLanguage = choice },
                    label = { Text(choice.name) },
                )
            }
        }

        Text("Memory", style = MaterialTheme.typography.titleMedium)
        Text("DrivePal does not repeat a Story it has told within about 30 days.")
        var confirmForget by remember { mutableStateOf(false) }
        OutlinedButton(onClick = {
            if (confirmForget) {
                storage.told.save(emptyMap())
                confirmForget = false
                message = "Forgotten. Every Story can be told again."
            } else {
                confirmForget = true
            }
        }) { Text(if (confirmForget) "Really forget?" else "Forget what I've heard") }
    }
}

/** Asks the AI server for one short reply and says what came of it. Blocking: call off the main thread. */
private fun tryIt(connection: AiConnection): String = try {
    val reply = ChatClient(AiHttp { connection }, connection.settings.chatSettings())
        .complete(listOf(ChatMessage("user", "Reply with the single word: ready"))).text
    "It answered: ${reply.trim().take(MAX_ECHO)}"
} catch (e: Exception) {
    "Did not work: ${e.message ?: e.javaClass.simpleName}"
}

private const val MAX_ECHO = 80
