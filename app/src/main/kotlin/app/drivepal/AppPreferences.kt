package app.drivepal

import android.content.Context
import androidx.core.content.edit
import app.drivepal.core.SpokenLanguage
import app.drivepal.core.Talkativeness

/** The driver's small choices (how talkative, which language), remembered between runs. The AI settings live in [AppStorage]. */
class AppPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("drivepal", Context.MODE_PRIVATE)

    var talkativeness: Talkativeness
        get() = preferences.getString(TALKATIVENESS, null)?.let { saved -> Talkativeness.entries.firstOrNull { it.name == saved } }
            ?: Talkativeness.Normal
        set(value) = preferences.edit { putString(TALKATIVENESS, value.name) }

    /** The language of the Announcements. Read at the start of a Trip. */
    var spokenLanguage: SpokenLanguage
        get() = preferences.getString(SPOKEN_LANGUAGE, null)?.let { saved -> SpokenLanguage.entries.firstOrNull { it.name == saved } }
            ?: SpokenLanguage.English
        set(value) = preferences.edit { putString(SPOKEN_LANGUAGE, value.name) }

    private companion object {
        const val TALKATIVENESS = "talkativeness"
        const val SPOKEN_LANGUAGE = "spokenLanguage"
    }
}
