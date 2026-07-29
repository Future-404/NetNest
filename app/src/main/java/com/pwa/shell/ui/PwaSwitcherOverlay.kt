package com.pwa.shell.ui

import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pwa.shell.data.local.PwaEntity
import java.io.File
import kotlin.math.roundToInt

@Composable
fun PwaSwitcherOverlay(
    currentPwa: PwaEntity,
    placement: SwitcherPlacement,
    drawerOpen: Boolean,
    drawerPwas: List<PwaEntity>,
    livePwaIds: Set<Long>,
    attentionPwaIds: Set<Long>,
    onDrawerOpenChange: (Boolean) -> Unit,
    onPlacementChange: (SwitcherPlacement) -> Unit,
    onPlacementChangeFinished: (SwitcherPlacement) -> Unit,
    onGestureStart: () -> List<Long>,
    onGestureSwitch: (PwaGestureDirection) -> Unit,
    onPwaSelected: (PwaEntity) -> Unit,
    onHomeSelected: () -> Unit,
    onCloseWarmPwa: (PwaEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val availableHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        if (drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.12f))
                    .clickable { onDrawerOpenChange(false) }
            )
            SwitcherDrawer(
                placement = placement,
                pwas = drawerPwas,
                livePwaIds = livePwaIds,
                attentionPwaIds = attentionPwaIds,
                availableHeightPx = availableHeightPx,
                onPlacementChange = onPlacementChange,
                onPlacementChangeFinished = onPlacementChangeFinished,
                onPwaSelected = onPwaSelected,
                onHomeSelected = onHomeSelected,
                onCloseWarmPwa = onCloseWarmPwa,
                modifier = Modifier.align(
                    if (placement.side == SwitcherSide.LEFT) {
                        Alignment.CenterStart
                    } else {
                        Alignment.CenterEnd
                    }
                )
            )
            val previewHeight = 72.dp
            val previewOffset = (maxHeight - previewHeight) * placement.verticalRatio
            SwitcherPositionPreview(
                side = placement.side,
                opacity = placement.handleOpacity,
                modifier = Modifier
                    .align(
                        if (placement.side == SwitcherSide.LEFT) {
                            Alignment.TopStart
                        } else {
                            Alignment.TopEnd
                        }
                    )
                    .offset(y = previewOffset)
            )
        } else {
            val handleHeight = 72.dp
            val yOffset = (maxHeight - handleHeight) * placement.verticalRatio
            SwitcherHandle(
                currentPwa = currentPwa,
                side = placement.side,
                opacity = placement.handleOpacity,
                onTap = { onDrawerOpenChange(true) },
                onGestureStart = onGestureStart,
                onGestureSwitch = onGestureSwitch,
                modifier = Modifier
                    .align(
                        if (placement.side == SwitcherSide.LEFT) {
                            Alignment.TopStart
                        } else {
                            Alignment.TopEnd
                        }
                    )
                    .offset(y = yOffset)
            )
        }
    }
}

@Composable
private fun SwitcherHandle(
    currentPwa: PwaEntity,
    side: SwitcherSide,
    opacity: Float,
    onTap: () -> Unit,
    onGestureStart: () -> List<Long>,
    onGestureSwitch: (PwaGestureDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val rootView = LocalView.current
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val visualAlpha = if (pressed) maxOf(opacity, 0.55f) else opacity

    DisposableEffect(Unit) {
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                rootView.systemGestureExclusionRects = emptyList()
            }
        }
    }

    Box(
        modifier = modifier
            .size(width = 28.dp, height = 72.dp)
            .onGloballyPositioned { coordinates ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val bounds = coordinates.boundsInRoot()
                    rootView.systemGestureExclusionRects = listOf(
                        Rect(
                            bounds.left.roundToInt(),
                            bounds.top.roundToInt(),
                            bounds.right.roundToInt(),
                            bounds.bottom.roundToInt()
                        )
                    )
                }
            }
            .semantics {
                contentDescription = "${currentPwa.name} 应用切换侧边条"
                customActions = listOf(
                    CustomAccessibilityAction("打开最近应用") {
                        onTap()
                        true
                    },
                    CustomAccessibilityAction("切换到上一个应用") {
                        if (onGestureStart().size > 1) {
                            onGestureSwitch(PwaGestureDirection.OLDER)
                        }
                        true
                    },
                    CustomAccessibilityAction("切换到下一个应用") {
                        if (onGestureStart().size > 1) {
                            onGestureSwitch(PwaGestureDirection.NEWER)
                        }
                        true
                    }
                )
            }
            .pointerInput(side, currentPwa.id) {
                val triggerDistance = with(density) { 48.dp.toPx() }
                val horizontalCancelDistance = with(density) { 24.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    var direction: PwaGestureDirection? = null
                    var cancelled = false
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val offset = change.position - down.position
                        val duration = change.uptimeMillis - down.uptimeMillis
                        when (
                            val result = classifyPwaSwitcherGesture(
                                durationMs = duration,
                                deltaX = offset.x,
                                deltaY = offset.y,
                                verticalTriggerPx = triggerDistance,
                                horizontalCancelPx = horizontalCancelDistance
                            )
                        ) {
                            PwaSwitcherGestureResult.Cancelled -> cancelled = true
                            is PwaSwitcherGestureResult.Switch -> {
                                if (
                                    !cancelled &&
                                    direction != result.direction &&
                                    onGestureStart().size > 1
                                ) {
                                    direction = result.direction
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.LongPress
                                    )
                                }
                            }
                            PwaSwitcherGestureResult.Tap -> Unit
                        }
                        if (direction != null) {
                            change.consume()
                        }
                        released = !change.pressed
                    }
                    pressed = false
                    when {
                        direction != null && !cancelled -> onGestureSwitch(direction)
                        !cancelled -> onTap()
                    }
                }
            },
        contentAlignment = if (side == SwitcherSide.LEFT) {
            Alignment.CenterStart
        } else {
            Alignment.CenterEnd
        }
    ) {
        Surface(
            modifier = Modifier
                .width(6.dp)
                .height(56.dp)
                .alpha(visualAlpha),
            shape = if (side == SwitcherSide.LEFT) {
                RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
            } else {
                RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
            },
            color = MaterialTheme.colorScheme.onSurface
        ) {}
    }
}

@Composable
private fun SwitcherDrawer(
    placement: SwitcherPlacement,
    pwas: List<PwaEntity>,
    livePwaIds: Set<Long>,
    attentionPwaIds: Set<Long>,
    availableHeightPx: Float,
    onPlacementChange: (SwitcherPlacement) -> Unit,
    onPlacementChangeFinished: (SwitcherPlacement) -> Unit,
    onPwaSelected: (PwaEntity) -> Unit,
    onHomeSelected: () -> Unit,
    onCloseWarmPwa: (PwaEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var opacityDraft by remember { mutableFloatStateOf(placement.handleOpacity) }

    Surface(
        modifier = modifier
            .width(116.dp)
            .padding(vertical = 12.dp),
        shape = if (placement.side == SwitcherSide.LEFT) {
            RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
        } else {
            RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
        },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SwitcherPositionDragControl(
                placement = placement,
                availableHeightPx = availableHeightPx,
                onPlacementChange = onPlacementChange,
                onPlacementChangeFinished = onPlacementChangeFinished
            )
            Text(
                text = "透明度 ${(placement.handleOpacity * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = opacityDraft,
                onValueChange = {
                    opacityDraft = it
                    onPlacementChange(placement.copy(handleOpacity = it).normalized())
                },
                onValueChangeFinished = {
                    onPlacementChangeFinished(
                        placement.copy(handleOpacity = opacityDraft).normalized()
                    )
                },
                valueRange = SwitcherPlacement.MIN_HANDLE_OPACITY..
                    SwitcherPlacement.MAX_HANDLE_OPACITY,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "侧边条透明度"
                    }
            )
            HorizontalDivider()
            repeat(4) { index ->
                val pwa = pwas.getOrNull(index)
                if (pwa == null) {
                    Spacer(modifier = Modifier.size(62.dp))
                } else {
                    SwitcherDrawerItem(
                        pwa = pwa,
                        side = placement.side,
                        isLive = pwa.id in livePwaIds,
                        hasAttention = pwa.id in attentionPwaIds,
                        onClick = { onPwaSelected(pwa) },
                        onClose = { onCloseWarmPwa(pwa) }
                    )
                }
            }
            HorizontalDivider()
            IconButton(
                onClick = onHomeSelected,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(Icons.Default.Home, contentDescription = "返回首页")
            }
        }
    }
}

@Composable
private fun SwitcherPositionDragControl(
    placement: SwitcherPlacement,
    availableHeightPx: Float,
    onPlacementChange: (SwitcherPlacement) -> Unit,
    onPlacementChangeFinished: (SwitcherPlacement) -> Unit
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val latestPlacement by rememberUpdatedState(placement)
    val latestOnPlacementChange by rememberUpdatedState(onPlacementChange)
    val latestOnPlacementChangeFinished by rememberUpdatedState(onPlacementChangeFinished)
    val horizontalThresholdPx = with(density) { 48.dp.toPx() }

    fun commit(updated: SwitcherPlacement) {
        val normalized = updated.normalized()
        latestOnPlacementChange(normalized)
        latestOnPlacementChangeFinished(normalized)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .semantics {
                contentDescription = "拖动调整侧边条位置"
                customActions = listOf(
                    CustomAccessibilityAction("向上移动") {
                        commit(
                            latestPlacement.copy(
                                verticalRatio = latestPlacement.verticalRatio - 0.05f
                            )
                        )
                        true
                    },
                    CustomAccessibilityAction("向下移动") {
                        commit(
                            latestPlacement.copy(
                                verticalRatio = latestPlacement.verticalRatio + 0.05f
                            )
                        )
                        true
                    },
                    CustomAccessibilityAction("移到左侧") {
                        commit(latestPlacement.copy(side = SwitcherSide.LEFT))
                        true
                    },
                    CustomAccessibilityAction("移到右侧") {
                        commit(latestPlacement.copy(side = SwitcherSide.RIGHT))
                        true
                    }
                )
            }
            .pointerInput(availableHeightPx) {
                var startPlacement = placement
                var totalDrag = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        startPlacement = latestPlacement
                        totalDrag = Offset.Zero
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, dragAmount ->
                        totalDrag += dragAmount
                        val draftPlacement = switcherPlacementAfterDrag(
                            start = startPlacement,
                            deltaX = totalDrag.x,
                            deltaY = totalDrag.y,
                            availableHeightPx = availableHeightPx,
                            horizontalThresholdPx = horizontalThresholdPx,
                            applyHorizontalSide = false
                        )
                        latestOnPlacementChange(draftPlacement)
                        change.consume()
                    },
                    onDragEnd = {
                        val finalPlacement = switcherPlacementAfterDrag(
                            start = startPlacement,
                            deltaX = totalDrag.x,
                            deltaY = totalDrag.y,
                            availableHeightPx = availableHeightPx,
                            horizontalThresholdPx = horizontalThresholdPx,
                            applyHorizontalSide = true
                        )
                        latestOnPlacementChange(finalPlacement)
                        latestOnPlacementChangeFinished(finalPlacement)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragCancel = {
                        latestOnPlacementChange(startPlacement)
                    }
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(3.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        RoundedCornerShape(2.dp)
                    )
            )
            Text(
                text = "拖动调整位置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = "上下移动 · 左右换边",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SwitcherPositionPreview(
    side: SwitcherSide,
    opacity: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(6.dp)
            .height(56.dp)
            .alpha(opacity),
        shape = if (side == SwitcherSide.LEFT) {
            RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
        } else {
            RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
        },
        color = MaterialTheme.colorScheme.onSurface
    ) {}
}

@Composable
private fun SwitcherDrawerItem(
    pwa: PwaEntity,
    side: SwitcherSide,
    isLive: Boolean,
    hasAttention: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    var dragX by remember(pwa.id) { mutableFloatStateOf(0f) }
    val closeThreshold = with(LocalDensity.current) { 40.dp.toPx() }
    val outwardSign = if (side == SwitcherSide.LEFT) -1f else 1f

    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer { translationX = dragX }
            .then(
                if (isLive) {
                    Modifier.pointerInput(side, pwa.id) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                val next = dragX + amount
                                dragX = if (next * outwardSign > 0f) next else 0f
                                change.consume()
                            },
                            onDragEnd = {
                                if (isOutwardCloseGesture(side, dragX, closeThreshold)) onClose()
                                dragX = 0f
                            },
                            onDragCancel = { dragX = 0f }
                        )
                    }
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = buildString {
                    append(pwa.name)
                    if (!isLive) append("，需要重新加载")
                    if (hasAttention) append("，有待处理请求")
                }
            },
        contentAlignment = Alignment.Center
    ) {
        PwaSwitcherIcon(pwa)
        if (!isLive) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("↻", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (hasAttention) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
            )
        }
    }
}

@Composable
private fun PwaSwitcherIcon(pwa: PwaEntity) {
    val iconFile = pwa.iconPath.takeIf(String::isNotBlank)?.let(::File)
    if (iconFile?.isFile == true) {
        AsyncImage(
            model = ImageRequest.Builder(LocalView.current.context)
                .data(iconFile)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(15.dp))
        )
    } else {
        Surface(
            modifier = Modifier.size(50.dp),
            shape = RoundedCornerShape(15.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    pwa.name.take(1).uppercase().ifEmpty { "P" },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}
