package com.pwa.shell.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.ImageLoader
import coil.request.ImageRequest
import com.pwa.shell.data.local.PwaEntity
import com.pwa.shell.data.local.PwaFolderEntity
import com.pwa.shell.ui.theme.glassmorphic
import com.pwa.shell.ui.theme.glassmorphicOverlay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

private sealed interface HomeGridItem {
    val stableKey: Any

    data class Pwa(val value: PwaEntity) : HomeGridItem {
        override val stableKey: Any = value.id
    }

    data class Folder(
        val value: PwaFolderEntity,
        val members: List<PwaEntity>
    ) : HomeGridItem {
        override val stableKey: Any = "netnest_folder_${value.id}"
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
    folders: List<PwaFolderEntity>,
    settingsTileIndex: Int,
    addAppTileIndex: Int
): List<HomeGridItem> {
    val pwasById = pwas.associateBy(PwaEntity::id)
    val foldersById = folders.associateBy(PwaFolderEntity::id)
    val result = persistentHomeOrder(pwas, folders)
        .mapNotNullTo(mutableListOf()) { entry ->
            when (entry) {
                is HomeOrderEntry.Pwa ->
                    pwasById[entry.id]?.let(HomeGridItem::Pwa)
                is HomeOrderEntry.Folder -> foldersById[entry.id]?.let { folder ->
                    HomeGridItem.Folder(
                        value = folder,
                        members = pwas
                            .filter { it.folderId == folder.id }
                            .sortedWith(
                                compareBy<PwaEntity> { it.folderOrder }
                                    .thenBy { it.addedTime }
                            )
                    )
                }
            }
        }

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

private class NetNestMenuPositionProvider(
    private val offset: IntOffset,
    private val windowMarginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - windowMarginPx)
            .coerceAtLeast(windowMarginPx)
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left + offset.x
        } else {
            anchorBounds.right - popupContentSize.width - offset.x
        }
        val x = preferredX.coerceIn(windowMarginPx, maxX)

        val maxY = (windowSize.height - popupContentSize.height - windowMarginPx)
            .coerceAtLeast(windowMarginPx)
        val below = anchorBounds.bottom + offset.y
        val above = anchorBounds.top - popupContentSize.height - offset.y
        val preferredY = when {
            below <= maxY -> below
            above >= windowMarginPx -> above
            else -> below.coerceIn(windowMarginPx, maxY)
        }
        return IntOffset(x, preferredY.coerceIn(windowMarginPx, maxY))
    }
}

@Composable
private fun NetNestDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return

    val density = LocalDensity.current
    val positionProvider = remember(offset, density) {
        NetNestMenuPositionProvider(
            offset = with(density) {
                IntOffset(offset.x.roundToPx(), offset.y.roundToPx())
            },
            windowMarginPx = with(density) { 8.dp.roundToPx() }
        )
    }
    val shape = RoundedCornerShape(22.dp)

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Column(
            modifier = Modifier
                .width(232.dp)
                .heightIn(max = 420.dp)
                .glassmorphicOverlay(shape = shape, elevation = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            content = content
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    pwas: List<PwaEntity>,
    folders: List<PwaFolderEntity>,
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

    val uiState by viewModel.uiState.collectAsState()
    val homeScope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    var displayedItems by remember(
        pwas,
        folders,
        settingsTileIndex,
        addAppTileIndex
    ) {
        mutableStateOf(
            buildHomeGridItems(pwas, folders, settingsTileIndex, addAppTileIndex)
        )
    }
    var dragStartOrder by remember { mutableStateOf<List<HomeGridItem>>(emptyList()) }
    var draggedItemKey by remember { mutableStateOf<Any?>(null) }
    var draggedCenter by remember { mutableStateOf(Offset.Zero) }
    var dragInMotion by remember { mutableStateOf(false) }
    var folderDropTargetKey by remember { mutableStateOf<Any?>(null) }
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

    fun persistHomeOrder(items: List<HomeGridItem>) {
        val entries = items.mapNotNull {
            when (it) {
                is HomeGridItem.Pwa -> HomeOrderEntry.Pwa(it.value.id)
                is HomeGridItem.Folder -> HomeOrderEntry.Folder(it.value.id)
                HomeGridItem.Settings, HomeGridItem.AddApp -> null
            }
        }
        val newSettingsIndex = items.indexOf(HomeGridItem.Settings).coerceAtLeast(0)
        val newAddAppIndex = items.indexOf(HomeGridItem.AddApp).coerceAtLeast(0)
        onSettingsTileIndexChanged(newSettingsIndex)
        onAddAppTileIndexChanged(newAddAppIndex)
        viewModel.reorderHomeItems(entries)
    }

    fun moveDraggedItemTo(position: Offset) {
        val draggedIndex = displayedItems.indexOfFirst { it.stableKey == draggedItemKey }
        val targetInfo = gridState.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                position.x >= item.offset.x &&
                    position.x <= item.offset.x + item.size.width &&
                    position.y >= item.offset.y &&
                    position.y <= item.offset.y + item.size.height
            }
        val targetKey = targetInfo?.key
        val targetIndex = stableKeyTargetIndex(
            orderedKeys = displayedItems.map { it.stableKey },
            targetKey = targetKey
        )
        val draggedItem = displayedItems.getOrNull(draggedIndex)
        val targetItem = targetIndex?.let(displayedItems::getOrNull)
        val isCenteredOnTarget = targetInfo?.let { info ->
            val insetX = info.size.width * 0.18f
            val insetY = info.size.height * 0.14f
            position.x >= info.offset.x + insetX &&
                position.x <= info.offset.x + info.size.width - insetX &&
                position.y >= info.offset.y + insetY &&
                position.y <= info.offset.y + info.size.height - insetY
        } == true
        val targetKind = when {
            draggedItem !is HomeGridItem.Pwa ||
                draggedItem.value.folderId != null ||
                targetItem?.stableKey == draggedItem.stableKey ->
                PwaDropTargetKind.NONE
            targetItem is HomeGridItem.Pwa -> PwaDropTargetKind.PWA
            targetItem is HomeGridItem.Folder -> PwaDropTargetKind.FOLDER
            else -> PwaDropTargetKind.NONE
        }
        when (
            pwaTargetDragAction(
                targetKind = targetKind,
                isCentered = isCenteredOnTarget,
                wasCenteredOnSameTarget =
                    folderDropTargetKey == targetItem?.stableKey
            )
        ) {
            PwaTargetDragAction.WAIT_FOR_CENTER -> {
                folderDropTargetKey = null
                return
            }
            PwaTargetDragAction.GROUP -> {
                folderDropTargetKey = targetItem?.stableKey
                return
            }
            PwaTargetDragAction.REORDER -> Unit
        }
        folderDropTargetKey = null
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
    var openFolderId by remember { mutableStateOf<Long?>(null) }
    var renameFolderId by remember { mutableStateOf<Long?>(null) }
    var pendingFolderCreation by remember {
        mutableStateOf<Pair<PwaEntity, PwaEntity>?>(null)
    }
    var organizePwa by remember { mutableStateOf<PwaEntity?>(null) }
    var pendingDissolveFolder by remember { mutableStateOf<PwaFolderEntity?>(null) }

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
                    itemsIndexed(
                        displayedItems,
                        key = { _, item -> item.stableKey }
                    ) { _, item ->
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
                        fun beginDrag() {
                            val info = gridState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == item.stableKey }
                            if (info != null) {
                                dragStartOrder = displayedItems
                                draggedItemKey = item.stableKey
                                dragInMotion = false
                                folderDropTargetKey = null
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
                            val source = dragStartOrder.firstOrNull {
                                it.stableKey == draggedItemKey
                            }
                            val target = displayedItems.firstOrNull {
                                it.stableKey == folderDropTargetKey
                            }
                            if (moved && source is HomeGridItem.Pwa && target != null) {
                                displayedItems = dragStartOrder
                                when (target) {
                                    is HomeGridItem.Pwa -> {
                                        pendingFolderCreation = source.value to target.value
                                    }
                                    is HomeGridItem.Folder -> {
                                        viewModel.addPwaToFolder(
                                            source.value.id,
                                            target.value.id
                                        ) { result ->
                                            Toast.makeText(
                                                context,
                                                result.fold(
                                                    onSuccess = { "已加入“${target.value.name}”" },
                                                    onFailure = {
                                                        it.localizedMessage ?: "加入文件夹失败"
                                                    }
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    HomeGridItem.Settings, HomeGridItem.AddApp -> Unit
                                }
                            } else if (moved && displayedItems != dragStartOrder) {
                                persistHomeOrder(displayedItems)
                            }
                            dragInMotion = false
                            draggedItemKey = null
                            folderDropTargetKey = null
                        }
                        fun cancelDrag() {
                            displayedItems = dragStartOrder
                            dragInMotion = false
                            draggedItemKey = null
                            folderDropTargetKey = null
                        }

                        when (item) {
                            is HomeGridItem.Pwa -> {
                                val pwa = item.value
                                PwaGridItem(
                                    pwa = pwa,
                                    imageLoader = imageLoader,
                                    modifier = itemModifier,
                                    isDragging = draggedItemKey == item.stableKey,
                                    isFolderDropTarget =
                                        folderDropTargetKey == item.stableKey,
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
                                    onOrganize = { organizePwa = pwa },
                                    onDragStart = ::beginDrag,
                                    onDrag = ::dragBy,
                                    onDragEnd = ::endDrag,
                                    onDragCancel = ::cancelDrag
                                )
                            }
                            is HomeGridItem.Folder -> {
                                FolderGridItem(
                                    folder = item.value,
                                    members = item.members,
                                    imageLoader = imageLoader,
                                    modifier = itemModifier,
                                    isDragging = draggedItemKey == item.stableKey,
                                    isFolderDropTarget =
                                        folderDropTargetKey == item.stableKey,
                                    dragTranslation = dragTranslation,
                                    onClick = { openFolderId = item.value.id },
                                    onRename = { renameFolderId = item.value.id },
                                    onDissolve = { pendingDissolveFolder = item.value },
                                    onDragStart = ::beginDrag,
                                    onDrag = ::dragBy,
                                    onDragEnd = ::endDrag,
                                    onDragCancel = ::cancelDrag
                                )
                            }
                            HomeGridItem.Settings -> {
                                SettingsGridItem(
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
                                    onDragStart = ::beginDrag,
                                    onDrag = ::dragBy,
                                    onDragEnd = ::endDrag,
                                    onDragCancel = ::cancelDrag
                                )
                            }
                            HomeGridItem.AddApp -> {
                                AddAppGridItem(
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

            pendingFolderCreation?.let { (firstPwa, secondPwa) ->
                FolderNameDialog(
                    title = "新建文件夹",
                    initialName = "新文件夹",
                    confirmLabel = "创建",
                    onDismiss = { pendingFolderCreation = null },
                    onConfirm = { name ->
                        viewModel.createFolder(firstPwa.id, secondPwa.id, name) { result ->
                            Toast.makeText(
                                context,
                                result.fold(
                                    onSuccess = { "文件夹已创建" },
                                    onFailure = {
                                        it.localizedMessage ?: "创建文件夹失败"
                                    }
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        pendingFolderCreation = null
                    }
                )
            }

            renameFolderId?.let { folderId ->
                val folder = folders.firstOrNull { it.id == folderId }
                if (folder != null) {
                    FolderNameDialog(
                        title = "重命名文件夹",
                        initialName = folder.name,
                        confirmLabel = "保存",
                        onDismiss = { renameFolderId = null },
                        onConfirm = { name ->
                            viewModel.renameFolder(folder.id, name) { result ->
                                result.exceptionOrNull()?.let {
                                    Toast.makeText(
                                        context,
                                        it.localizedMessage ?: "重命名失败",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            renameFolderId = null
                        }
                    )
                } else {
                    LaunchedEffect(folderId) { renameFolderId = null }
                }
            }

            organizePwa?.let { pwa ->
                OrganizePwaDialog(
                    pwa = pwa,
                    folders = folders,
                    rootPwas = pwas.filter { it.folderId == null && it.id != pwa.id },
                    onDismiss = { organizePwa = null },
                    onFolderSelected = { folder ->
                        viewModel.addPwaToFolder(pwa.id, folder.id) { result ->
                            Toast.makeText(
                                context,
                                result.fold(
                                    onSuccess = { "已加入“${folder.name}”" },
                                    onFailure = {
                                        it.localizedMessage ?: "加入文件夹失败"
                                    }
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        organizePwa = null
                    },
                    onPwaSelected = { otherPwa ->
                        organizePwa = null
                        pendingFolderCreation = pwa to otherPwa
                    }
                )
            }

            openFolderId?.let { folderId ->
                val folder = folders.firstOrNull { it.id == folderId }
                val members = pwas
                    .filter { it.folderId == folderId }
                    .sortedWith(
                        compareBy<PwaEntity> { it.folderOrder }
                            .thenBy { it.addedTime }
                    )
                if (folder != null) {
                    FolderContentsDialog(
                        folder = folder,
                        members = members,
                        imageLoader = imageLoader,
                        onDismiss = { openFolderId = null },
                        onOpen = { pwa ->
                            openFolderId = null
                            onPwaClick(pwa)
                        },
                        onEdit = { pwa ->
                            openFolderId = null
                            showEditDialog = pwa
                        },
                        onRemove = { pwa ->
                            if (members.size <= 2) openFolderId = null
                            viewModel.removePwaFromFolder(pwa.id) { result ->
                                Toast.makeText(
                                    context,
                                    result.fold(
                                        onSuccess = {
                                            if (members.size <= 2) {
                                                "文件夹已自动解散"
                                            } else {
                                                "“${pwa.name}”已移出文件夹"
                                            }
                                        },
                                        onFailure = {
                                            it.localizedMessage ?: "移出文件夹失败"
                                        }
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onDelete = { pwa ->
                            openFolderId = null
                            showDeleteConfirmDialog = pwa
                        },
                        onRename = {
                            openFolderId = null
                            renameFolderId = folder.id
                        },
                        onDissolve = {
                            openFolderId = null
                            pendingDissolveFolder = folder
                        }
                    )
                } else {
                    LaunchedEffect(folderId) { openFolderId = null }
                }
            }

            pendingDissolveFolder?.let { folder ->
                AlertDialog(
                    onDismissRequest = { pendingDissolveFolder = null },
                    title = { Text("解散“${folder.name}”？") },
                    text = {
                        Text("文件夹内的应用会回到主界面，应用及其数据不会被删除。")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.dissolveFolder(folder.id) { result ->
                                    Toast.makeText(
                                        context,
                                        result.fold(
                                            onSuccess = { "文件夹已解散" },
                                            onFailure = {
                                                it.localizedMessage ?: "解散文件夹失败"
                                            }
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                pendingDissolveFolder = null
                            }
                        ) {
                            Text("解散")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDissolveFolder = null }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val normalizedName = normalizedFolderName(name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("文件夹名称") },
                supportingText = { Text("${name.trim().length}/30") },
                isError = name.isNotBlank() && normalizedName == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = normalizedName != null,
                onClick = { normalizedName?.let(onConfirm) }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun OrganizePwaDialog(
    pwa: PwaEntity,
    folders: List<PwaFolderEntity>,
    rootPwas: List<PwaEntity>,
    onDismiss: () -> Unit,
    onFolderSelected: (PwaFolderEntity) -> Unit,
    onPwaSelected: (PwaEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("整理“${pwa.name}”") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (folders.isNotEmpty()) {
                    item {
                        Text(
                            text = "加入现有文件夹",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(
                        count = folders.size,
                        key = { index -> "organize_folder_${folders[index].id}" }
                    ) { index ->
                        val folder = folders[index]
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            leadingContent = {
                                FolderGlyph(modifier = Modifier.size(24.dp))
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onFolderSelected(folder) }
                        )
                    }
                }

                if (rootPwas.isNotEmpty()) {
                    item {
                        Text(
                            text = "与另一个应用新建文件夹",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
                        )
                    }
                    items(
                        count = rootPwas.size,
                        key = { index -> "organize_pwa_${rootPwas[index].id}" }
                    ) { index ->
                        val other = rootPwas[index]
                        ListItem(
                            headlineContent = { Text(other.name) },
                            leadingContent = {
                                Icon(Icons.Default.Add, contentDescription = null)
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onPwaSelected(other) }
                        )
                    }
                }

                if (folders.isEmpty() && rootPwas.isEmpty()) {
                    item {
                        Text(
                            text = "至少还需要一个主界面应用，才能创建文件夹。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderContentsDialog(
    folder: PwaFolderEntity,
    members: List<PwaEntity>,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
    onOpen: (PwaEntity) -> Unit,
    onEdit: (PwaEntity) -> Unit,
    onRemove: (PwaEntity) -> Unit,
    onDelete: (PwaEntity) -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(30.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(min = 260.dp, max = 620.dp)
                .glassmorphicOverlay(shape = shape, elevation = 20.dp)
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = "重命名文件夹")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed(
                    items = members,
                    key = { _, member -> "folder_member_${member.id}" }
                ) { _, member ->
                    FolderMemberItem(
                        pwa = member,
                        imageLoader = imageLoader,
                        onOpen = { onOpen(member) },
                        onEdit = { onEdit(member) },
                        onRemove = { onRemove(member) },
                        onDelete = { onDelete(member) }
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
            TextButton(
                onClick = onDissolve,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("解散文件夹", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderMemberItem(
    pwa: PwaEntity,
    imageLoader: ImageLoader,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val iconShape = RoundedCornerShape(18.dp)
    val iconFile = pwa.iconPath.takeIf(String::isNotBlank)?.let(::File)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { expanded = true }
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .glassmorphic(shape = iconShape, elevation = 3.dp)
                .clip(iconShape),
            contentAlignment = Alignment.Center
        ) {
            if (iconFile?.isFile == true) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(iconFile)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = pwa.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = pwa.name,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = pwa.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Box(modifier = Modifier.size(0.dp)) {
            NetNestDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = (-28).dp, y = (-18).dp)
            ) {
                DropdownMenuItem(
                    text = { Text("打开") },
                    leadingIcon = {
                        Icon(Icons.Default.Home, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onOpen()
                    }
                )
                DropdownMenuItem(
                    text = { Text("编辑") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("移出文件夹") },
                    leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onRemove()
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除应用") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error
                    ),
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
fun PwaGridItem(
    pwa: PwaEntity,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    isDragging: Boolean,
    isFolderDropTarget: Boolean,
    dragTranslation: Offset,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    onOrganize: () -> Unit,
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
                val scale = when {
                    isDragging -> 1.10f
                    isFolderDropTarget -> 1.07f
                    else -> 1f
                }
                scaleX = scale
                scaleY = scale
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
                        if (!dragGate.isDragging) expanded = true
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
            NetNestDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = (-30).dp, y = (-20).dp)
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
                DropdownMenuItem(
                    text = { Text("整理到文件夹", fontWeight = FontWeight.Medium) },
                    leadingIcon = { FolderGlyph(modifier = Modifier.size(24.dp)) },
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    onClick = {
                        expanded = false
                        onOrganize()
                    }
                )

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
private fun FolderGridItem(
    folder: PwaFolderEntity,
    members: List<PwaEntity>,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    isDragging: Boolean,
    isFolderDropTarget: Boolean,
    dragTranslation: Offset,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
    onDragCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
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
                val scale = when {
                    isDragging -> 1.10f
                    isFolderDropTarget -> 1.07f
                    else -> 1f
                }
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(folder.id, dragTouchSlop) {
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
                        if (!dragGate.isDragging) expanded = true
                    },
                    onDragCancel = {
                        dragGate.reset()
                        latestOnDragCancel()
                    }
                )
            }
            .semantics {
                onLongClick(label = "打开文件夹操作") {
                    expanded = true
                    true
                }
            }
            .clickable(enabled = !isDragging, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        FolderPreview(
            members = members,
            imageLoader = imageLoader,
            elevated = isDragging || isFolderDropTarget
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = folder.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Box(modifier = Modifier.size(0.dp)) {
            NetNestDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = (-30).dp, y = (-20).dp)
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = { Text("打开文件夹", fontWeight = FontWeight.Medium) },
                    leadingIcon = {
                        FolderGlyph(modifier = Modifier.size(24.dp))
                    },
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    onClick = {
                        expanded = false
                        onClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("重命名", fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    onClick = {
                        expanded = false
                        onRename()
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                DropdownMenuItem(
                    text = { Text("解散文件夹", fontWeight = FontWeight.SemiBold) },
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
                        onDissolve()
                    }
                )
            }
        }
    }
}

@Composable
private fun FolderGlyph(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(modifier = modifier) {
        val corner = size.minDimension * 0.16f
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, size.height * 0.06f),
            size = Size(size.width * 0.52f, size.height * 0.36f),
            cornerRadius = CornerRadius(corner, corner)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, size.height * 0.24f),
            size = Size(size.width, size.height * 0.70f),
            cornerRadius = CornerRadius(corner, corner)
        )
    }
}

@Composable
private fun FolderPreview(
    members: List<PwaEntity>,
    imageLoader: ImageLoader,
    elevated: Boolean
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .size(64.dp)
            .glassmorphic(
                shape = shape,
                elevation = if (elevated) 14.dp else 4.dp
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) { column ->
                    val member = members.getOrNull(row * 3 + column)
                    FolderPreviewIcon(member = member, imageLoader = imageLoader)
                }
            }
        }
    }
}

@Composable
private fun FolderPreviewIcon(
    member: PwaEntity?,
    imageLoader: ImageLoader
) {
    if (member == null) {
        Spacer(modifier = Modifier.size(16.dp))
        return
    }
    val iconFile = member.iconPath.takeIf(String::isNotBlank)?.let(::File)
    if (iconFile?.isFile == true) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(iconFile)
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = Modifier.size(16.dp),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsGridItem(
    customIconPath: String?,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    isDragging: Boolean,
    dragTranslation: Offset,
    onClick: () -> Unit,
    onChangeIcon: () -> Unit,
    onResetIcon: () -> Unit,
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
                        if (!dragGate.isDragging) expanded = true
                    },
                    onDragCancel = {
                        dragGate.reset()
                        latestOnDragCancel()
                    }
                )
            }
            .semantics {
                onLongClick(label = "移动设置") {
                    expanded = true
                    true
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
            NetNestDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = (-30).dp, y = (-20).dp)
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
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddAppGridItem(
    customIconPath: String?,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    isDragging: Boolean,
    dragTranslation: Offset,
    onClick: () -> Unit,
    onChangeIcon: () -> Unit,
    onResetIcon: () -> Unit,
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
                        if (!dragGate.isDragging) expanded = true
                    },
                    onDragCancel = {
                        dragGate.reset()
                        latestOnDragCancel()
                    }
                )
            }
            .semantics {
                onLongClick(label = "移动或编辑添加应用图标") {
                    expanded = true
                    true
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
            NetNestDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = (-30).dp, y = (-20).dp)
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
