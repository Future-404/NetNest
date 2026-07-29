package com.pwa.shell.ui

import android.content.Context

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromStoredValue(value: String?): AppThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

internal fun resolveDarkTheme(mode: AppThemeMode, systemDarkTheme: Boolean): Boolean =
    when (mode) {
        AppThemeMode.SYSTEM -> systemDarkTheme
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

internal fun normalizeSettingsTileIndex(index: Int, pwaCount: Int): Int =
    index.coerceIn(0, pwaCount.coerceAtLeast(0))

class GlobalSettingsPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun loadThemeMode(): AppThemeMode =
        AppThemeMode.fromStoredValue(preferences.getString(KEY_THEME_MODE, null))

    fun saveThemeMode(mode: AppThemeMode) {
        preferences.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun loadSettingsTileIndex(): Int =
        preferences.getInt(KEY_SETTINGS_TILE_INDEX, DEFAULT_SETTINGS_TILE_INDEX)

    fun saveSettingsTileIndex(index: Int) {
        preferences.edit()
            .putInt(KEY_SETTINGS_TILE_INDEX, index.coerceAtLeast(0))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "global_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SETTINGS_TILE_INDEX = "settings_tile_index"
        const val DEFAULT_SETTINGS_TILE_INDEX = Int.MAX_VALUE
    }
}
