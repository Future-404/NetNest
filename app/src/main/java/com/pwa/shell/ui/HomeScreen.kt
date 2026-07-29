package com.pwa.shell.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.ImageLoader
import coil.request.ImageRequest
import com.pwa.shell.data.local.PwaEntity
import com.pwa.shell.ui.theme.glassmorphic
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

private sealed interface HomeGridItem {
    val stableKey: Any

    data class Pwa(val value: PwaEntity) : HomeGridItem {
        override val stableKey: Any = value.id
    }

    data object Settings : HomeGridItem {
        override val stableKey: Any = "netnest_system_settings"
    }

    data object AddApp : HomeGridItem {
        override val stableKey: Any = "netnest_system_add_app"
    }
}

private fun buildHomeGridItems(
    pwas: List<PwaEntity>,
    settingsTileIndex: Int,
    addAppTileIndex: Int
): List<HomeGridItem> {
    val result = pwas.mapTo(mutableListOf<HomeGridItem>()) { HomeGridItem.Pwa(it) }

    val systemItems = listOf(
        HomeGridItem.Settings to settingsTileIndex,
        HomeGridItem.AddApp to addAppTileIndex
    ).sortedBy { it.second }

    for ((item, targetIdx) in systemItems) {
        val idx = targetIdx.coerceIn(0, result.size)
        result.add(idx, item)
    }
    return result
}

internal fun stableKeyTargetIndex(
    orderedKeys: List<Any>,
    targetKey: Any?
): Int? {
    if (targetKey == null) return null
    return orderedKeys.indexOfFirst { it == targetKey }.takeIf { it >= 0 }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    settingsTileIndex: Int,
    onSettingsTileIndexChanged: (Int) -> Unit,
    addAppTileIndex: Int,
    onAddAppTileIndexChanged: (Int) -> Unit,
    onSettingsClick: () -> Unit,
    onPwaClick: (PwaEntity) -> Unit,
    onPwaUpdate: (previous: PwaEntity, updated: PwaEntity) -> Unit,
    onPwaDelete: (PwaEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val globalSettingsPreferences = remember(context) { GlobalSettingsPreferences(context) }
    var settingsCustomIcon by remember { mutableStateOf(globalSettingsPreferences.loadSettingsCustomIcon()) }
    var addAppCustomIcon by remember { mutableStateOf(globalSettingsPreferences.loadAddAppCustomIcon()) }
    var targetSystemAppForIconPicker by remember { mutableStateOf<String?>(null) }

    val pwas by viewModel.pwaList.collectAsState(initial = emptyList())
    val uiState by viewModel.uiState.collectAsState()
    val homeScope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    var displayedItems by remember { mutableStateOf<List<HomeGridItem>>(emptyList()) }
    var dragStartOrder by remember { mutableStateOf<List<HomeGridItem>>(emptyList()) }
    var draggedItemKey by remember { mutableStateOf<Any?>(null) }
    var draggedCenter by remember { mutableStateOf(Offset.Zero) }
    var dragInMotion by remember { mutableStateOf(false) }
    val autoScrollEdge = with(LocalDensity.current) { 72.dp.toPx() }

    val systemIconPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && targetSystemAppForIconPicker != null) {
            homeScope.launch {
                PwaIconManager.importCustomIcon(context.applicationContext, uri).onSuccess { path ->
                    if (targetSystemAppForIconPicker == "settings") {
                        val previousPath = settingsCustomIcon
                        settingsCustomIcon = path
                        globalSettingsPreferences.saveSettingsCustomIcon(path)
                        previousPath
                            ?.takeIf { it != path }
                            ?.let { PwaIconManager.deleteManagedIcon(context, it) }
                    } else if (targetSystemAppForIconPicker == "add_app") {
                        val previousPath = addAppCustomIcon
                        addAppCustomIcon = path
                        globalSettingsPreferences.saveAddAppCustomIcon(path)
                        previousPath
                            ?.takeIf { it != path }
                            ?.let { PwaIconManager.deleteManagedIcon(context, it) }
                    }
                    Toast.makeText(context, "图标设置成功", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "图标设置失败: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
                targetSystemAppForIconPicker = null
            }
        }
    }

    fun resetSystemAppIcon(target: String) {
        if (target == "settings") {
            settingsCustomIcon?.let { PwaIconManager.deleteManagedIcon(context, it) }
            settingsCustomIcon = null
            globalSettingsPreferences.saveSettingsCustomIcon(null)
        } else if (target == "add_app") {
            addAppCustomIcon?.let { PwaIconManager.deleteManagedIcon(context, it) }
            addAppCustomIcon = null
            globalSettingsPreferences.saveAddAppCustomIcon(null)
        }
        Toast.makeText(context, "已恢复默认图标", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(pwas, settingsTileIndex, addAppTileIndex) {
        if (draggedItemKey == null) {
            displayedItems = buildHomeGridItems(pwas, settingsTileIndex, addAppTileIndex)
        }
    }

    fun persistHomeOrder(items: List<HomeGridItem>) {
        val reorderedPwas = items.mapNotNull { (it as? HomeGridItem.Pwa)?.value }
        val newSettingsIndex = items.indexOf(HomeGridItem.Settings).coerceAtLeast(0)
        val newAddAppIndex = items.indexOf(HomeGridItem.AddApp).coerceAtLeast(0)
        onSettingsTileIndexChanged(newSettingsIndex)
        onAddAppTileIndexChanged(newAddAppIndex)
        viewModel.reorderPwas(reorderedPwas)
    }

    fun moveDraggedItemTo(position: Offset) {
        val draggedIndex = displayedItems.indexOfFirst { it.stableKey == draggedItemKey }
        val targetKey = gridState.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                position.x >= item.offset.x &&
                    position.x <= item.offset.x + item.size.width &&
                    position.y >= item.offset.y &&
                    position.y <= item.offset.y + item.size.height
            }
            ?.key
        val targetIndex = stableKeyTargetIndex(
            orderedKeys = displayedItems.map { it.stableKey },
            targetKey = targetKey
        )
        if (draggedIndex >= 0 && targetIndex != null && draggedIndex != targetIndex) {
            displayedItems = moveListItem(displayedItems, draggedIndex, targetIndex)
        }
    }

    LaunchedEffect(draggedItemKey) {
        while (draggedItemKey != null) {
            val layoutInfo = gridState.layoutInfo
            val scrollAmount = when {
                !dragInMotion -> 0f
                draggedCenter.y < layoutInfo.viewportStartOffset + autoScrollEdge -> -14f
                draggedCenter.y > layoutInfo.viewportEndOffset - autoScrollEdge -> 14f
                else -> 0f
            }
            if (scrollAmount != 0f) {
                gridState.scrollBy(scrollAmount)
                moveDraggedItemTo(draggedCenter)
            }
            withFrameNanos { }
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<PwaEntity?>(null) }
    var showManualAddDialog by remember { mutableStateOf<UiState.Error?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<PwaEntity?>(null) }

    // Configure Coil ImageLoader with SVG support
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()
    }

    Scaffold(
        topBar = {},
        floatingActionButton = {}, // Removed FAB as AddApp is now a system app tile on grid
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4), // 4 columns per row
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                // Glassmorphic top header
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 28.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Title Bar with Neon Glow Accent
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "NetNest",
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = (-0.5).sp
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.colorScheme.secondary
                                                    )
                                                )
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "PRO",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                                Text(
                                    text = "共 ${pwas.size} 个网络应用桌面入口",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                    }
                }

                    itemsIndexed(
                        displayedItems,
                        key = { _, item -> item.stableKey }
                    ) { index, item ->
                        val itemInfo = gridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == item.stableKey }
                        val dragTranslation = if (
                            draggedItemKey == item.stableKey && itemInfo != null
                        ) {
                            draggedCenter - Offset(
                                itemInfo.offset.x + itemInfo.size.width / 2f,
                                itemInfo.offset.y + itemInfo.size.height / 2f
                            )
                        } else {
                            Offset.Zero
                        }
                        val itemModifier = if (draggedItemKey == item.stableKey) {
                            Modifier
                        } else {
                            Modifier.animateItemPlacement()
                        }
                        fun moveItem(direction: Int) {
                            val targetIndex = index + direction
                            if (targetIndex in displayedItems.indices) {
                                val reordered = moveListItem(
                                    displayedItems,
                                    index,
                                    targetIndex
                                )
                                displayedItems = reordered
                                persistHomeOrder(reordered)
                            }
                        }
                        fun beginDrag() {
                            val info = gridState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == item.stableKey }
                            if (info != null) {
                                dragStartOrder = displayedItems
                                draggedItemKey = item.stableKey
                                dragInMotion = false
                                draggedCenter = Offset(
                                    info.offset.x + info.size.width / 2f,
                                    info.offset.y + info.size.height / 2f
                                )
                            }
                        }
                        fun dragBy(delta: Offset) {
                            dragInMotion = true
                            draggedCenter += delta
                            moveDraggedItemTo(draggedCenter)
                        }
                        fun endDrag(moved: Boolean) {
                            if (moved && displayedItems != dragStartOrder) {
                                persistHomeOrder(displayedItems)
                            }
                            dragInMotion = false
                            draggedItemKey = null
                        }
                        fun cancelDrag() {
                            displayedItems = dragStartOrder
                            dragInMotion = false
                            draggedItemKey = null
                        }

                        when (item) {
                            is HomeGridItem.Pwa -> {
                                val pwa = item.value
                                PwaGridItem(
                                    pwa = pwa,
                                    index = index,
                                    totalItems = displayedItems.size,
                                    imageLoader = imageLoader,
                                    modifier = itemModifier,
                                    isDragging = draggedItemKey == item.stableKey,
                                    dragTranslation = dragTranslation,
                                    onClick = { onPwaClick(pwa) },
                                    onDelete = { showDeleteConfirmDialog = pwa },
                                    onEdit = { showEditDialog = pwa },
                                    onAddToHomeScreen = {
                                        homeScope.launch {
                                            val message = when (
                                                requestPinnedPwaShortcut(
                                                    context.applicationContext,
                                                    pwa
                                                )
                                            ) {
                                                PinPwaShortcutResult.REQUESTED ->
                                                    "请在系统弹窗中确认添加到桌面"
                                                PinPwaShortcutResult.ALREADY_PINNED ->
                                                    "桌面图标已存在，并已更新"
                                                PinPwaShortcutResult.UNSUPPORTED ->
                                                    "当前桌面启动器不支持添加图标"
                                                PinPwaShortcutResult.FAILED ->
                                                    "无法请求添加桌面图标"
                                            }
                                            Toast.makeText(
                                                context,
                                                message,
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    },
                                    onMove = ::moveItem,
                                    onDragStart = ::beginDrag,
                                    onDrag = ::dragBy,
                                    onDragEnd = ::endDrag,
                                    onDragCancel = ::cancelDrag
                                )
                            }
                            HomeGridItem.Settings -> {
                                SettingsGridItem(
                                    index = index,
                                    totalItems = displayedItems.size,
                                    customIconPath = settingsCustomIcon,
                                    imageLoader = imageLoader,
                                    modifier = itemModifier,
                                    isDragging = draggedItemKey == item.stableKey,
                                    dragTranslation = dragTranslation,
                                    onClick = onSettingsClick,
                                    onChangeIcon = {
                                        targetSystemAppForIconPicker = "settings"
                                        systemIconPickerLauncher.launch(arrayOf("image/*"))
                                    },
                                    onResetIcon = { resetSystemAppIcon("settings") },
                                    onMove = ::moveItem,
                                    onDragStart = ::beginDrag,
                                    onDrag = ::dragBy,
                                    onDragEnd = ::endDrag,
                                    onDragCancel = ::cancelDrag
                                )
                            }
                            HomeGridItem.AddApp -> {
                                AddAppGridItem(
                                    index = index,
                                    totalItems = displayedItems.size,
                                    customIconPath = addAppCustomIcon,
                                    imageLoader = imageLoader,
                                    modifier = itemModifier,
                                    isDragging = draggedItemKey == item.stableKey,
                                    dragTranslation = dragTranslation,
                                    onClick = { showAddDialog = true },
                                    onChangeIcon = {
                                        targetSystemAppForIconPicker = "add_app"
                                        systemIconPickerLauncher.launch(arrayOf("image/*"))
                                    },
                                    onResetIcon = { resetSystemAppIcon("add_app") },
                                    onMove = ::moveItem,
                                    onDragStart = ::beginDrag,
                                    onDrag = ::dragBy,
                                    onDragEnd = ::endDrag,
                                    onDragCancel = ::cancelDrag
                                )
                            }
                        }
                    }

                    if (pwas.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "还没有添加网页应用。\n点击“+”创建您的专属网络桌面！",
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp, bottom = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "NetNest v${getAppVersionName(context)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.65f
                                )
                            )
                        }
                    }
                }

            // Global UI state overlays
            when (val state = uiState) {
                is UiState.Loading -> {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        title = { Text("请稍候...") },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(state.message)
                            }
                        }
                    )
                }
                is UiState.Success -> {
                    LaunchedEffect(state) {
                        viewModel.resetState()
                    }
                }
                is UiState.Error -> {
                    AlertDialog(
                        onDismissRequest = { viewModel.resetState() },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.resetState()
                                showManualAddDialog = state
                            }) {
                                Text("手动添加")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.resetState() }) {
                                Text("取消")
                            }
                        },
                        title = { Text("错误") },
                        text = { Text("${state.message}\n\n您想手动添加此 PWA 吗？") }
                    )
                }
                UiState.Idle -> {}
            }

            // Add PWA Dialog
            if (showAddDialog) {
                AddPwaDialog(
                    onDismiss = { showAddDialog = false },
                    onConfirm = { url, dataSpace ->
                        showAddDialog = false
                        viewModel.addPwa(url, context, dataSpace)
                    }
                )
            }

            // Manual Add Dialog
            showManualAddDialog?.let { request ->
                ManualAddDialog(
                    initialUrl = request.fallbackUrl,
                    initialDataSpace = request.dataSpace,
                    onDismiss = { showManualAddDialog = null },
                    onConfirm = { name, url, theme, useChromeUa, useDevConsole, useFullscreen, securityMode, trustedDomains, dataSpace ->
                        showManualAddDialog = null
                        viewModel.addPwaManually(
                            name,
                            url,
                            "",
                            theme,
                            useChromeUa,
                            useDevConsole,
                            useFullscreen,
                            securityMode,
                            trustedDomains,
                            dataSpace
                        )
                    }
                )
            }

            var showScriptManagerForPwa by remember { mutableStateOf<PwaEntity?>(null) }

            // Edit Dialog
            showEditDialog?.let { pwa ->
                EditPwaDialog(
                    pwa = pwa,
                    onDismiss = { showEditDialog = null },
                    onConfirm = { updatedName, updatedUrl, updatedIconPath, updatedTheme, useChromeUa, useDevConsole, useFullscreen, securityMode, securityPromptEnabled, trustedDomains, customUserAgent, customLanguage, customPlatform, screenWidth, screenHeight, deviceScaleFactor, showSwitcherHandle ->
                        showEditDialog = null
                        onPwaUpdate(pwa, pwa.copy(
                            name = updatedName,
                            url = updatedUrl,
                            iconPath = updatedIconPath,
                            themeColor = updatedTheme,
                            useChromeUa = useChromeUa,
                            useDevConsole = useDevConsole,
                            useFullscreen = useFullscreen,
                            securityMode = securityMode,
                            securityPromptEnabled = securityPromptEnabled,
                            trustedDomains = trustedDomains,
                            customUserAgent = customUserAgent,
                            customLanguage = customLanguage,
                            customPlatform = customPlatform,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            deviceScaleFactor = deviceScaleFactor,
                            showSwitcherHandle = showSwitcherHandle
                        ))
                    },
                    onReloadWebsiteIcon = { editedUrl ->
                        viewModel.downloadWebsiteIcon(editedUrl)
                    },
                    onManageScripts = {
                        showEditDialog = null
                        showScriptManagerForPwa = pwa
                    }
                )
            }

            showScriptManagerForPwa?.let { pwa ->
                UserScriptManagerScreen(
                    pwa = pwa,
                    viewModel = viewModel,
                    onDismiss = { showScriptManagerForPwa = null }
                )
            }

            // Delete Confirmation Dialog
            showDeleteConfirmDialog?.let { pwa ->
                val deletionMessage = when {
                    pwa.webProfileId == null ->
                        "您将删除网页应用“${pwa.name}”。该应用使用共享数据空间；为避免影响其他共享 PWA，Cookie 和网页存储不会随单个应用删除。图标、脚本、通知和应用配置会被清理。"
                    pwa.usedSharedCompatibility ->
                        "您将删除网页应用“${pwa.name}”及其独立数据空间。此前共享兼容模式中产生的数据无法安全归属，将保留在旧共享空间中；其他独立 PWA 不受影响。"
                    else ->
                        "您将删除网页应用“${pwa.name}”及其完整独立数据空间，包括 Cookie、本地存储、脚本、通知和图标。其他 PWA 不受影响。"
                }
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmDialog = null },
                    title = { Text("确认删除网页应用？") },
                    text = { Text("$deletionMessage\n\n此操作无法撤销。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onPwaDelete(pwa)
                                displayedItems = displayedItems.filterNot {
                                    it is HomeGridItem.Pwa && it.value.id == pwa.id
                                }
                                showDeleteConfirmDialog = null
                            }
                        ) {
                            Text("确认删除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirmDialog = null }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PwaGridItem(
    pwa: PwaEntity,
    index: Int,
    totalItems: Int,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    isDragging: Boolean,
    dragTranslation: Offset,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    onMove: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
    onDragCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val iconShape = RoundedCornerShape(22.dp)
    val hapticFeedback = LocalHapticFeedback.current
    val dragTouchSlop = LocalViewConfiguration.current.touchSlop
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnDragCancel by rememberUpdatedState(onDragCancel)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationX = dragTranslation.x
                translationY = dragTranslation.y
                scaleX = if (isDragging) 1.10f else 1f
                scaleY = if (isDragging) 1.10f else 1f
            }
            .pointerInput(pwa.id, dragTouchSlop) {
                val dragGate = LongPressDragGate(dragTouchSlop)
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragGate.reset()
                        expanded = false
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnDragStart()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragGate.track(amount)?.let { approvedDelta ->
                            latestOnDrag(approvedDelta)
                        }
                    },
                    onDragEnd = {
                        latestOnDragEnd(dragGate.isDragging)
                        if (!dragGate.isDragging && totalItems > 1) expanded = true
                    },
                    onDragCancel = {
                        dragGate.reset()
                        latestOnDragCancel()
                    }
                )
            }
            .semantics {
                onLongClick(label = "打开应用操作") {
                    expanded = true
                    true
                }
            }
            .clickable(enabled = !isDragging, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Glass Desktop Icon Box
        Box(
            modifier = Modifier
                .size(64.dp)
                .glassmorphic(
                    shape = iconShape,
                    elevation = if (isDragging) 16.dp else 4.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            if (pwa.iconPath.isNotEmpty() && File(pwa.iconPath).exists()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(File(pwa.iconPath))
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = pwa.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(iconShape),
                    contentScale = ContentScale.Fit
                )
            } else {
                // Translucent Glass Letter Placeholder
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = pwa.name.take(1).uppercase(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Title Label
        Text(
            text = pwa.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        // Dropdown options
        Box(modifier = Modifier.size(0.dp)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = (-30).dp, y = (-20).dp),
                modifier = Modifier
                    .width(232.dp)
                    .glassmorphic(shape = RoundedCornerShape(20.dp), elevation = 12.dp)
            ) {
                Text(
                    text = pwa.name,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = { Text("编辑应用", fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)),
                    onClick = {
                        expanded = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("添加到桌面", fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)),
                    onClick = {
                        expanded = false
                        onAddToHomeScreen()
                    }
                )

                if (index > 0) {
                    DropdownMenuItem(
                        text = { Text("向左移动", fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)),
                        onClick = {
                            expanded = false
                            onMove(-1)
                        }
                    )
                }
                if (index < totalItems - 1) {
                    DropdownMenuItem(
                        text = { Text("向右移动", fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)),
                        onClick = {
                            expanded = false
                            onMove(1)
                        }
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                DropdownMenuItem(
                    text = { Text("删除应用", fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    onClick = {
                        expanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsGridItem(
    index: Int,
    totalItems: Int,
    customIconPath: String?,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    isDragging: Boolean,
    dragTranslation: Offset,
    onClick: () -> Unit,
    onChangeIcon: () -> Unit,
    onResetIcon: () -> Unit,
    onMove: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
    onDragCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val iconShape = RoundedCornerShape(22.dp)
    val hapticFeedback = LocalHapticFeedback.current
    val dragTouchSlop = LocalViewConfiguration.current.touchSlop
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnDragCancel by rememberUpdatedState(onDragCancel)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationX = dragTranslation.x
                translationY = dragTranslation.y
                scaleX = if (isDragging) 1.10f else 1f
                scaleY = if (isDragging) 1.10f else 1f
            }
            .pointerInput(dragTouchSlop) {
                val dragGate = LongPressDragGate(dragTouchSlop)
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragGate.reset()
                        expanded = false
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnDragStart()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragGate.track(amount)?.let(latestOnDrag)
                    },
                    onDragEnd = {
                        latestOnDragEnd(dragGate.isDragging)
                        if (!dragGate.isDragging && totalItems > 1) expanded = true
                    },
                    onDragCancel = {
                        dragGate.reset()
                        latestOnDragCancel()
                    }
                )
            }
            .semantics {
                onLongClick(label = "移动设置") {
                    if (totalItems > 1) {
                        expanded = true
                        true
                    } else {
                        false
                    }
                }
            }
            .clickable(enabled = !isDragging, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .glassmorphic(
                    shape = iconShape,
                    elevation = if (isDragging) 16.dp else 4.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!customIconPath.isNullOrBlank() && File(customIconPath).isFile) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(File(customIconPath))
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = "设置",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(iconShape),
                    contentScale = ContentScale.Fit
                )
            } else {
                // Translucent Glass Default Settings Icon
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "设置",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        Box(modifier = Modifier.size(0.dp)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = (-30).dp, y = (-20).dp),
                modifier = Modifier
                    .width(232.dp)
                    .glassmorphic(shape = RoundedCornerShape(20.dp), elevation = 12.dp)
            ) {
                Text(
                    text = "设置应用",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = { Text("更改图标", fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)),
                    onClick = {
                        expanded = false
                        onChangeIcon()
                    }
                )
                if (!customIconPath.isNullOrBlank()) {
                    DropdownMenuItem(
                        text = { Text("恢复默认图标", fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)),
                        onClick = {
                            expanded = false
                            onResetIcon()
                        }
                    )
                }
                if (index > 0) {
                    DropdownMenuItem(
                        text = { Text("向左移动", fontWeight = FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        onClick = {
                            expanded = false
                            onMove(-1)
                        }
                    )
                }
                if (index < totalItems - 1) {
                    DropdownMenuItem(
                        text = { Text("向右移动", fontWeight = FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        onClick = {
                            expanded = false
                            onMove(1)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddAppGridItem(
    index: Int,
    totalItems: Int,
    customIconPath: String?,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    isDragging: Boolean,
    dragTranslation: Offset,
    onClick: () -> Unit,
    onChangeIcon: () -> Unit,
    onResetIcon: () -> Unit,
    onMove: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
    onDragCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val iconShape = RoundedCornerShape(22.dp)
    val hapticFeedback = LocalHapticFeedback.current
    val dragTouchSlop = LocalViewConfiguration.current.touchSlop
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnDragCancel by rememberUpdatedState(onDragCancel)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationX = dragTranslation.x
                translationY = dragTranslation.y
                scaleX = if (isDragging) 1.10f else 1f
                scaleY = if (isDragging) 1.10f else 1f
            }
            .pointerInput(dragTouchSlop) {
                val dragGate = LongPressDragGate(dragTouchSlop)
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragGate.reset()
                        expanded = false
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnDragStart()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragGate.track(amount)?.let(latestOnDrag)
                    },
                    onDragEnd = {
                        latestOnDragEnd(dragGate.isDragging)
                        if (!dragGate.isDragging && totalItems > 1) expanded = true
                    },
                    onDragCancel = {
                        dragGate.reset()
                        latestOnDragCancel()
                    }
                )
            }
            .semantics {
                onLongClick(label = "移动或编辑添加应用图标") {
                    if (totalItems > 1) {
                        expanded = true
                        true
                    } else {
                        false
                    }
                }
            }
            .clickable(enabled = !isDragging, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .glassmorphic(
                    shape = iconShape,
                    elevation = if (isDragging) 16.dp else 4.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!customIconPath.isNullOrBlank() && File(customIconPath).isFile) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(File(customIconPath))
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = "添加应用",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(iconShape),
                    contentScale = ContentScale.Fit
                )
            } else {
                // Translucent Glass Default Add Icon
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "添加应用",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        Box(modifier = Modifier.size(0.dp)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = (-30).dp, y = (-20).dp),
                modifier = Modifier
                    .width(232.dp)
                    .glassmorphic(shape = RoundedCornerShape(20.dp), elevation = 12.dp)
            ) {
                Text(
                    text = "添加应用",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = { Text("添加新应用", fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)),
                    onClick = {
                        expanded = false
                        onClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("更改图标", fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)),
                    onClick = {
                        expanded = false
                        onChangeIcon()
                    }
                )
                if (!customIconPath.isNullOrBlank()) {
                    DropdownMenuItem(
                        text = { Text("恢复默认图标", fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)),
                        onClick = {
                            expanded = false
                            onResetIcon()
                        }
                    )
                }
                if (index > 0) {
                    DropdownMenuItem(
                        text = { Text("向左移动", fontWeight = FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        onClick = {
                            expanded = false
                            onMove(-1)
                        }
                    )
                }
                if (index < totalItems - 1) {
                    DropdownMenuItem(
                        text = { Text("向右移动", fontWeight = FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        onClick = {
                            expanded = false
                            onMove(1)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PwaDataSpaceSelector(
    selected: PwaDataSpace,
    onSelected: (PwaDataSpace) -> Unit
) {
    val isolationSupported = remember { PwaWebProfileManager.isMultiProfileSupported() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("数据空间", style = MaterialTheme.typography.labelLarge)
        PwaDataSpaceOption(
            selected = selected == PwaDataSpace.SHARED,
            title = "共享登录数据",
            description = "多个共享 PWA 可共用登录状态；删除应用时网页数据会保留。",
            onClick = { onSelected(PwaDataSpace.SHARED) }
        )
        PwaDataSpaceOption(
            selected = selected == PwaDataSpace.ISOLATED,
            title = "独立数据空间",
            description = if (isolationSupported) {
                "Cookie 和网页存储独立；删除应用时可完整清理。"
            } else {
                "当前 WebView 暂不支持，将先使用共享兼容模式并在支持后自动启用。"
            },
            onClick = { onSelected(PwaDataSpace.ISOLATED) }
        )
        Text(
            "数据空间创建后不可直接切换。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PwaDataSpaceOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddPwaDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, PwaDataSpace) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var dataSpace by remember { mutableStateOf(PwaDataSpace.SHARED) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank()) {
                        onConfirm(url, dataSpace)
                    } else {
                        isError = true
                    }
                }
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        title = { Text("添加新 PWA") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("输入网站 URL。NetNest 将自动解析 PWA 清单。")
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        isError = false
                    },
                    label = { Text("网站 URL") },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                    isError = isError,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isError) {
                    Text("URL 不能为空", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                PwaDataSpaceSelector(
                    selected = dataSpace,
                    onSelected = { dataSpace = it }
                )
            }
        }
    )
}

@Composable
private fun ManualAddDialog(
    initialUrl: String,
    initialDataSpace: PwaDataSpace,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, themeColor: String?, useChromeUa: Boolean, useDevConsole: Boolean, useFullscreen: Boolean, securityMode: Int, trustedDomains: String, dataSpace: PwaDataSpace) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(initialUrl) }
    var themeColor by remember { mutableStateOf("#6200EE") }
    var useChromeUa by remember { mutableStateOf(true) }
    var useDevConsole by remember { mutableStateOf(false) }
    var useFullscreen by remember { mutableStateOf(false) }
    var isSecurityShieldEnabled by remember { mutableStateOf(true) }
    var trustedDomains by remember { mutableStateOf("") }
    var dataSpace by remember(initialDataSpace) { mutableStateOf(initialDataSpace) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        onConfirm(
                            name,
                            url,
                            themeColor.takeIf { it.isNotBlank() },
                            useChromeUa,
                            useDevConsole,
                            useFullscreen,
                            if (isSecurityShieldEnabled) 1 else 0,
                            trustedDomains,
                            dataSpace
                        )
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        title = { Text("手动添加 PWA") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("应用名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网站 URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PwaDataSpaceSelector(
                    selected = dataSpace,
                    onSelected = { dataSpace = it }
                )
                OutlinedTextField(
                    value = themeColor,
                    onValueChange = { themeColor = it },
                    label = { Text("主题颜色 (十六进制)") },
                    placeholder = { Text("#6200EE") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("标准 Chrome User-Agent", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "移除 '; wv' 以防止功能退化。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useChromeUa,
                        onCheckedChange = { useChromeUa = it }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("应用内开发者控制台", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "注入 vConsole 以在应用内调试控制台和存储。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useDevConsole,
                        onCheckedChange = { useDevConsole = it }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("全屏隐藏状态栏", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "进入该 PWA 后完全隐藏系统通知栏/状态栏。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useFullscreen,
                        onCheckedChange = { useFullscreen = it }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("隐私数据上传拦截", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "阻止网页静默上传聊天记录或API密钥，并弹窗警告。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isSecurityShieldEnabled,
                        onCheckedChange = { isSecurityShieldEnabled = it }
                    )
                }
                if (isSecurityShieldEnabled) {
                    OutlinedTextField(
                        value = trustedDomains,
                        onValueChange = { trustedDomains = it },
                        label = { Text("信任的域名 (逗号分隔)") },
                        placeholder = { Text("例如: api.openrouter.ai,api.openai.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPwaDialog(
    pwa: PwaEntity,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, iconPath: String, themeColor: String?, useChromeUa: Boolean, useDevConsole: Boolean, useFullscreen: Boolean, securityMode: Int, securityPromptEnabled: Boolean, trustedDomains: String, customUserAgent: String?, customLanguage: String, customPlatform: String, screenWidth: Int, screenHeight: Int, deviceScaleFactor: Float, showSwitcherHandle: Boolean) -> Unit,
    onReloadWebsiteIcon: suspend (url: String) -> Result<String>,
    onManageScripts: () -> Unit
) {
    val context = LocalContext.current
    val notificationPermissionStore = remember {
        PwaNotificationPermissionStore(context.applicationContext)
    }
    var notificationPermission by remember(pwa.id, pwa.url) {
        mutableStateOf(notificationPermissionStore.get(pwa.id, pwa.url))
    }
    var name by remember { mutableStateOf(pwa.name) }
    var url by remember { mutableStateOf(pwa.url) }
    var iconPath by remember { mutableStateOf(pwa.iconPath) }
    var iconLoading by remember { mutableStateOf(false) }
    var iconError by remember { mutableStateOf<String?>(null) }
    val iconDraft = remember(pwa.id) { PwaIconDraft(pwa.iconPath) }
    var themeColor by remember { mutableStateOf(pwa.themeColor ?: "") }
    var useChromeUa by remember { mutableStateOf(pwa.useChromeUa) }
    var useDevConsole by remember { mutableStateOf(pwa.useDevConsole) }
    var useFullscreen by remember { mutableStateOf(pwa.useFullscreen) }
    var isSecurityShieldEnabled by remember { mutableStateOf(pwa.securityMode != 0) }
    var securityPromptEnabled by remember { mutableStateOf(pwa.securityPromptEnabled) }
    var trustedDomains by remember { mutableStateOf(pwa.trustedDomains) }
    var customUserAgent by remember { mutableStateOf(pwa.customUserAgent ?: "") }
    var customLanguage by remember { mutableStateOf(pwa.customLanguage) }
    var customPlatform by remember { mutableStateOf(pwa.customPlatform) }
    var screenWidth by remember { mutableStateOf(if (pwa.screenWidth > 0) pwa.screenWidth.toString() else "") }
    var screenHeight by remember { mutableStateOf(if (pwa.screenHeight > 0) pwa.screenHeight.toString() else "") }
    var deviceScaleFactor by remember { mutableStateOf(if (pwa.deviceScaleFactor > 0f) pwa.deviceScaleFactor.toString() else "") }
    var showSwitcherHandle by remember { mutableStateOf(pwa.showSwitcherHandle) }
    var browserSectionExpanded by remember {
        mutableStateOf(
            !pwa.customUserAgent.isNullOrBlank() ||
                pwa.customLanguage.isNotBlank() ||
                pwa.customPlatform.isNotBlank() ||
                pwa.screenWidth > 0 ||
                pwa.screenHeight > 0 ||
                pwa.deviceScaleFactor > 0f
        )
    }
    var developerSectionExpanded by remember { mutableStateOf(pwa.useDevConsole) }
    var attemptedSave by remember { mutableStateOf(false) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    val editorListState = rememberLazyListState()
    val editorScope = rememberCoroutineScope()
    val iconImageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
    fun replaceDraftIcon(updatedPath: String) {
        if (iconPath != pwa.iconPath) {
            PwaIconManager.deleteManagedIcon(context, iconPath)
        }
        iconPath = updatedPath
        iconDraft.replace(updatedPath)
    }
    val iconPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            editorScope.launch {
                iconLoading = true
                iconError = null
                PwaIconManager.importCustomIcon(context.applicationContext, uri)
                    .onSuccess { importedPath ->
                        replaceDraftIcon(importedPath)
                    }
                    .onFailure {
                        iconError = it.localizedMessage ?: "图标导入失败"
                    }
                iconLoading = false
            }
        }
    }
    DisposableEffect(pwa.id) {
        onDispose {
            iconDraft.pathToDeleteOnDispose()
                ?.let { PwaIconManager.deleteManagedIcon(context, it) }
        }
    }

    val nameInvalid = name.isBlank()
    val urlInvalid = !isValidWebUrl(url)
    val hasCustomBrowserIdentity =
        customUserAgent.isNotBlank() ||
            customLanguage.isNotBlank() ||
            customPlatform.isNotBlank() ||
            screenWidth.isNotBlank() ||
            screenHeight.isNotBlank() ||
            deviceScaleFactor.isNotBlank()
    val hasUnsavedChanges =
        name != pwa.name ||
            url != pwa.url ||
            iconPath != pwa.iconPath ||
            themeColor != pwa.themeColor.orEmpty() ||
            useChromeUa != pwa.useChromeUa ||
            useDevConsole != pwa.useDevConsole ||
            useFullscreen != pwa.useFullscreen ||
            isSecurityShieldEnabled != (pwa.securityMode != 0) ||
            securityPromptEnabled != pwa.securityPromptEnabled ||
            trustedDomains != pwa.trustedDomains ||
            customUserAgent != pwa.customUserAgent.orEmpty() ||
            customLanguage != pwa.customLanguage ||
            customPlatform != pwa.customPlatform ||
            (screenWidth.toIntOrNull() ?: 0) != pwa.screenWidth ||
            (screenHeight.toIntOrNull() ?: 0) != pwa.screenHeight ||
            (deviceScaleFactor.toFloatOrNull() ?: 0f) != pwa.deviceScaleFactor ||
            showSwitcherHandle != pwa.showSwitcherHandle

    fun requestDismiss() {
        if (hasUnsavedChanges) {
            showDiscardConfirmation = true
        } else {
            onDismiss()
        }
    }

    fun save(onSaved: (() -> Unit)? = null) {
        attemptedSave = true
        if (nameInvalid || urlInvalid) {
            editorScope.launch { editorListState.animateScrollToItem(0) }
            return
        }
        iconDraft.commit()
        onConfirm(
            name.trim(),
            url.trim(),
            iconPath,
            themeColor.trim().takeIf { it.isNotEmpty() },
            useChromeUa,
            useDevConsole,
            useFullscreen,
            if (isSecurityShieldEnabled) 1 else 0,
            securityPromptEnabled,
            trustedDomains.trim(),
            customUserAgent.trim().takeIf { it.isNotEmpty() },
            customLanguage.trim(),
            customPlatform.trim(),
            screenWidth.toIntOrNull()?.coerceIn(0, 10000) ?: 0,
            screenHeight.toIntOrNull()?.coerceIn(0, 10000) ?: 0,
            deviceScaleFactor.toFloatOrNull()?.coerceIn(0.1f, 8f) ?: 0f,
            showSwitcherHandle
        )
        onSaved?.invoke()
    }

    Dialog(
        onDismissRequest = { requestDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("编辑应用")
                                Text(
                                    pwa.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { requestDismiss() }) {
                                Icon(Icons.Default.Close, contentDescription = "关闭编辑")
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { requestDismiss() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("取消")
                            }
                            Button(
                                onClick = { save() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("保存修改")
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    LazyColumn(
                        state = editorListState,
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 16.dp,
                            end = 16.dp,
                            bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                        EditorSection(
                            title = "基础信息",
                            description = "应用名称、地址和显示方式"
                        ) {
                            Surface(
                                color = if (pwa.webProfileId == null) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = if (pwa.webProfileId == null) {
                                            "数据空间：共享"
                                        } else {
                                            "数据空间：独立配置"
                                        },
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        text = if (pwa.webProfileId == null) {
                                            "Cookie 和网页本地数据可与其他共享 PWA 共用；删除应用时共享网页数据会保留。"
                                        } else {
                                            "支持时 Cookie 和网页本地数据与其他 PWA 隔离；不支持时会显示兼容提示。"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(getSoftColor(url)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (iconPath.isNotBlank() && File(iconPath).isFile) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(File(iconPath))
                                                .crossfade(true)
                                                .build(),
                                            imageLoader = iconImageLoader,
                                            contentDescription = "应用图标预览",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = name.take(1).uppercase().ifEmpty { "P" },
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF49454F)
                                        )
                                    }
                                    if (iconLoading) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                            shape = RoundedCornerShape(20.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(28.dp),
                                                    strokeWidth = 3.dp
                                                )
                                            }
                                        }
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            iconPickerLauncher.launch(arrayOf("image/*"))
                                        },
                                        enabled = !iconLoading,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("选择自定义图标")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            if (!isValidWebUrl(url)) {
                                                iconError = "请先填写有效的网站 URL"
                                            } else {
                                                editorScope.launch {
                                                    iconLoading = true
                                                    iconError = null
                                                    onReloadWebsiteIcon(url.trim())
                                                        .onSuccess(::replaceDraftIcon)
                                                        .onFailure {
                                                            iconError = it.localizedMessage
                                                                ?: "网站图标获取失败"
                                                        }
                                                    iconLoading = false
                                                }
                                            }
                                        },
                                        enabled = !iconLoading,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("恢复网站图标")
                                    }
                                }
                            }
                            Text(
                                text = "图片会按比例居中裁剪为 512×512 图标，并安全保存到 NetNest。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            iconError?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("应用名称") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                isError = attemptedSave && nameInvalid,
                                supportingText = {
                                    if (attemptedSave && nameInvalid) Text("请输入应用名称")
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text("网站 URL") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next
                                ),
                                isError = attemptedSave && urlInvalid,
                                supportingText = {
                                    if (attemptedSave && urlInvalid) {
                                        Text("请输入完整的 http:// 或 https:// 地址")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = themeColor,
                                onValueChange = { themeColor = it },
                                label = { Text("主题颜色") },
                                placeholder = { Text("#6200EE") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            EditorSwitchRow(
                                title = "全屏显示",
                                description = "进入应用后隐藏系统状态栏",
                                checked = useFullscreen,
                                onCheckedChange = { useFullscreen = it }
                            )
                            HorizontalDivider()
                            EditorSwitchRow(
                                title = "显示应用切换侧边条",
                                description = "可随时打开最近应用或上下滑动快速切换",
                                checked = showSwitcherHandle,
                                onCheckedChange = { showSwitcherHandle = it }
                            )
                        }
                        }

                        item {
                        EditorSection(
                            title = "隐私与安全",
                            description = "控制敏感数据上传与网页通知权限"
                        ) {
                            EditorSwitchRow(
                                title = "隐私数据上传拦截",
                                description = "检测聊天记录、API 密钥和账号凭证",
                                checked = isSecurityShieldEnabled,
                                onCheckedChange = { isSecurityShieldEnabled = it }
                            )
                            if (isSecurityShieldEnabled) {
                                HorizontalDivider()
                                EditorSwitchRow(
                                    title = "显示拦截提醒",
                                    description = "关闭后自动拦截，不再弹窗打断",
                                    checked = securityPromptEnabled,
                                    onCheckedChange = { securityPromptEnabled = it }
                                )
                                OutlinedTextField(
                                    value = trustedDomains,
                                    onValueChange = { trustedDomains = it },
                                    label = { Text("信任域名") },
                                    placeholder = { Text("api.example.com, backup.example.com") },
                                    supportingText = { Text("多个域名使用英文逗号分隔") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            HorizontalDivider()
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("网页通知", style = MaterialTheme.typography.bodyMedium)
                                val effectivePermission = effectiveNotificationPermission(
                                    context,
                                    notificationPermission,
                                    pwa.id
                                )
                                val permissionLabel = when {
                                    notificationPermission == PwaNotificationPermission.GRANTED &&
                                        effectivePermission == PwaNotificationPermission.DENIED ->
                                        "网页已允许，但系统通知已关闭"
                                    notificationPermission == PwaNotificationPermission.GRANTED ->
                                        "已允许"
                                    notificationPermission == PwaNotificationPermission.DENIED ->
                                        "已阻止"
                                    else -> "尚未请求"
                                }
                                Text(
                                    "$permissionLabel。权限按此 PWA 独立保存。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (notificationPermission != PwaNotificationPermission.DEFAULT) {
                                    TextButton(
                                        onClick = {
                                            resetPwaNotificationPermission(context, pwa)
                                            notificationPermission =
                                                PwaNotificationPermission.DEFAULT
                                        }
                                    ) {
                                        Text("重置通知授权")
                                    }
                                }
                            }
                        }
                        }

                        item {
                        ExpandableEditorSection(
                            title = "浏览器与设备身份",
                            description = if (browserSectionExpanded) {
                                "自定义网页可见的浏览器参数"
                            } else if (hasCustomBrowserIdentity) {
                                "已配置自定义浏览器身份"
                            } else {
                                "使用默认浏览器身份"
                            },
                            expanded = browserSectionExpanded,
                            onExpandedChange = { browserSectionExpanded = it }
                        ) {
                            Text(
                                "TLS 指纹由 Android WebView 控制，以下选项仅修改网页可见参数。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            EditorSwitchRow(
                                title = "标准 Chrome User-Agent",
                                description = "移除 WebView 标识以提高网页兼容性",
                                checked = useChromeUa,
                                onCheckedChange = { useChromeUa = it }
                            )
                            OutlinedTextField(
                                value = customUserAgent,
                                onValueChange = { customUserAgent = it },
                                label = { Text("自定义 User-Agent（可选）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = customLanguage,
                                onValueChange = { customLanguage = it },
                                label = { Text("语言") },
                                placeholder = { Text("zh-CN") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = customPlatform,
                                onValueChange = { customPlatform = it },
                                label = { Text("平台") },
                                placeholder = { Text("Android") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = screenWidth,
                                    onValueChange = { screenWidth = it.filter(Char::isDigit) },
                                    label = { Text("宽度") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = screenHeight,
                                    onValueChange = { screenHeight = it.filter(Char::isDigit) },
                                    label = { Text("高度") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = deviceScaleFactor,
                                    onValueChange = {
                                        deviceScaleFactor = it.filter { char ->
                                            char.isDigit() || char == '.'
                                        }
                                    },
                                    label = { Text("DPR") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Decimal
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        }

                        item {
                        ExpandableEditorSection(
                            title = "开发者工具",
                            description = if (useDevConsole) "控制台已开启" else "控制台与自定义脚本",
                            expanded = developerSectionExpanded,
                            onExpandedChange = { developerSectionExpanded = it }
                        ) {
                            EditorSwitchRow(
                                title = "应用内开发者控制台",
                                description = "注入 vConsole，用于调试页面和存储",
                                checked = useDevConsole,
                                onCheckedChange = { useDevConsole = it }
                            )
                            OutlinedButton(
                                onClick = { save(onManageScripts) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("保存设置并管理脚本")
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("退出后，本次修改将不会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onDismiss()
                    }
                ) {
                    Text("放弃修改", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text("继续编辑")
                }
            }
        )
    }
}

@Composable
private fun EditorSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
private fun ExpandableEditorSection(
    title: String,
    description: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(if (expanded) "收起" else "展开")
                }
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun EditorSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

internal fun isValidWebUrl(value: String): Boolean {
    return runCatching {
        val uri = java.net.URI(value.trim())
        (uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

// Generate consistent soft/pastel colors based on URL hashing
private fun getSoftColor(url: String): Color {
    val softColors = listOf(
        Color(0xFFFFD2D2), // soft peach-pink
        Color(0xFFFFE3D2), // soft apricot
        Color(0xFFFFF2D2), // soft cream-yellow
        Color(0xFFE2F0D9), // soft light-green
        Color(0xFFD9F2E6), // soft mint
        Color(0xFFD9EAF2), // soft pastel-blue
        Color(0xFFE6D9F2), // soft lilac
        Color(0xFFF2D9E6), // soft rose
        Color(0xFFECEFF1), // soft blue-gray
        Color(0xFFEFEBE9)  // soft clay-gray
    )
    if (url.isEmpty()) return Color(0xFFECEFF1)
    val hash = abs(url.hashCode())
    val index = hash % softColors.size
    return softColors[index]
}

private fun parseHexColor(hex: String?): Color {
    if (hex.isNullOrEmpty()) return Color(0xFF6200EE)
    return try {
        val cleaned = hex.trim().replace("#", "")
        if (cleaned.length == 6) {
            Color(android.graphics.Color.parseColor("#$cleaned"))
        } else if (cleaned.length == 3) {
            val r = cleaned[0].toString().repeat(2)
            val g = cleaned[1].toString().repeat(2)
            val b = cleaned[2].toString().repeat(2)
            Color(android.graphics.Color.parseColor("#$r$g$b"))
        } else {
            Color(android.graphics.Color.parseColor(hex))
        }
    } catch (e: Exception) {
        Color(0xFF6200EE)
    }
}
