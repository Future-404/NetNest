package com.pwa.shell.ui

import com.pwa.shell.data.local.PwaEntity

/**
 * Values applied only while creating/configuring a WebView require a cold rebuild.
 * Metadata, security policy and switcher visibility are observed without losing
 * the current page.
 */
fun requiresWebSessionRestart(previous: PwaEntity, updated: PwaEntity): Boolean {
    return previous.url != updated.url ||
        previous.useChromeUa != updated.useChromeUa ||
        previous.useDevConsole != updated.useDevConsole ||
        previous.customUserAgent != updated.customUserAgent ||
        previous.customLanguage != updated.customLanguage ||
        previous.customPlatform != updated.customPlatform ||
        previous.screenWidth != updated.screenWidth ||
        previous.screenHeight != updated.screenHeight ||
        previous.deviceScaleFactor != updated.deviceScaleFactor ||
        previous.webProfileId != updated.webProfileId
}
