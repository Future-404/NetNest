package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PwaReorderTest {

    @Test
    fun `moves an item across grid positions without changing the source`() {
        val original = listOf("A", "B", "C", "D")

        assertEquals(listOf("B", "C", "A", "D"), moveListItem(original, 0, 2))
        assertEquals(listOf("A", "D", "B", "C"), moveListItem(original, 3, 1))
        assertEquals(listOf("A", "B", "C", "D"), original)
    }

    @Test
    fun `keeps the same list for invalid or unchanged moves`() {
        val original = listOf("A", "B")

        assertSame(original, moveListItem(original, -1, 1))
        assertSame(original, moveListItem(original, 0, 0))
        assertSame(original, moveListItem(original, 0, 2))
    }
}
