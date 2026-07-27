package com.pwa.shell.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.pwa.shell.MainActivity
import com.pwa.shell.data.local.PwaEntity

internal enum class PinPwaShortcutResult {
    REQUESTED,
    ALREADY_PINNED,
    UNSUPPORTED,
    FAILED
}

internal fun pwaShortcutId(pwaId: Long): String = "pwa_$pwaId"

internal fun pwaShortcutShortLabel(name: String): String =
    name.trim().ifEmpty { "PWA" }.take(10)

internal suspend fun requestPinnedPwaShortcut(
    context: Context,
    pwa: PwaEntity
): PinPwaShortcutResult = runCatching {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        return@runCatching PinPwaShortcutResult.UNSUPPORTED
    }
    if (isPwaShortcutPinned(context, pwa.id)) {
        updatePinnedPwaShortcut(context, pwa)
        return@runCatching PinPwaShortcutResult.ALREADY_PINNED
    }
    val shortcut = buildPwaShortcut(context, pwa)
    if (ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) {
        PinPwaShortcutResult.REQUESTED
    } else {
        PinPwaShortcutResult.FAILED
    }
}.getOrDefault(PinPwaShortcutResult.FAILED)

internal suspend fun updatePinnedPwaShortcut(context: Context, pwa: PwaEntity) {
    if (!isPwaShortcutPinned(context, pwa.id)) return
    ShortcutManagerCompat.updateShortcuts(context, listOf(buildPwaShortcut(context, pwa)))
}

internal fun disablePinnedPwaShortcut(context: Context, pwaId: Long) {
    if (!isPwaShortcutPinned(context, pwaId)) return
    ShortcutManagerCompat.disableShortcuts(
        context,
        listOf(pwaShortcutId(pwaId)),
        "该网页应用已被删除"
    )
}

internal fun isPwaShortcutPinned(context: Context, pwaId: Long): Boolean =
    runCatching {
        ShortcutManagerCompat.getShortcuts(
            context,
            ShortcutManagerCompat.FLAG_MATCH_PINNED
        ).any { it.id == pwaShortcutId(pwaId) }
    }.getOrDefault(false)

private suspend fun buildPwaShortcut(
    context: Context,
    pwa: PwaEntity
): ShortcutInfoCompat {
    val icon = PwaIconManager.shortcutBitmap(context, pwa.iconPath, pwa.name)
    val intent = Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_OPEN_PWA_SHORTCUT
        putExtra(MainActivity.EXTRA_PWA_ID, pwa.id)
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return ShortcutInfoCompat.Builder(context, pwaShortcutId(pwa.id))
        .setShortLabel(pwaShortcutShortLabel(pwa.name))
        .setLongLabel(pwa.name.trim().ifEmpty { "PWA" }.take(25))
        .setIcon(IconCompat.createWithBitmap(icon))
        .setIntent(intent)
        .build()
}
