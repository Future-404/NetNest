package com.pwa.shell.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPwaValidationTest {

    @Test
    fun `accepts complete http and https urls`() {
        assertTrue(isValidWebUrl("https://example.com/app"))
        assertTrue(isValidWebUrl("http://localhost:8080"))
    }

    @Test
    fun `rejects missing hosts and unsupported schemes`() {
        assertFalse(isValidWebUrl(""))
        assertFalse(isValidWebUrl("example.com"))
        assertFalse(isValidWebUrl("file:///tmp/app.html"))
        assertFalse(isValidWebUrl("javascript:alert(1)"))
    }
}
