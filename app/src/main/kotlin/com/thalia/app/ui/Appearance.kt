package com.thalia.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Light, dark, or whatever the phone is set to. System is the default. */
enum class ThemeChoice(
    val key: String,
    val label: String,
) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        fun of(key: String?): ThemeChoice = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * The accent colour. Teal is the approved design's own default. Which of teal and Thalia Blue is the product's default is an
 * open owner decision, so both are offered and teal is only the starting point. Success, pending and error colours do not
 * follow the accent.
 */
enum class Accent(
    val key: String,
    val label: String,
    val light: Color,
    val dark: Color,
) {
    TEAL("teal", "Teal", Color(0xFF1F6B68), Color(0xFF5CBDB6)),
    BLUE("blue", "Thalia Blue", Color(0xFF2F5D8A), Color(0xFF8DB4E8)),
    VIOLET("violet", "Violet", Color(0xFF5B4B8A), Color(0xFFB7A8EC)),
    GREEN("green", "Green", Color(0xFF3E6B3A), Color(0xFF8FCB87)),
    ;

    companion object {
        fun of(key: String?): Accent = entries.firstOrNull { it.key == key } ?: TEAL
    }
}

/**
 * The two appearance choices, kept in this app's private preferences. They hold no planning data. The values are Compose state
 * shared by the whole process, so a change made in Settings repaints every screen behind it at once.
 */
class AppearancePrefs internal constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var theme by mutableStateOf(ThemeChoice.of(prefs.getString(THEME, null)))
        private set

    var accent by mutableStateOf(Accent.of(prefs.getString(ACCENT, null)))
        private set

    fun chooseTheme(choice: ThemeChoice) {
        theme = choice
        prefs.edit().putString(THEME, choice.key).apply()
    }

    fun chooseAccent(choice: Accent) {
        accent = choice
        prefs.edit().putString(ACCENT, choice.key).apply()
    }

    companion object {
        private const val FILE = "appearance"
        private const val THEME = "theme"
        private const val ACCENT = "accent"

        @Volatile private var shared: AppearancePrefs? = null

        /** The one instance for this process. */
        fun of(context: Context): AppearancePrefs = shared ?: synchronized(this) { shared ?: AppearancePrefs(context).also { shared = it } }
    }
}
