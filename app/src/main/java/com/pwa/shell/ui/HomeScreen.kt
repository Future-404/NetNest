package com.pwa.shell.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageLoader
import coil.request.ImageRequest
import com.pwa.shell.data.local.PwaEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onPwaClick: (PwaEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pwas by viewModel.pwaList.collectAsState(initial = emptyList())
    val uiState by viewModel.uiState.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<PwaEntity?>(null) }
    var showManualAddDialog by remember { mutableStateOf<String?>(null) } // holds failed URL

    // Configure Coil ImageLoader with SVG support
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "NetNest Portal",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add PWA")
            }
        },
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
                        text = "No PWAs added yet.\nTap the '+' button to add your first web app!",
                        textAlign = TextAlign.Center,
                        color = Color.Gray,
                        lineHeight = 20.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(pwas, key = { _, pwa -> pwa.id }) { index, pwa ->
                        PwaGridItem(
                            pwa = pwa,
                            index = index,
                            totalItems = pwas.size,
                            imageLoader = imageLoader,
                            onClick = { onPwaClick(pwa) },
                            onDelete = { viewModel.deletePwa(pwa) },
                            onEdit = { showEditDialog = pwa },
                            onRefresh = { viewModel.refreshPwaIcon(pwa, context) },
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
                }
            }

            // Handle Global UI states
            when (val state = uiState) {
                is UiState.Loading -> {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        title = { Text("Please wait...") },
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
                                Text("Add Manually")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.resetState() }) {
                                Text("Cancel")
                            }
                        },
                        title = { Text("Error") },
                        text = { Text("${state.message}\n\nWould you like to add this PWA manually?") }
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
                    onConfirm = { name, url, theme ->
                        showManualAddDialog = null
                        viewModel.addPwaManually(name, url, "", theme)
                    }
                )
            }

            // Edit Dialog
            showEditDialog?.let { pwa ->
                EditPwaDialog(
                    pwa = pwa,
                    onDismiss = { showEditDialog = null },
                    onConfirm = { updatedName, updatedUrl, updatedTheme ->
                        showEditDialog = null
                        viewModel.updatePwa(pwa.copy(name = updatedName, url = updatedUrl, themeColor = updatedTheme))
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
    onRefresh: () -> Unit,
    onMove: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val themeColor = parseHexColor(pwa.themeColor)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { expanded = true }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Icon Container
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .padding(2.dp),
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
                        // Letter fallback with gradient backgrounds based on PWA name
                        val fallbackBrush = Brush.linearGradient(
                            colors = listOf(themeColor.copy(alpha = 0.8f), themeColor)
                        )
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = themeColor,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = pwa.name.take(1).uppercase(),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Name
                Text(
                    text = pwa.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Dropdown Menu
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = 10.dp, y = (-40).dp)
            ) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        expanded = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Refresh Icon") },
                    onClick = {
                        expanded = false
                        onRefresh()
                    }
                )
                if (index > 0) {
                    DropdownMenuItem(
                        text = { Text("Move Left") },
                        onClick = {
                            expanded = false
                            onMove(-1)
                        }
                    )
                }
                if (index < totalItems - 1) {
                    DropdownMenuItem(
                        text = { Text("Move Right") },
                        onClick = {
                            expanded = false
                            onMove(1)
                        }
                    )
                }
                Divider()
                DropdownMenuItem(
                    text = { Text("Delete", color = Color.Red) },
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
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Add New PWA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the website URL. The app will fetch PWA manifest automatically.")
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        isError = false
                    },
                    label = { Text("Website URL") },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                    isError = isError,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isError) {
                    Text("URL cannot be empty", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
    )
}

@Composable
fun ManualAddDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, themeColor: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(initialUrl) }
    var themeColor by remember { mutableStateOf("#6200EE") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        onConfirm(name, url, themeColor.takeIf { it.isNotBlank() })
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Add PWA Manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("App Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Website URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = themeColor,
                    onValueChange = { themeColor = it },
                    label = { Text("Theme Color (Hex)") },
                    placeholder = { Text("#6200EE") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

@Composable
fun EditPwaDialog(
    pwa: PwaEntity,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, themeColor: String?) -> Unit
) {
    var name by remember { mutableStateOf(pwa.name) }
    var url by remember { mutableStateOf(pwa.url) }
    var themeColor by remember { mutableStateOf(pwa.themeColor ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        onConfirm(name, url, themeColor.takeIf { it.isNotBlank() })
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Edit PWA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("App Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Website URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = themeColor,
                    onValueChange = { themeColor = it },
                    label = { Text("Theme Color (Hex)") },
                    placeholder = { Text("#6200EE") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

fun parseHexColor(hex: String?): Color {
    if (hex.isNullOrEmpty()) return Color(0xFF6200EE) // Default purple
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
