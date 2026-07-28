package com.pwa.shell.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class AppUpdateCheckerTest {

    @Test
    fun `compares semantic versions numerically`() {
        assertTrue(isNewerVersion("1.0.5", "v1.0.6"))
        assertTrue(isNewerVersion("1.9.9", "1.10.0"))
        assertFalse(isNewerVersion("1.0.5", "1.0.5"))
        assertFalse(isNewerVersion("2.0.0", "1.99.99"))
        assertFalse(isNewerVersion("invalid", "1.0.6"))
    }

    @Test
    fun `parses release and prefers its apk asset`() {
        val update = parseGitHubRelease(
            """
            {
              "tag_name": "v1.2.0",
              "name": "NetNest v1.2.0",
              "body": "Bug fixes",
              "html_url": "https://github.com/Future-404/NetNest/releases/tag/v1.2.0",
              "assets": [{
                "name": "NetNest-v1.2.0-debug.apk",
                "browser_download_url": "https://github.com/Future-404/NetNest/releases/download/v1.2.0/NetNest.apk"
              }]
            }
            """.trimIndent()
        )

        assertEquals("1.2.0", update.versionName)
        assertEquals("Bug fixes", update.releaseNotes)
        assertTrue(update.hasDirectApk)
        assertTrue(update.downloadUrl.endsWith("NetNest.apk"))
    }

    @Test
    fun `falls back to release page when no apk exists`() {
        val update = parseGitHubRelease(
            """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://github.com/Future-404/NetNest/releases/tag/v1.2.0",
              "assets": []
            }
            """.trimIndent()
        )

        assertFalse(update.hasDirectApk)
        assertEquals(update.releasePageUrl, update.downloadUrl)
    }

    @Test
    fun `rejects release links outside github`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseGitHubRelease(
                """
                {
                  "tag_name": "v9.9.9",
                  "html_url": "https://example.com/fake-release",
                  "assets": []
                }
                """.trimIndent()
            )
        }
    }
}
