package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PwaShortcutTest {

    @Test
    fun `shortcut id is stable and isolated per pwa`() {
        assertEquals("pwa_42", pwaShortcutId(42))
        assertEquals("pwa_43", pwaShortcutId(43))
    }

    @Test
    fun `shortcut labels are non-empty and launcher sized`() {
        assertEquals("PWA", pwaShortcutShortLabel("   "))
        assertEquals("1234567890", pwaShortcutShortLabel("1234567890extra"))
        assertEquals("我的应用", pwaShortcutShortLabel("  我的应用  "))
    }

    @Test
    fun `adaptive shortcut icons start at Android 8`() {
        assertFalse(supportsAdaptiveShortcutIcon(25))
        assertTrue(supportsAdaptiveShortcutIcon(26))
        assertTrue(supportsAdaptiveShortcutIcon(34))
    }
}
