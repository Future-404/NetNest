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
        assertEquals(3, normalizeSettingsTileIndex(Int.MAX_VALUE, pwaCount = 3))
    }
}
