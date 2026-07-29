package com.pwa.shell.ui

import com.pwa.shell.data.local.PwaEntity
import com.pwa.shell.data.local.PwaFolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeOrganizationTest {
    @Test
    fun `home order merges folders and root apps while hiding folder members`() {
        val folder = PwaFolderEntity(
            id = 8L,
            name = "工作",
            displayOrder = 1,
            addedTime = 20L
        )
        val pwas = listOf(
            pwa(id = 1L, displayOrder = 0, addedTime = 10L),
            pwa(id = 2L, displayOrder = 2, addedTime = 30L),
            pwa(
                id = 3L,
                displayOrder = 0,
                addedTime = 5L,
                folderId = folder.id
            )
        )

        assertEquals(
            listOf(
                HomeOrderEntry.Pwa(1L),
                HomeOrderEntry.Folder(8L),
                HomeOrderEntry.Pwa(2L)
            ),
            persistentHomeOrder(pwas, listOf(folder))
        )
    }

    @Test
    fun `rebuilt home source replaces two grouped root apps with their folder`() {
        val folder = PwaFolderEntity(
            id = 8L,
            name = "工作",
            displayOrder = 0,
            addedTime = 20L
        )
        val groupedPwas = listOf(
            pwa(id = 1L, displayOrder = 0, addedTime = 10L, folderId = folder.id),
            pwa(id = 2L, displayOrder = 1, addedTime = 11L, folderId = folder.id)
        )

        assertEquals(
            listOf(HomeOrderEntry.Folder(folder.id)),
            persistentHomeOrder(groupedPwas, listOf(folder))
        )
    }

    @Test
    fun `folder name is trimmed collapsed and bounded`() {
        assertEquals("工作 工具", normalizedFolderName("  工作   工具  "))
        assertNull(normalizedFolderName("   "))
        assertNull(normalizedFolderName("a".repeat(31)))
    }

    @Test
    fun `drag waits at target edge so reaching center can create a folder`() {
        assertEquals(
            PwaTargetDragAction.WAIT_FOR_CENTER,
            pwaTargetDragAction(
                targetKind = PwaDropTargetKind.PWA,
                isCentered = false,
                wasCenteredOnSameTarget = false
            )
        )
        assertEquals(
            PwaTargetDragAction.GROUP,
            pwaTargetDragAction(
                targetKind = PwaDropTargetKind.PWA,
                isCentered = true,
                wasCenteredOnSameTarget = false
            )
        )
    }

    @Test
    fun `drag reorders after crossing through a target center`() {
        assertEquals(
            PwaTargetDragAction.REORDER,
            pwaTargetDragAction(
                targetKind = PwaDropTargetKind.PWA,
                isCentered = false,
                wasCenteredOnSameTarget = true
            )
        )
    }

    @Test
    fun `existing folder is a magnetic drop target across its whole grid cell`() {
        assertEquals(
            PwaTargetDragAction.GROUP,
            pwaTargetDragAction(
                targetKind = PwaDropTargetKind.FOLDER,
                isCentered = false,
                wasCenteredOnSameTarget = false
            )
        )
    }

    private fun pwa(
        id: Long,
        displayOrder: Int,
        addedTime: Long,
        folderId: Long? = null
    ) = PwaEntity(
        id = id,
        name = "PWA $id",
        url = "https://example.com/$id",
        iconPath = "",
        themeColor = null,
        displayOrder = displayOrder,
        addedTime = addedTime,
        folderId = folderId
    )
}
