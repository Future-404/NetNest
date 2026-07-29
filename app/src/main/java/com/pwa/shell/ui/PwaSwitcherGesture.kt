package com.pwa.shell.ui

import kotlin.math.abs

sealed interface PwaSwitcherGestureResult {
    data object Tap : PwaSwitcherGestureResult
    data object Cancelled : PwaSwitcherGestureResult
    data class Switch(val direction: PwaGestureDirection) : PwaSwitcherGestureResult
}

fun classifyPwaSwitcherGesture(
    durationMs: Long,
    deltaX: Float,
    deltaY: Float,
    verticalTriggerPx: Float,
    horizontalCancelPx: Float
): PwaSwitcherGestureResult {
    if (abs(deltaX) >= horizontalCancelPx && abs(deltaX) > abs(deltaY)) {
        return PwaSwitcherGestureResult.Cancelled
    }
    if (
        durationMs >= 220L &&
        abs(deltaY) >= verticalTriggerPx &&
        abs(deltaY) > abs(deltaX)
    ) {
        return PwaSwitcherGestureResult.Switch(
            if (deltaY < 0f) PwaGestureDirection.OLDER else PwaGestureDirection.NEWER
        )
    }
    return PwaSwitcherGestureResult.Tap
}

fun isOutwardCloseGesture(
    side: SwitcherSide,
    deltaX: Float,
    thresholdPx: Float
): Boolean {
    return when (side) {
        SwitcherSide.LEFT -> deltaX <= -thresholdPx
        SwitcherSide.RIGHT -> deltaX >= thresholdPx
    }
}

internal fun switcherPlacementAfterFreeDrag(
    start: SwitcherPlacement,
    deltaX: Float,
    deltaY: Float,
    availableWidthPx: Float,
    availableHeightPx: Float
): SwitcherPlacement {
    val safeWidth = availableWidthPx.coerceAtLeast(1f)
    val startX = if (start.side == SwitcherSide.LEFT) 0f else safeWidth
    val releasedX = (startX + deltaX).coerceIn(0f, safeWidth)
    val side = if (releasedX < safeWidth / 2f) {
        SwitcherSide.LEFT
    } else {
        SwitcherSide.RIGHT
    }
    return start.copy(
        side = side,
        verticalRatio = start.verticalRatio +
            deltaY / availableHeightPx.coerceAtLeast(1f)
    ).normalized()
}
