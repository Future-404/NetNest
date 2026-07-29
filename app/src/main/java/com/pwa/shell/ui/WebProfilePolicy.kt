package com.pwa.shell.ui

import java.util.UUID

internal enum class PwaWebProfileMode {
    SHARED,
    ISOLATED,
    COMPATIBILITY_SHARED
}

enum class PwaDataSpace {
    SHARED,
    ISOLATED
}

internal data class ResolvedPwaWebProfile(
    val mode: PwaWebProfileMode,
    val profileName: String? = null
)

internal fun newPwaWebProfileId(): String =
    "netnest_pwa_${UUID.randomUUID().toString().replace("-", "")}"

internal fun webProfileIdFor(dataSpace: PwaDataSpace): String? =
    when (dataSpace) {
        PwaDataSpace.SHARED -> null
        PwaDataSpace.ISOLATED -> newPwaWebProfileId()
    }

internal fun resolvePwaWebProfile(
    profileName: String?,
    multiProfileSupported: Boolean
): ResolvedPwaWebProfile {
    if (profileName.isNullOrBlank()) {
        return ResolvedPwaWebProfile(PwaWebProfileMode.SHARED)
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
