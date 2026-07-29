package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PwaSessionManagerTest {
    @Test
    fun `fifth activation evicts least recently used warm session`() {
        val manager = PwaSessionManager()
        (1L..4L).forEach { manager.activate(it, PwaActivationSource.HOME, it * 100L) }

        val evicted = manager.activate(5L, PwaActivationSource.HOME, 500L)

        assertEquals(setOf(1L), evicted)
        assertEquals(listOf(2L, 3L, 4L, 5L), manager.snapshot().liveSessions.map { it.pwaId })
        assertEquals(5L, manager.snapshot().activePwaId)
    }

    @Test
    fun `gesture sequence is frozen and wraps in both directions`() {
        val manager = PwaSessionManager()
        (1L..4L).forEach { manager.activate(it, PwaActivationSource.HOME, it) }

        assertEquals(listOf(4L, 3L, 2L, 1L), manager.beginGesture())
        assertEquals(3L, manager.gestureTarget(PwaGestureDirection.OLDER))
        manager.activate(3L, PwaActivationSource.GESTURE, 10L)
        assertEquals(2L, manager.gestureTarget(PwaGestureDirection.OLDER))
        assertEquals(3L, manager.gestureTarget(PwaGestureDirection.NEWER))
    }

    @Test
    fun `non gesture activation rebuilds carousel`() {
        val manager = PwaSessionManager()
        manager.activate(1L, PwaActivationSource.HOME, 1L)
        manager.activate(2L, PwaActivationSource.HOME, 2L)
        manager.beginGesture()
        manager.activate(1L, PwaActivationSource.DRAWER, 3L)

        assertTrue(manager.snapshot().gestureSequence.isEmpty())
        assertEquals(listOf(1L, 2L), manager.beginGesture())
    }

    @Test
    fun `idle and background cleanup retain current session`() {
        val manager = PwaSessionManager()
        manager.activate(1L, PwaActivationSource.HOME, 0L)
        manager.activate(2L, PwaActivationSource.HOME, 100L)

        assertEquals(
            setOf(1L),
            manager.evictIdleWarmSessions(
                100L + PwaSessionManager.FOREGROUND_IDLE_TIMEOUT_MS
            )
        )
        manager.activate(3L, PwaActivationSource.HOME, 200L)
        manager.onAppBackgrounded(1_000L)
        assertTrue(manager.onAppForegrounded(120_999L).isEmpty())
        manager.onAppBackgrounded(2_000L)
        assertEquals(setOf(2L), manager.onAppForegrounded(122_000L))
        assertEquals(3L, manager.snapshot().activePwaId)
    }

    @Test
    fun `active time is not counted as warm idle time`() {
        val manager = PwaSessionManager()
        manager.activate(1L, PwaActivationSource.HOME, 0L)
        manager.goHome(PwaSessionManager.FOREGROUND_IDLE_TIMEOUT_MS)

        assertTrue(
            manager.evictIdleWarmSessions(
                PwaSessionManager.FOREGROUND_IDLE_TIMEOUT_MS + 1L
            ).isEmpty()
        )
        assertEquals(
            setOf(1L),
            manager.evictIdleWarmSessions(
                PwaSessionManager.FOREGROUND_IDLE_TIMEOUT_MS * 2L
            )
        )
    }

    @Test
    fun `capacity eviction preserves sessions waiting for attention when warm exists`() {
        val manager = PwaSessionManager()
        (1L..4L).forEach { manager.activate(it, PwaActivationSource.HOME, it * 100L) }
        manager.markAttention(1L, true)

        val evicted = manager.activate(5L, PwaActivationSource.HOME, 500L)

        assertEquals(setOf(2L), evicted)
        assertTrue(
            manager.snapshot().liveSessions.any {
                it.pwaId == 1L && it.phase == PwaSessionPhase.ATTENTION
            }
        )
    }

    @Test
    fun `memory pressure removes every non current session`() {
        val manager = PwaSessionManager()
        (1L..3L).forEach { manager.activate(it, PwaActivationSource.HOME, it) }

        assertEquals(setOf(1L, 2L), manager.onMemoryPressure())
        assertEquals(listOf(3L), manager.snapshot().liveSessions.map { it.pwaId })
    }

    @Test
    fun `pending close can be undone or finalized without deleting pwa data`() {
        val manager = PwaSessionManager()
        manager.activate(1L, PwaActivationSource.HOME, 1L)
        manager.activate(2L, PwaActivationSource.HOME, 2L)

        assertTrue(manager.beginPendingClose(1L))
        assertFalse(1L in manager.snapshot().recentPwaIds)
        assertTrue(manager.undoPendingClose(1L))
        assertTrue(1L in manager.snapshot().recentPwaIds)
        assertTrue(manager.beginPendingClose(1L))
        assertTrue(manager.finalizePendingClose(1L))
        assertFalse(manager.snapshot().liveSessions.any { it.pwaId == 1L })
    }

    @Test
    fun `reconcile removes stale ids and clears deleted active session`() {
        val manager = PwaSessionManager(listOf(9L, 1L))
        manager.activate(1L, PwaActivationSource.HOME, 1L)

        assertEquals(setOf(1L), manager.reconcile(setOf(2L)))
        assertNull(manager.snapshot().activePwaId)
        assertTrue(manager.snapshot().recentPwaIds.isEmpty())
    }
}
