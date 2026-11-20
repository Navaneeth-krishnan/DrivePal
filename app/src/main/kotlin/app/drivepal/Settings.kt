package app.drivepal

import android.content.Context
import androidx.core.content.edit
import app.drivepal.core.Talkativeness

/** The driver's choices, remembered between runs. */
class Settings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("drivepal", Context.MODE_PRIVATE)

    var talkativeness: Talkativeness
        get() = preferences.getString(TALKATIVENESS, null)?.let { saved -> Talkativeness.entries.firstOrNull { it.name == saved } }
            ?: Talkativeness.Normal
        set(value) = preferences.edit { putString(TALKATIVENESS, value.name) }

    private companion object {
        const val TALKATIVENESS = "talkativeness"
    }
}
