package com.pwa.shell.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URL

@Serializable
data class PwaFetchResult(
    val name: String,
    val url: String,
    val iconUrl: String?,
    val themeColor: String?
)

@Serializable
private data class PwaManifest(
    val name: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    val icons: List<PwaManifestIcon>? = null,
    @SerialName("theme_color") val themeColor: String? = null
)

@Serializable
private data class PwaManifestIcon(
    val src: String,
    val sizes: String? = null,
    val type: String? = null,
    val purpose: String? = null
)

class PwaManifestFetcher(private val okHttpClient: OkHttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun fetchPwaInfo(pageUrl: String): PwaFetchResult = withContext(Dispatchers.IO) {
        val absolutePageUrl = cleanUrl(pageUrl)
        val html = fetchHtml(absolutePageUrl)
        val document = Jsoup.parse(html, absolutePageUrl)

        // Try manifest first
        val manifestUrl = document.select("link[rel=manifest]").firstOrNull()?.absUrl("href")
        if (!manifestUrl.isNullOrEmpty()) {
            try {
                val manifestJson = fetchString(manifestUrl)
                val manifest = json.decodeFromString<PwaManifest>(manifestJson)
                val name = manifest.shortName ?: manifest.name ?: document.title() ?: "PWA"
                val themeColor = manifest.themeColor

                // Parse icons
                val iconUrl = selectBestManifestIcon(manifest.icons, manifestUrl)
                    ?: selectFallbackIcon(document, absolutePageUrl)

                return@withContext PwaFetchResult(
                    name = name,
                    url = absolutePageUrl,
                    iconUrl = iconUrl,
                    themeColor = themeColor
                )
            } catch (e: Exception) {
                // Fallback if manifest fetch/parse fails
            }
        }

        // Fallback: Parse icons from HTML or default favicon
        val name = document.title().takeIf { it.isNotEmpty() } ?: "PWA"
        val iconUrl = selectFallbackIcon(document, absolutePageUrl)
        PwaFetchResult(
            name = name,
            url = absolutePageUrl,
            iconUrl = iconUrl,
            themeColor = null
        )
    }

    private fun cleanUrl(url: String): String {
        return if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }
    }

    private fun fetchHtml(url: String): String {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Failed to fetch HTML: ${response.code}")
            return response.body?.string() ?: ""
        }
    }

    private fun fetchString(url: String): String {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Failed to fetch manifest: ${response.code}")
            return response.body?.string() ?: ""
        }
    }

    private fun selectBestManifestIcon(icons: List<PwaManifestIcon>?, manifestUrl: String): String? {
        if (icons.isNullOrEmpty()) return null

        // Sort icons by parsed size descending
        val sortedIcons = icons.sortedWith(compareByDescending<PwaManifestIcon> {
            it.purpose?.contains("any") == true
        }.thenByDescending {
            getIconArea(it.sizes)
        }.thenByDescending {
            it.type == "image/png"
        })

        val bestIcon = sortedIcons.first()
        return resolveUrl(manifestUrl, bestIcon.src)
    }

    private fun selectFallbackIcon(document: org.jsoup.nodes.Document, baseUrl: String): String {
        // 1. apple-touch-icon
        val appleIcon = document.select("link[rel=apple-touch-icon]").firstOrNull()?.absUrl("href")
        if (!appleIcon.isNullOrEmpty()) return appleIcon

        // 2. shortcut icon / icon
        val iconElement = document.select("link[rel~=(?i)^(shortcut )?icon]").sortedByDescending {
            getIconArea(it.attr("sizes"))
        }.firstOrNull()
        val iconUrl = iconElement?.absUrl("href")
        if (!iconUrl.isNullOrEmpty()) return iconUrl

        // 3. /favicon.ico at root
        return try {
            val urlObj = URL(baseUrl)
            URL(urlObj.protocol, urlObj.host, urlObj.port, "/favicon.ico").toString()
        } catch (e: Exception) {
            resolveUrl(baseUrl, "favicon.ico")
        }
    }

    private fun getIconArea(sizesStr: String?): Int {
        if (sizesStr.isNullOrEmpty()) return 0
        if (sizesStr.equals("any", ignoreCase = true)) return 512 * 512

        // e.g. "192x192 512x512" or "192x192"
        val firstSize = sizesStr.split(" ").firstOrNull() ?: return 0
        val parts = firstSize.split("x", "X")
        if (parts.size == 2) {
            try {
                val w = parts[0].trim().toInt()
                val h = parts[1].trim().toInt()
                return w * h
            } catch (e: NumberFormatException) {
                // ignore
            }
        }
        return 0
    }

    private fun resolveUrl(baseUrl: String, relativePath: String): String {
        return try {
            URL(URL(baseUrl), relativePath).toString()
        } catch (e: Exception) {
            relativePath
        }
    }
}
