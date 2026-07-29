package com.pwa.shell.ui

import java.util.UUID

internal enum class PwaWebProfileMode {
    LEGACY_SHARED,
    ISOLATED,
    COMPATIBILITY_SHARED
}

internal data class ResolvedPwaWebProfile(
    val mode: PwaWebProfileMode,
    val profileName: String? = null
)

internal fun newPwaWebProfileId(): String =
    "netnest_pwa_${UUID.randomUUID().toString().replace("-", "")}"

internal fun resolvePwaWebProfile(
    profileName: String?,
    multiProfileSupported: Boolean
): ResolvedPwaWebProfile {
    if (profileName.isNullOrBlank()) {
        return ResolvedPwaWebProfile(PwaWebProfileMode.LEGACY_SHARED)
    }
    return if (multiProfileSupported) {
        ResolvedPwaWebProfile(PwaWebProfileMode.ISOLATED, profileName)
    } else {
        ResolvedPwaWebProfile(
            mode = PwaWebProfileMode.COMPATIBILITY_SHARED,
            profileName = profileName
        )
    }
}
