package com.pwa.shell.ui

import android.content.Context

enum class SwitcherSide {
    LEFT,
    RIGHT
}

data class SwitcherPlacement(
    val side: SwitcherSide = SwitcherSide.RIGHT,
    val verticalRatio: Float = DEFAULT_VERTICAL_RATIO
) {
    fun normalized(): SwitcherPlacement = copy(
        verticalRatio = verticalRatio.coerceIn(MIN_VERTICAL_RATIO, MAX_VERTICAL_RATIO)
    )

    companion object {
        const val DEFAULT_VERTICAL_RATIO = 0.62f
        const val MIN_VERTICAL_RATIO = 0.20f
        const val MAX_VERTICAL_RATIO = 0.80f
    }
}

class PwaSwitcherPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun loadPlacement(): SwitcherPlacement {
        val side = runCatching {
            SwitcherSide.valueOf(
                preferences.getString(KEY_SIDE, SwitcherSide.RIGHT.name)
                    ?: SwitcherSide.RIGHT.name
            )
        }.getOrDefault(SwitcherSide.RIGHT)
        return SwitcherPlacement(
            side = side,
            verticalRatio = preferences.getFloat(
                KEY_VERTICAL_RATIO,
                SwitcherPlacement.DEFAULT_VERTICAL_RATIO
            )
        ).normalized()
    }

    fun savePlacement(placement: SwitcherPlacement) {
        val normalized = placement.normalized()
        preferences.edit()
            .putString(KEY_SIDE, normalized.side.name)
            .putFloat(KEY_VERTICAL_RATIO, normalized.verticalRatio)
            .apply()
    }

    fun loadRecentPwaIds(): List<Long> {
        return preferences.getString(KEY_RECENT_PWA_IDS, null)
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            ?.filter { it > 0L }
            ?.distinct()
            ?.take(PwaSessionManager.MAX_RECENT_PWAS)
            .orEmpty()
    }

    fun saveRecentPwaIds(ids: List<Long>) {
        preferences.edit()
            .putString(
                KEY_RECENT_PWA_IDS,
                ids.asSequence()
                    .filter { it > 0L }
                    .distinct()
                    .take(PwaSessionManager.MAX_RECENT_PWAS)
                    .joinToString(",")
            )
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "pwa_switcher"
        private const val KEY_SIDE = "side"
        private const val KEY_VERTICAL_RATIO = "vertical_ratio"
        private const val KEY_RECENT_PWA_IDS = "recent_pwa_ids"
    }
}
