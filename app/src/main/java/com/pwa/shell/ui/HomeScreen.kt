package com.pwa.shell.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.ImageLoader
import coil.request.ImageRequest
import com.pwa.shell.data.local.PwaEntity
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onPwaClick: (PwaEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pwas by viewModel.pwaList.collectAsState(initial = emptyList())
    val uiState by viewModel.uiState.collectAsState()
    val homeScope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<PwaEntity?>(null) }
    var showManualAddDialog by remember { mutableStateOf<String?>(null) }
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
        topBar = {}, // Removed top bar for edge-to-edge immersion
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(16.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加 PWA")
            }
        },
        containerColor = Color(0xFFF5F5F3), // Light neutral warm-gray background
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (pwas.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "还没有添加网页应用。\n点击 '+' 创建您的专属网络桌面！",
                        textAlign = TextAlign.Center,
                        color = Color(0xFF7D7A76),
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 24.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        text = "NetNest v${getAppVersionName(context)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4), // 4 columns per row
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    itemsIndexed(pwas, key = { _, pwa -> pwa.id }) { index, pwa ->
                        PwaGridItem(
                            pwa = pwa,
                            index = index,
                            totalItems = pwas.size,
                            imageLoader = imageLoader,
                            onClick = { onPwaClick(pwa) },
                            onDelete = { showDeleteConfirmDialog = pwa },
                            onEdit = { showEditDialog = pwa },
                            onAddToHomeScreen = {
                                homeScope.launch {
                                    val message = when (
                                        requestPinnedPwaShortcut(context.applicationContext, pwa)
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
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            },
                            onMove = { direction ->
                                val mutablePwas = pwas.toMutableList()
                                val targetIndex = index + direction
                                if (targetIndex in mutablePwas.indices) {
                                    val temp = mutablePwas[index]
                                    mutablePwas[index] = mutablePwas[targetIndex]
                                    mutablePwas[targetIndex] = temp
                                    viewModel.reorderPwas(mutablePwas)
                                }
                            }
                        )
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
                                color = Color.Gray.copy(alpha = 0.6f)
                            )
                        }
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
                                showManualAddDialog = state.fallbackUrl
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
                    onConfirm = { url ->
                        showAddDialog = false
                        viewModel.addPwa(url, context)
                    }
                )
            }

            // Manual Add Dialog
            showManualAddDialog?.let { failedUrl ->
                ManualAddDialog(
                    initialUrl = failedUrl,
                    onDismiss = { showManualAddDialog = null },
                    onConfirm = { name, url, theme, useChromeUa, useDevConsole, useFullscreen, securityMode, trustedDomains ->
                        showManualAddDialog = null
                        viewModel.addPwaManually(name, url, "", theme, useChromeUa, useDevConsole, useFullscreen, securityMode, trustedDomains)
                    }
                )
            }

            var showScriptManagerForPwa by remember { mutableStateOf<PwaEntity?>(null) }

            // Edit Dialog
            showEditDialog?.let { pwa ->
                EditPwaDialog(
                    pwa = pwa,
                    onDismiss = { showEditDialog = null },
                    onConfirm = { updatedName, updatedUrl, updatedIconPath, updatedTheme, useChromeUa, useDevConsole, useFullscreen, securityMode, securityPromptEnabled, trustedDomains, customUserAgent, customLanguage, customPlatform, screenWidth, screenHeight, deviceScaleFactor ->
                        showEditDialog = null
                        viewModel.updatePwa(pwa.copy(
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
                            deviceScaleFactor = deviceScaleFactor
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
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmDialog = null },
                    title = { Text("确认删除网页应用？") },
                    text = { Text("您将删除网页应用 \"${pwa.name}\"。这将会清理该网站的本地存储数据（LocalStorage, Cookies 等）和图标缓存。此操作无法撤销。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deletePwa(pwa)
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
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    onMove: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val softColor = remember(pwa.url) { getSoftColor(pwa.url) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { expanded = true }
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Flat Desktop Icon Box (iOS/Android Modern Style)
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp)) // iOS Squircle style
                .background(softColor),
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
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                // Consistent soft colored placeholder showing first uppercase character
                Text(
                    text = pwa.name.take(1).uppercase(),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF49454F).copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Title Label
        Text(
            text = pwa.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = Color(0xFF333333),
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
                offset = DpOffset(x = (-30).dp, y = (-20).dp)
            ) {
                DropdownMenuItem(
                    text = { Text("编辑") },
                    onClick = {
                        expanded = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("添加到桌面") },
                    onClick = {
                        expanded = false
                        onAddToHomeScreen()
                    }
                )

                if (index > 0) {
                    DropdownMenuItem(
                        text = { Text("左移") },
                        onClick = {
                            expanded = false
                            onMove(-1)
                        }
                    )
                }
                if (index < totalItems - 1) {
                    DropdownMenuItem(
                        text = { Text("右移") },
                        onClick = {
                            expanded = false
                            onMove(1)
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("删除", color = Color.Red) },
                    onClick = {
                        expanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
fun AddPwaDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank()) {
                        onConfirm(url)
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
    )
}

@Composable
fun ManualAddDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, themeColor: String?, useChromeUa: Boolean, useDevConsole: Boolean, useFullscreen: Boolean, securityMode: Int, trustedDomains: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(initialUrl) }
    var themeColor by remember { mutableStateOf("#6200EE") }
    var useChromeUa by remember { mutableStateOf(true) }
    var useDevConsole by remember { mutableStateOf(false) }
    var useFullscreen by remember { mutableStateOf(false) }
    var isSecurityShieldEnabled by remember { mutableStateOf(true) }
    var trustedDomains by remember { mutableStateOf("") }

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
                            trustedDomains
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Text("移除 '; wv' 以防止功能退化。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                        Text("注入 vConsole 以在应用内调试控制台和存储。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                        Text("进入该 PWA 后完全隐藏系统通知栏/状态栏。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                        Text("阻止网页静默上传聊天记录或API密钥，并弹窗警告。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
    onConfirm: (name: String, url: String, iconPath: String, themeColor: String?, useChromeUa: Boolean, useDevConsole: Boolean, useFullscreen: Boolean, securityMode: Int, securityPromptEnabled: Boolean, trustedDomains: String, customUserAgent: String?, customLanguage: String, customPlatform: String, screenWidth: Int, screenHeight: Int, deviceScaleFactor: Float) -> Unit,
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
    var iconCommitted by remember { mutableStateOf(false) }
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
    val latestIconPath by rememberUpdatedState(iconPath)
    val latestIconCommitted by rememberUpdatedState(iconCommitted)
    DisposableEffect(pwa.id) {
        onDispose {
            if (!latestIconCommitted && latestIconPath != pwa.iconPath) {
                PwaIconManager.deleteManagedIcon(context, latestIconPath)
            }
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
            (deviceScaleFactor.toFloatOrNull() ?: 0f) != pwa.deviceScaleFactor

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
        iconCommitted = true
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
            deviceScaleFactor.toFloatOrNull()?.coerceIn(0.1f, 8f) ?: 0f
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
                                text = "图片将安全复制到 NetNest，仅用于该 PWA 和桌面快捷方式。",
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

private fun getAppVersionName(context: Context): String {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }
}
