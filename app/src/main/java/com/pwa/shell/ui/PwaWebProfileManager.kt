package com.pwa.shell.ui

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

internal object PwaWebProfileManager {
    fun isMultiProfileSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }.getOrDefault(false)

    fun resolve(profileName: String?): ResolvedPwaWebProfile =
        resolvePwaWebProfile(profileName, isMultiProfileSupported())

    /**
     * Must be the first operation performed on a newly-created WebView.
     */
    fun attach(webView: WebView, profileName: String?): ResolvedPwaWebProfile {
        val resolved = resolve(profileName)
        if (resolved.mode == PwaWebProfileMode.ISOLATED) {
            try {
                WebViewCompat.setProfile(webView, requireNotNull(resolved.profileName))
            } catch (_: UnsupportedOperationException) {
                return ResolvedPwaWebProfile(
                    mode = PwaWebProfileMode.COMPATIBILITY_SHARED,
                    profileName = resolved.profileName
                )
            }
        }
        return resolved
    }

    fun cookieManager(
        webView: WebView,
        profile: ResolvedPwaWebProfile
    ): CookieManager =
        if (profile.mode == PwaWebProfileMode.ISOLATED) {
            WebViewCompat.getProfile(webView).cookieManager
        } else {
            CookieManager.getInstance()
        }

    /**
     * Returns null when profile deletion cannot be attempted on this WebView provider.
     * A true/false result means ProfileStore accepted the request or no profile existed.
     */
    fun deleteProfile(profileName: String): Boolean? {
        if (!isMultiProfileSupported()) return null
        return ProfileStore.getInstance().deleteProfile(profileName)
    }
}
