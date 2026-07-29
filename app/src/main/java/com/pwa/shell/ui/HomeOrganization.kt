package com.pwa.shell.ui

import com.pwa.shell.data.local.PwaEntity
import com.pwa.shell.data.local.PwaFolderEntity

sealed interface HomeOrderEntry {
    val id: Long

    data class Pwa(override val id: Long) : HomeOrderEntry
    data class Folder(override val id: Long) : HomeOrderEntry
}

internal fun persistentHomeOrder(
    pwas: List<PwaEntity>,
    folders: List<PwaFolderEntity>
): List<HomeOrderEntry> {
    return buildList {
        pwas.filter { it.folderId == null }.forEach {
            add(Triple(it.displayOrder, it.addedTime, HomeOrderEntry.Pwa(it.id)))
        }
        folders.forEach {
            add(Triple(it.displayOrder, it.addedTime, HomeOrderEntry.Folder(it.id)))
        }
    }.sortedWith(
        compareBy<Triple<Int, Long, HomeOrderEntry>> { it.first }
            .thenBy { it.second }
            .thenBy { it.third.id }
    ).map { it.third }
}

internal fun normalizedFolderName(rawName: String): String? {
    val normalized = rawName.trim().replace(Regex("\\s+"), " ")
    return normalized.takeIf { it.isNotEmpty() && it.length <= 30 }
}

internal enum class PwaTargetDragAction {
    WAIT_FOR_CENTER,
    GROUP,
    REORDER
}

internal enum class PwaDropTargetKind {
    NONE,
    PWA,
    FOLDER
}

internal fun pwaTargetDragAction(
    targetKind: PwaDropTargetKind,
    isCentered: Boolean,
    wasCenteredOnSameTarget: Boolean
): PwaTargetDragAction {
    if (targetKind == PwaDropTargetKind.NONE) return PwaTargetDragAction.REORDER
    if (targetKind == PwaDropTargetKind.FOLDER) return PwaTargetDragAction.GROUP
    if (isCentered) return PwaTargetDragAction.GROUP
    return if (wasCenteredOnSameTarget) {
        PwaTargetDragAction.REORDER
    } else {
        PwaTargetDragAction.WAIT_FOR_CENTER
    }
}
