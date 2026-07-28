package com.pwa.shell.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressDragGateTest {

    @Test
    fun `keeps touch jitter pending below the drag threshold`() {
        val gate = LongPressDragGate(touchSlop = 8f)

        assertNull(gate.track(Offset(2f, 1f)))
        assertNull(gate.track(Offset(-1f, 2f)))
        assertFalse(gate.isDragging)
    }

    @Test
    fun `starts dragging only after cumulative displacement reaches the threshold`() {
        val gate = LongPressDragGate(touchSlop = 5f)

        assertNull(gate.track(Offset(3f, 0f)))
        assertEquals(Offset(4f, 3f), gate.track(Offset(1f, 3f)))
        assertTrue(gate.isDragging)
        assertEquals(Offset(2f, -1f), gate.track(Offset(2f, -1f)))
    }

    @Test
    fun `reset restores the pending state for the next gesture`() {
        val gate = LongPressDragGate(touchSlop = 5f)
        gate.track(Offset(5f, 0f))

        gate.reset()

        assertFalse(gate.isDragging)
        assertNull(gate.track(Offset(1f, 1f)))
    }
}
