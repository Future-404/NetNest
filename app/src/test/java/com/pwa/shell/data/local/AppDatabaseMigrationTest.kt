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

    @Test
    fun `folder migration advances schema from 10 to 11 with root defaults`() {
        assertEquals(10, AppDatabase.MIGRATION_10_11.startVersion)
        assertEquals(11, AppDatabase.MIGRATION_10_11.endVersion)
        assertEquals(null, defaultPwa().folderId)
        assertEquals(0, defaultPwa().folderOrder)
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
