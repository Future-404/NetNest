package com.pwa.shell.data.remote

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdateInfo(
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val hasDirectApk: Boolean
)

class AppUpdateChecker(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String): AppUpdateInfo? {
        val now = System.currentTimeMillis()
        val cached = readCached()
        if (now - preferences.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) {
            return cached?.takeIf { shouldShow(it, currentVersion, now) }
        }

        preferences.edit().putLong(KEY_LAST_CHECK, now).apply()
        val latest = fetchLatestRelease().getOrNull() ?: cached
        latest?.let(::saveCached)
        return latest?.takeIf { shouldShow(it, currentVersion, now) }
    }

    /**
     * User-initiated checks always contact GitHub and ignore the automatic
     * reminder's interval and snooze state.
     */
    suspend fun checkNow(currentVersion: String): Result<AppUpdateInfo?> {
        return fetchLatestRelease().map { latest ->
            preferences.edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply()
            saveCached(latest)
            latest.takeIf { isNewerVersion(currentVersion, it.versionName) }
        }
    }

    fun snooze(versionName: String) {
        preferences.edit()
            .putString(KEY_SNOOZED_VERSION, versionName)
            .putLong(KEY_SNOOZED_UNTIL, System.currentTimeMillis() + CHECK_INTERVAL_MS)
            .apply()
    }

    private fun shouldShow(
        update: AppUpdateInfo,
        currentVersion: String,
        now: Long
    ): Boolean {
        if (!isNewerVersion(currentVersion, update.versionName)) return false
        return preferences.getString(KEY_SNOOZED_VERSION, null) != update.versionName ||
            preferences.getLong(KEY_SNOOZED_UNTIL, 0L) <= now
    }

    private suspend fun fetchLatestRelease(): Result<AppUpdateInfo> =
        withContext(Dispatchers.IO) {
            var lastError: Throwable = IOException("无法检查更新")
            repeat(MAX_ATTEMPTS) { attempt ->
                try {
                    val request = Request.Builder()
                        .url(LATEST_RELEASE_URL)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "NetNest-Android")
                        .build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (response.isSuccessful) {
                            return@withContext runCatching { parseGitHubRelease(body) }
                        }
                        lastError = IOException("GitHub 返回 HTTP ${response.code}")
                        if (response.code !in RETRYABLE_STATUS_CODES) {
                            return@withContext Result.failure(lastError)
                        }
                    }
                } catch (error: IOException) {
                    lastError = error
                }
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
            Result.failure(lastError)
        }

    private fun saveCached(update: AppUpdateInfo) {
        preferences.edit()
            .putString(KEY_VERSION, update.versionName)
            .putString(KEY_TITLE, update.title)
            .putString(KEY_NOTES, update.releaseNotes)
            .putString(KEY_DOWNLOAD_URL, update.downloadUrl)
            .putString(KEY_RELEASE_URL, update.releasePageUrl)
            .putBoolean(KEY_DIRECT_APK, update.hasDirectApk)
            .apply()
    }

    private fun readCached(): AppUpdateInfo? {
        val version = preferences.getString(KEY_VERSION, null) ?: return null
        val downloadUrl = preferences.getString(KEY_DOWNLOAD_URL, null) ?: return null
        val releaseUrl = preferences.getString(KEY_RELEASE_URL, null) ?: return null
        return AppUpdateInfo(
            versionName = version,
            title = preferences.getString(KEY_TITLE, null).orEmpty(),
            releaseNotes = preferences.getString(KEY_NOTES, null).orEmpty(),
            downloadUrl = downloadUrl,
            releasePageUrl = releaseUrl,
            hasDirectApk = preferences.getBoolean(KEY_DIRECT_APK, false)
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "app_update_checker"
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Future-404/NetNest/releases/latest"
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 400L
        val RETRYABLE_STATUS_CODES = setOf(408, 500, 502, 503, 504)
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_VERSION = "version"
        const val KEY_TITLE = "title"
        const val KEY_NOTES = "notes"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_RELEASE_URL = "release_url"
        const val KEY_DIRECT_APK = "direct_apk"
        const val KEY_SNOOZED_VERSION = "snoozed_version"
        const val KEY_SNOOZED_UNTIL = "snoozed_until"
    }
}

private val releaseJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val assets: List<GitHubReleaseAsset> = emptyList()
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String
)

internal fun parseGitHubRelease(json: String): AppUpdateInfo {
    val release = releaseJson.decodeFromString<GitHubRelease>(json)
    require(release.tagName.isNotBlank()) { "发行版本号为空" }
    require(release.htmlUrl.startsWith("https://github.com/")) { "发行页面地址无效" }
    val apk = release.assets.firstOrNull {
        it.name.endsWith(".apk", ignoreCase = true) &&
            it.browserDownloadUrl.startsWith("https://github.com/")
    }
    return AppUpdateInfo(
        versionName = release.tagName.removePrefix("v").removePrefix("V"),
        title = release.name?.takeIf(String::isNotBlank) ?: release.tagName,
        releaseNotes = release.body.orEmpty().trim(),
        downloadUrl = apk?.browserDownloadUrl ?: release.htmlUrl,
        releasePageUrl = release.htmlUrl,
        hasDirectApk = apk != null
    )
}

internal fun isNewerVersion(currentVersion: String, candidateVersion: String): Boolean {
    fun parse(value: String): List<Int>? {
        val match = Regex("""^[vV]?(\d+(?:\.\d+)*)(?:[-+].*)?$""")
            .matchEntire(value.trim()) ?: return null
        return match.groupValues[1].split(".").map { it.toIntOrNull() ?: return null }
    }

    val current = parse(currentVersion) ?: return false
    val candidate = parse(candidateVersion) ?: return false
    repeat(maxOf(current.size, candidate.size)) { index ->
        val currentPart = current.getOrElse(index) { 0 }
        val candidatePart = candidate.getOrElse(index) { 0 }
        if (candidatePart != currentPart) return candidatePart > currentPart
    }
    return false
}
