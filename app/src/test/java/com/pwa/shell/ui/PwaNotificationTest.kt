package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PwaNotificationTest {

    @Test
    fun `normalizes origins and default ports`() {
        assertEquals(
            "https://example.com",
            notificationOrigin("HTTPS://Example.COM:443/path?value=1")
        )
        assertEquals(
            "https://example.com:8443",
            notificationOrigin("https://example.com:8443/path")
        )
        assertEquals(
            "http://[::1]:8080",
            notificationOrigin("http://[::1]:8080/path")
        )
        assertEquals(
            "https://xn--r8jz45g.xn--zckzah",
            notificationOrigin("https://例え.テスト/path")
        )
        assertNull(notificationOrigin("javascript:alert(1)"))
    }

    @Test
    fun `allows only the configured secure origin`() {
        assertTrue(
            isNotificationSourceAllowed(
                "https://app.example.com/messages",
                "https://app.example.com/"
            )
        )
        assertFalse(
            isNotificationSourceAllowed(
                "https://child.app.example.com/",
                "https://app.example.com/"
            )
        )
        assertFalse(
            isNotificationSourceAllowed(
                "http://app.example.com/",
                "http://app.example.com/"
            )
        )
        assertTrue(
            isNotificationSourceAllowed(
                "http://localhost:8080/messages",
                "http://localhost:8080/"
            )
        )
        assertTrue(
            isNotificationSourceAllowed(
                "http://[::1]:8080/messages",
                "http://[::1]:8080/"
            )
        )
    }

    @Test
    fun `builds a single exact document origin rule`() {
        assertEquals(
            setOf("https://app.example.com"),
            notificationAllowedOriginRules("https://app.example.com/path")
        )
        assertTrue(notificationAllowedOriginRules("http://app.example.com").isEmpty())
    }

    @Test
    fun `sanitizes notification text and enforces length`() {
        assertEquals(
            "hello\nworld",
            sanitizeNotificationText(" hello\u0000\nworld ", 20)
        )
        assertEquals("abc", sanitizeNotificationText("abcdef", 3))
    }

    @Test
    fun `limits bursts but permits a new minute window`() {
        var now = 0L
        val limiter = NotificationRateLimiter(clock = { now })

        repeat(5) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())

        now = 60_000L
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `enforces the hourly cap independently`() {
        var now = 0L
        val limiter = NotificationRateLimiter(
            clock = { now },
            maxPerMinute = 10,
            maxPerHour = 2
        )

        assertTrue(limiter.tryAcquire())
        now = 60_000L
        assertTrue(limiter.tryAcquire())
        now = 120_000L
        assertFalse(limiter.tryAcquire())
        now = 3_600_000L
        assertTrue(limiter.tryAcquire())
    }
}
