package com.pwa.shell.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pwa.shell.data.local.ImportSource
import com.pwa.shell.data.local.RunAt
import com.pwa.shell.data.local.UserScriptEntity
import com.pwa.shell.data.local.PwaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UserScriptManagerScreen(
    pwa: PwaEntity,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onTestRun: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val userScriptDao = viewModel.userScriptDao

    // Scripts State observed from Database
    val scriptsFlow = remember(pwa.id) { userScriptDao.getScriptsForPwaFlow(pwa.id) }
    val scripts by scriptsFlow.collectAsState(initial = emptyList())

    // Active Editor State
    var editingScript by remember { mutableStateOf<UserScriptEntity?>(null) }

    // Dialog & Options States
    var showImportOptions by remember { mutableStateOf(false) }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var parsedImportScript by remember { mutableStateOf<ParsedScript?>(null) }
    var importSource by remember { mutableStateOf(ImportSource.CLIPBOARD) }

    var showDeleteConfirm by remember { mutableStateOf<UserScriptEntity?>(null) }
    var showOverwriteConfirm by remember { mutableStateOf<UserScriptEntity?>(null) }
    var showScriptMenu by remember { mutableStateOf<UserScriptEntity?>(null) }

    // Selected Script for details expansion
    var expandedScriptId by remember { mutableStateOf<Long?>(null) }

    val clipboardManager = LocalClipboardManager.current

    // File selection launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val stringBuilder = StringBuilder()
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                stringBuilder.append(line).append("\n")
                            }
                        }
                    }
                    val content = stringBuilder.toString()
                    withContext(Dispatchers.Main) {
                        importSource = ImportSource.FILE
                        pendingImportText = content
                        parsedImportScript = parseUserScriptMeta(content)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    if (editingScript != null) {
        ScriptFullScreenEditor(
            initial = editingScript!!,
            onSave = { updated ->
                runCatching {
                    userScriptDao.update(updated)
                    editingScript = null
                }
            },
            onCancel = { editingScript = null },
            onTestRun = onTestRun
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("脚本管理 - ${pwa.name}") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Button(onClick = { showImportOptions = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导入脚本")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (scripts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("暂无脚本。点击右上角导入脚本！", color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(scripts, key = { _, s -> s.id }) { index, script ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .combinedClickable(
                                    onClick = {
                                        expandedScriptId = if (expandedScriptId == script.id) null else script.id
                                    },
                                    onLongClick = {
                                        showScriptMenu = script
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (script.enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = script.name,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "${script.matchPatterns.size} 条匹配规则",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Sort order buttons
                                        IconButton(
                                            enabled = index > 0,
                                            onClick = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    swapScriptsOrder(userScriptDao, scripts, index, index - 1)
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
                                        }

                                        IconButton(
                                            enabled = index < scripts.size - 1,
                                            onClick = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    swapScriptsOrder(userScriptDao, scripts, index, index + 1)
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
                                        }

                                        Switch(
                                            checked = script.enabled,
                                            onCheckedChange = { isEnabled ->
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    userScriptDao.toggleEnabled(script.id, isEnabled)
                                                }
                                            }
                                        )
                                    }
                                }

                                AnimatedVisibility(visible = expandedScriptId == script.id) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                    ) {
                                        HorizontalDivider()
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("运行时机: ${script.runAt}", style = MaterialTheme.typography.bodyMedium)
                                        Text("导入来源: ${script.importSource}", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "更新时间: ${
                                                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                                    .format(java.util.Date(script.updatedAt))
                                            }",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("匹配规则列表:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                        script.matchPatterns.forEach { pattern ->
                                            Text(
                                                text = " • $pattern",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "⚠️ 该脚本可读写本 PWA 下共享的存储空间",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet Options
    if (showImportOptions) {
        AlertDialog(
            onDismissRequest = { showImportOptions = false },
            title = { Text("导入用户脚本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("请选择您的脚本导入来源。NetNest 兼容标准的油猴脚本元数据格式。")
                    Button(
                        onClick = {
                            showImportOptions = false
                            val clipboardText = clipboardManager.getText()?.text
                            if (clipboardText.isNullOrBlank()) {
                                Toast.makeText(context, "剪贴板为空！", Toast.LENGTH_SHORT).show()
                            } else {
                                importSource = ImportSource.CLIPBOARD
                                pendingImportText = clipboardText
                                parsedImportScript = parseUserScriptMeta(clipboardText)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("从剪贴板导入")
                    }

                    Button(
                        onClick = {
                            showImportOptions = false
                            filePickerLauncher.launch(arrayOf("text/javascript", "application/javascript", "text/plain"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("从本地文件导入 (.js)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportOptions = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Import Preview Dialog
    if (parsedImportScript != null && pendingImportText != null) {
        val parsed = parsedImportScript!!
        val namePlaceholder = parsed.name ?: "未命名脚本_${System.currentTimeMillis()}"
        val finalMatches = parsed.matchPatterns ?: listOf("*")
        val finalRunAt = parsed.runAt ?: RunAt.DOCUMENT_END

        AlertDialog(
            onDismissRequest = {
                parsedImportScript = null
                pendingImportText = null
            },
            title = { Text("📥 导入脚本确认") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text("请核对即将导入的脚本元数据信息：", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = namePlaceholder,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("脚本名称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("匹配域名规则:", fontWeight = FontWeight.SemiBold)
                    finalMatches.forEach {
                        Text(" • $it", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("注入时机: $finalRunAt")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("代码预览 (只读):", fontWeight = FontWeight.SemiBold)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(Color.Black)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = parsed.code,
                            color = Color.Green,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val rawText = pendingImportText!!
                        coroutineScope.launch(Dispatchers.IO) {
                            val existing = userScriptDao.getScriptByName(pwa.id, namePlaceholder)
                            withContext(Dispatchers.Main) {
                                if (existing != null) {
                                    showOverwriteConfirm = existing
                                } else {
                                    // Save new
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val maxSort = scripts.maxOfOrNull { it.sortOrder } ?: 0
                                        userScriptDao.insert(
                                            UserScriptEntity(
                                                pwaId = pwa.id,
                                                name = namePlaceholder,
                                                enabled = true,
                                                matchPatterns = finalMatches,
                                                runAt = finalRunAt,
                                                code = parsed.code,
                                                rawSource = rawText,
                                                importSource = importSource,
                                                sortOrder = maxSort + 1
                                            )
                                        )
                                    }
                                    Toast.makeText(context, "导入成功！", Toast.LENGTH_SHORT).show()
                                    parsedImportScript = null
                                    pendingImportText = null
                                }
                            }
                        }
                    }
                ) {
                    Text("确认导入")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        parsedImportScript = null
                        pendingImportText = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    // Overwrite Confirmation Dialog
    if (showOverwriteConfirm != null) {
        val existing = showOverwriteConfirm!!
        val parsed = parsedImportScript!!
        AlertDialog(
            onDismissRequest = { showOverwriteConfirm = null },
            title = { Text("⚠️ 同名脚本覆盖确认") },
            text = {
                Text("已存在名为 \"${existing.name}\" 的自定义脚本。是否覆盖它？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            userScriptDao.update(
                                existing.copy(
                                    code = parsed.code,
                                    rawSource = pendingImportText!!,
                                    matchPatterns = parsed.matchPatterns ?: existing.matchPatterns,
                                    runAt = parsed.runAt ?: existing.runAt,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                        Toast.makeText(context, "覆盖成功！", Toast.LENGTH_SHORT).show()
                        showOverwriteConfirm = null
                        parsedImportScript = null
                        pendingImportText = null
                    }
                ) {
                    Text("确认覆盖", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverwriteConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Long Press Context Menu Dialog
    if (showScriptMenu != null) {
        val script = showScriptMenu!!
        AlertDialog(
            onDismissRequest = { showScriptMenu = null },
            title = { Text(script.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showScriptMenu = null
                            editingScript = script
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("📝 修改源码")
                    }

                    Button(
                        onClick = {
                            showScriptMenu = null
                            showDeleteConfirm = script
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🗑 删除脚本")
                    }

                    Button(
                        onClick = {
                            showScriptMenu = null
                            clipboardManager.setText(AnnotatedString(script.rawSource))
                            Toast.makeText(context, "源码已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("📋 复制原文")
                    }

                    if (onTestRun != null) {
                        Button(
                            onClick = {
                                showScriptMenu = null
                                onTestRun(script.code)
                                Toast.makeText(context, "已手动注入运行该脚本", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("▶️ 立即测试运行")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showScriptMenu = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Delete Confirmation
    if (showDeleteConfirm != null) {
        val script = showDeleteConfirm!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("⚠️ 删除脚本确认") },
            text = {
                Text("确定要删除脚本 \"${script.name}\" 吗？此操作无法撤销。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            userScriptDao.delete(script)
                        }
                        Toast.makeText(context, "脚本已删除", Toast.LENGTH_SHORT).show()
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptFullScreenEditor(
    initial: UserScriptEntity,
    onSave: suspend (UserScriptEntity) -> Result<Unit>,
    onCancel: () -> Unit,
    onTestRun: ((String) -> Unit)? = null
) {
    var code by remember { mutableStateOf(initial.rawSource) }
    var name by remember { mutableStateOf(initial.name) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var isLogPanelExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "取消")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            val updatedRaw = updateMetaName(code, name)
                            val parsed = parseUserScriptMeta(updatedRaw)
                            val updated = initial.copy(
                                name = name,
                                code = parsed.code,
                                rawSource = updatedRaw,
                                matchPatterns = parsed.matchPatterns ?: initial.matchPatterns,
                                runAt = parsed.runAt ?: initial.runAt,
                                updatedAt = System.currentTimeMillis()
                            )
                            val res = onSave(updated)
                            res.onSuccess {
                                saveError = null
                            }
                            res.onFailure {
                                saveError = "保存失败：可能存在同名脚本，请修改名称"
                            }
                        }
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "保存")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize
                    ),
                    modifier = Modifier.fillMaxSize(),
                    placeholder = { Text("// ==UserScript==\n// Write your script here...") }
                )
            }

            saveError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Interactive Bottom Log Panel & Test Run Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { isLogPanelExpanded = !isLogPanelExpanded }
                    ) {
                        Icon(
                            if (isLogPanelExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "运行日志 (${ScriptLogCollector.logs.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row {
                        if (onTestRun != null) {
                            Button(
                                onClick = {
                                    val parsed = parseUserScriptMeta(code)
                                    onTestRun(parsed.code)
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("立即测试")
                            }
                        }

                        TextButton(onClick = { ScriptLogCollector.clear() }) {
                            Text("清空")
                        }
                    }
                }

                AnimatedVisibility(visible = isLogPanelExpanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color(0xFF1E1E1E))
                    ) {
                        if (ScriptLogCollector.logs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("暂无日志记录", color = Color.Gray)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                            ) {
                                items(ScriptLogCollector.logs.toList()) { log ->
                                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                        .format(java.util.Date(log.timestamp))
                                    val color = when (log.level.lowercase()) {
                                        "error" -> Color.Red
                                        "warn" -> Color(0xFFFFC107)
                                        else -> Color.Green
                                    }
                                    Text(
                                        text = "[$time] [${log.level.uppercase()}] ${log.message}",
                                        color = color,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun swapScriptsOrder(dao: com.pwa.shell.data.local.UserScriptDao, scripts: List<UserScriptEntity>, index1: Int, index2: Int) {
    val list = scripts.toMutableList()
    val item1 = list[index1]
    val item2 = list[index2]
    val tempOrder = item1.sortOrder
    list[index1] = item2.copy(sortOrder = tempOrder)
    list[index2] = item1.copy(sortOrder = item2.sortOrder)
    dao.updateSortOrders(list)
}

fun updateMetaName(raw: String, newName: String): String {
    val headerRegex = Regex("==UserScript==([\\s\\S]*?)==/UserScript==")
    val matchResult = headerRegex.find(raw) ?: return raw
    val headerContent = matchResult.groupValues[1]
    val nameRegex = Regex("(@name\\s+).*")
    val updatedHeaderContent = if (nameRegex.containsMatchIn(headerContent)) {
        nameRegex.replace(headerContent) { match ->
            "${match.groupValues[1]}$newName"
        }
    } else {
        "\n// @name         $newName$headerContent"
    }
    val originalHeader = matchResult.value
    val newHeader = "==UserScript==" + updatedHeaderContent + "==/UserScript=="
    return raw.replace(originalHeader, newHeader)
}
