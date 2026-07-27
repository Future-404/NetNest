package com.pwa.shell.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePersistenceNoticeTest {

    @Test
    fun `only an authorized first failure displays the notice`() {
        val gate = StoragePersistenceNoticeGate("expected-token")

        assertFalse(gate.shouldNotify("wrong-token"))
        assertTrue(gate.shouldNotify("expected-token"))
        assertFalse(gate.shouldNotify("expected-token"))
    }

    @Test
    fun `wrapper preserves the browser result and only reports false`() {
        val token = "token\";alert(1)//"
        val script = getStoragePersistenceNoticeJs(token)

        assertTrue(script.contains("""const token = "token\";alert(1)//";"""))
        assertTrue(script.contains("if (!persisted) bridge.notifyUnsupported(token);"))
        assertTrue(script.contains("return persisted;"))
        assertTrue(script.contains("window.top !== window"))
        assertFalse(script.contains("return true;"))
    }
}
