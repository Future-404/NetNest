package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSettingsPreferencesTest {
    @Test
    fun `unknown stored theme falls back to system`() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStoredValue("UNKNOWN"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStoredValue(null))
    }

    @Test
    fun `theme mode resolves against system only when requested`() {
        assertTrue(resolveDarkTheme(AppThemeMode.SYSTEM, systemDarkTheme = true))
        assertFalse(resolveDarkTheme(AppThemeMode.SYSTEM, systemDarkTheme = false))
        assertFalse(resolveDarkTheme(AppThemeMode.LIGHT, systemDarkTheme = true))
        assertTrue(resolveDarkTheme(AppThemeMode.DARK, systemDarkTheme = false))
    }

    @Test
    fun `settings tile index is kept inside the current grid`() {
        assertEquals(0, normalizeSettingsTileIndex(-4, pwaCount = 3))
        assertEquals(2, normalizeSettingsTileIndex(2, pwaCount = 3))
        assertEquals(4, normalizeSettingsTileIndex(Int.MAX_VALUE, pwaCount = 3))
    }

    @Test
    fun `both system tiles can occupy the final grid position`() {
        assertEquals(1, normalizeSystemTileIndex(Int.MAX_VALUE, pwaCount = 0))
        assertEquals(4, normalizeSystemTileIndex(Int.MAX_VALUE, pwaCount = 3))
    }

    @Test
    fun `lazy grid keys map back to the full home order`() {
        val orderedKeys = listOf<Any>(10L, "netnest_system_settings", 20L)

        assertEquals(0, stableKeyTargetIndex(orderedKeys, 10L))
        assertEquals(2, stableKeyTargetIndex(orderedKeys, 20L))
        assertEquals(null, stableKeyTargetIndex(orderedKeys, "header"))
        assertEquals(null, stableKeyTargetIndex(orderedKeys, null))
    }
}
