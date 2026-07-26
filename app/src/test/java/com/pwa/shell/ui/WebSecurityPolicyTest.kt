package com.pwa.shell.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSecurityPolicyTest {
    @Test
    fun allowsPwaOriginAndSubdomains() {
        assertTrue(
            WebSecurityPolicy.isTrustedUrl(
                "https://app.example.com/path",
                "https://app.example.com",
                ""
            )
        )
        assertTrue(
            WebSecurityPolicy.isTrustedUrl(
                "https://api.example.com/v1",
                "https://app.example.com",
                "example.com"
            )
        )
    }

    @Test
    fun rejectsSuffixLookalikes() {
        assertFalse(
            WebSecurityPolicy.isTrustedUrl(
                "https://evil-example.com",
                "https://app.example.com",
                "example.com"
            )
        )
        assertFalse(
            WebSecurityPolicy.isTrustedUrl(
                "https://example.com.evil.test",
                "https://app.example.com",
                "example.com"
            )
        )
    }
}
