package com.pwa.shell.ui

import java.net.IDN
import java.net.URI

internal object WebSecurityPolicy {
    fun isTrustedUrl(requestUrl: String, pwaUrl: String, trustedDomains: String): Boolean {
        val requestHost = extractHost(requestUrl) ?: return false
        val pwaHost = extractHost(pwaUrl)
        if (requestHost == pwaHost) return true

        return trustedDomains
            .split(',')
            .asSequence()
            .mapNotNull(::normalizeHost)
            .any { trustedHost ->
                requestHost == trustedHost || requestHost.endsWith(".$trustedHost")
            }
    }

    private fun extractHost(url: String): String? {
        return runCatching { URI(url).host }
            .getOrNull()
            ?.let(::normalizeHost)
    }

    private fun normalizeHost(host: String): String? {
        val normalized = host.trim().trimEnd('.')
        if (normalized.isEmpty()) return null

        return runCatching { IDN.toASCII(normalized).lowercase() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }
}
