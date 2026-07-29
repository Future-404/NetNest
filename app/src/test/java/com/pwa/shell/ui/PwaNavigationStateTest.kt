package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PwaNavigationStateTest {
    @Test
    fun `navigation state cache removes the oldest entry when full`() {
        val states = linkedMapOf(1L to "one", 2L to "two")

        putBoundedNavigationState(states, 3L, "three", maxStates = 2)

        assertEquals(linkedMapOf(2L to "two", 3L to "three"), states)
    }

    @Test
    fun `updating navigation state refreshes its recency`() {
        val states = linkedMapOf(1L to "old", 2L to "two")

        putBoundedNavigationState(states, 1L, "new", maxStates = 2)
        putBoundedNavigationState(states, 3L, "three", maxStates = 2)

        assertEquals(linkedMapOf(1L to "new", 3L to "three"), states)
    }
}
