package com.pwa.shell.ui

import com.pwa.shell.data.local.PwaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPolicyStoreTest {
    private val pwa = PwaEntity(
        name = "Test",
        url = "https://app.example.com",
        iconPath = "",
        themeColor = null,
        displayOrder = 0,
        addedTime = 0L
    )

    @Test
    fun trustDomainIsAvailableImmediately() {
        val store = SecurityPolicyStore(pwa)
        store.trustDomain("api.example.com")
        assertTrue(store.snapshot().trustedDomains.contains("api.example.com"))
    }

    @Test
    fun blockAllChangesLivePolicy() {
        val store = SecurityPolicyStore(pwa)
        store.blockAll()
        assertEquals(2, store.snapshot().securityMode)
    }

    @Test
    fun cachedDecisionIsScopedToHostAndLeakType() {
        val store = SecurityPolicyStore(pwa)
        store.remember("api.example.com", "API 密钥", SecurityDecision.BLOCK_ONCE)
        assertEquals(SecurityDecision.BLOCK_ONCE, store.cached("api.example.com", "API 密钥"))
        assertEquals(null, store.cached("other.example.com", "API 密钥"))
    }
}
