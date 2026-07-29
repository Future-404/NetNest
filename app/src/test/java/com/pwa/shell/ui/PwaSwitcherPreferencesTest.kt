package com.pwa.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PwaSwitcherPreferencesTest {
    @Test
    fun `placement clamps vertical ratio to reachable range`() {
        assertEquals(
            SwitcherPlacement.MIN_VERTICAL_RATIO,
            SwitcherPlacement(verticalRatio = -1f).normalized().verticalRatio
        )
        assertEquals(
            SwitcherPlacement.MAX_VERTICAL_RATIO,
            SwitcherPlacement(verticalRatio = 2f).normalized().verticalRatio
        )
    }

    @Test
    fun `placement clamps handle opacity`() {
        assertEquals(
            SwitcherPlacement.MIN_HANDLE_OPACITY,
            SwitcherPlacement(handleOpacity = -1f).normalized().handleOpacity
        )
        assertEquals(
            SwitcherPlacement.MAX_HANDLE_OPACITY,
            SwitcherPlacement(handleOpacity = 2f).normalized().handleOpacity
        )
    }
}
