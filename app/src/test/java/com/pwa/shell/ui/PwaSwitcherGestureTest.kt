package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PwaSwitcherGestureTest {
    @Test
    fun `short press remains a tap`() {
        assertEquals(
            PwaSwitcherGestureResult.Tap,
            classifyPwaSwitcherGesture(100L, 0f, -60f, 48f, 24f)
        )
    }

    @Test
    fun `held vertical movement maps up to older and down to newer`() {
        assertEquals(
            PwaSwitcherGestureResult.Switch(PwaGestureDirection.OLDER),
            classifyPwaSwitcherGesture(220L, 2f, -48f, 48f, 24f)
        )
        assertEquals(
            PwaSwitcherGestureResult.Switch(PwaGestureDirection.NEWER),
            classifyPwaSwitcherGesture(300L, 2f, 60f, 48f, 24f)
        )
    }

    @Test
    fun `horizontal movement cancels handle gesture`() {
        assertEquals(
            PwaSwitcherGestureResult.Cancelled,
            classifyPwaSwitcherGesture(300L, 30f, 4f, 48f, 24f)
        )
    }

    @Test
    fun `outward close follows drawer side`() {
        assertTrue(isOutwardCloseGesture(SwitcherSide.LEFT, -40f, 40f))
        assertTrue(isOutwardCloseGesture(SwitcherSide.RIGHT, 40f, 40f))
        assertFalse(isOutwardCloseGesture(SwitcherSide.LEFT, 40f, 40f))
        assertFalse(isOutwardCloseGesture(SwitcherSide.RIGHT, -40f, 40f))
    }
}
