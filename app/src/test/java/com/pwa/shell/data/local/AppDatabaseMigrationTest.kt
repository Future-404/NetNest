package com.pwa.shell.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDatabaseMigrationTest {
    @Test
    fun `switcher visibility migration advances schema from 9 to 10`() {
        assertEquals(9, AppDatabase.MIGRATION_9_10.startVersion)
        assertEquals(10, AppDatabase.MIGRATION_9_10.endVersion)
        assertEquals(true, defaultPwa().showSwitcherHandle)
    }

    private fun defaultPwa() = PwaEntity(
        name = "Example",
        url = "https://example.com",
        iconPath = "",
        themeColor = null,
        displayOrder = 0,
        addedTime = 1L
    )
}
