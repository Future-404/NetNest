package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebProfilePolicyTest {
    @Test
    fun `shared PWA always remains on the global profile`() {
        val resolved = resolvePwaWebProfile(null, multiProfileSupported = true)

        assertEquals(PwaWebProfileMode.SHARED, resolved.mode)
        assertNull(resolved.profileName)
    }

    @Test
    fun `shared data space does not allocate an isolated profile`() {
        assertNull(webProfileIdFor(PwaDataSpace.SHARED))
    }

    @Test
    fun `isolated data space allocates a unique profile`() {
        val first = webProfileIdFor(PwaDataSpace.ISOLATED)
        val second = webProfileIdFor(PwaDataSpace.ISOLATED)

        assertTrue(requireNotNull(first).startsWith("netnest_pwa_"))
        assertTrue(first.matches(Regex("[a-z0-9_]+")))
        assertTrue(first != second)
    }

    @Test
    fun `new PWA uses its isolated profile when supported`() {
        val resolved = resolvePwaWebProfile("netnest_pwa_test", multiProfileSupported = true)

        assertEquals(PwaWebProfileMode.ISOLATED, resolved.mode)
        assertEquals("netnest_pwa_test", resolved.profileName)
    }

    @Test
    fun `new PWA falls back to shared compatibility profile when unsupported`() {
        val resolved = resolvePwaWebProfile("netnest_pwa_test", multiProfileSupported = false)

        assertEquals(PwaWebProfileMode.COMPATIBILITY_SHARED, resolved.mode)
        assertEquals("netnest_pwa_test", resolved.profileName)
    }

    @Test
    fun `generated profile names are opaque unique and path safe`() {
        val first = newPwaWebProfileId()
        val second = newPwaWebProfileId()

        assertTrue(first.startsWith("netnest_pwa_"))
        assertTrue(first.matches(Regex("[a-z0-9_]+")))
        assertTrue(first != second)
    }
}
