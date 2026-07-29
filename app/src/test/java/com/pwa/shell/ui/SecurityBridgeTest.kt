package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityBridgeTest {
    @Test
    fun `expired prompt is dismissed and request stays blocked`() {
        var shownCallback: ((SecurityDecision) -> Unit)? = null
        var expiredCallback: ((SecurityDecision) -> Unit)? = null
        val result = awaitSecurityDecision(
            timeoutMillis = 1L,
            onShow = { callback -> shownCallback = callback },
            onExpired = { callback -> expiredCallback = callback }
        )

        assertEquals(SecurityDecision.BLOCK_ONCE, result)
        assertTrue(shownCallback != null)
        assertSame(shownCallback, expiredCallback)
    }
}
