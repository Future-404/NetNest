package com.pwa.shell.ui

import com.pwa.shell.data.local.PwaEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PwaSessionInvalidationTest {
    private val pwa = PwaEntity(
        id = 1L,
        name = "Example",
        url = "https://example.com",
        iconPath = "",
        themeColor = null,
        displayOrder = 0,
        addedTime = 1L
    )

    @Test
    fun `metadata and switcher changes keep warm session`() {
        assertFalse(
            requiresWebSessionRestart(
                pwa,
                pwa.copy(name = "Renamed", iconPath = "/icon.png", showSwitcherHandle = false)
            )
        )
    }

    @Test
    fun `browser identity change invalidates warm session`() {
        assertTrue(
            requiresWebSessionRestart(
                pwa,
                pwa.copy(customUserAgent = "Custom")
            )
        )
    }
}
