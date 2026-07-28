package com.pwa.shell.ui

import androidx.compose.ui.geometry.Offset

internal class LongPressDragGate(private val touchSlop: Float) {
    init {
        require(touchSlop.isFinite() && touchSlop > 0f)
    }

    private var accumulatedDrag = Offset.Zero

    var isDragging: Boolean = false
        private set

    fun reset() {
        accumulatedDrag = Offset.Zero
        isDragging = false
    }

    fun track(delta: Offset): Offset? {
        if (isDragging) return delta

        accumulatedDrag += delta
        if (accumulatedDrag.getDistance() < touchSlop) return null

        isDragging = true
        return accumulatedDrag
    }
}
