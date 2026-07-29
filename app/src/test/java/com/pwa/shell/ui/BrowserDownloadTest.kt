package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserDownloadTest {

    @Test
    fun `classifies network and web data downloads`() {
        assertEquals(
            BrowserDownloadKind.NETWORK,
            classifyDownloadKind("https://example.com/export")
        )
        assertEquals(
            BrowserDownloadKind.WEB_DATA,
            classifyDownloadKind("blob:https://example.com/id")
        )
        assertEquals(
            BrowserDownloadKind.WEB_DATA,
            classifyDownloadKind("data:application/json;base64,e30=")
        )
    }

    @Test
    fun `rejects unsupported download schemes`() {
        assertNull(classifyDownloadKind("javascript:alert(1)"))
        assertNull(classifyDownloadKind("not-a-url"))
    }

    @Test
    fun `sanitizes path traversal and reserved filename characters`() {
        assertEquals(
            "secret_backup_.json",
            sanitizeDownloadFileName("../../secret:backup?.json")
        )
        assertEquals("download", sanitizeDownloadFileName("..."))
    }

    @Test
    fun `formats known and unknown sizes`() {
        assertEquals("大小未知", formatDownloadSize(-1))
        assertEquals("0 B", formatDownloadSize(0))
        assertEquals("1 KB", formatDownloadSize(1024))
        assertEquals("1.5 MB", formatDownloadSize(1024L * 1024L + 512L * 1024L))
    }

    @Test
    fun `captures and sanitizes the originating profile cookie header`() {
        assertEquals(
            "session=profile-aInjected: value",
            sanitizeHeaderValue("session=profile-a\r\nInjected: value")
        )
    }
}
