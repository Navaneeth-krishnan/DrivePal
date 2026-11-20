package app.drivepal.core

/** Picks which installed text-to-speech voice should read a text. */
object VoiceChoice {

    /**
     * A voice for [language] (a Wikipedia language code such as "nl") from [installedVoices] (tags such as "nl-NL"),
     * or an English voice if there is none, or null if not even English is installed.
     */
    fun pick(language: String, installedVoices: Set<String>): String? {
        val sorted = installedVoices.sorted()
        return sorted.firstOrNull { speaks(it, language) } ?: sorted.firstOrNull { speaks(it, "en") }
    }

    // A code with its own hyphen ("zh-min-nan") must match exactly: it is not the language its first part names.
    private fun speaks(voice: String, language: String): Boolean =
        if ('-' in language) voice.equals(language, ignoreCase = true) else voice.substringBefore('-').equals(language, ignoreCase = true)
}
