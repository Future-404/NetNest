package com.pwa.shell

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.pwa.shell.data.local.PwaEntity
import com.pwa.shell.data.local.PwaFolderEntity
import com.pwa.shell.data.remote.AppUpdateChecker
import com.pwa.shell.data.remote.AppUpdateInfo
import com.pwa.shell.ui.MainViewModel
import com.pwa.shell.ui.GlobalSettingsPreferences
import com.pwa.shell.ui.ManualUpdateCheckResult
import com.pwa.shell.ui.PwaActivationSource
import com.pwa.shell.ui.PwaExternalLaunch
import com.pwa.shell.ui.PwaSessionHost
import com.pwa.shell.ui.resolveDarkTheme
import com.pwa.shell.ui.getAppVersionName
import com.pwa.shell.ui.pwaShortcutId
import com.pwa.shell.ui.theme.NetNestTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    private val pwaLaunchTargets = MutableStateFlow<PwaLaunch?>(null)
    private val memoryPressureSignals = MutableStateFlow(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pwaLaunchTargets.value = pwaLaunch(intent)
        consumePwaLaunchIntent()
        
        // Enable edge-to-edge immersion globally
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Directly instantiate the MainViewModel with application context
        val viewModel = MainViewModel(applicationContext)

        setContent {
            val settingsPreferences = remember {
                GlobalSettingsPreferences(applicationContext)
            }
            var themeMode by remember {
                mutableStateOf(settingsPreferences.loadThemeMode())
            }
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = resolveDarkTheme(themeMode, systemDarkTheme)

            NetNestTheme(
                darkTheme = darkTheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
                    var showManualUpdateDialog by remember { mutableStateOf(false) }
                    var isHomeVisible by remember { mutableStateOf(true) }
                    val updateChecker = remember { AppUpdateChecker(applicationContext) }
                    val currentAppVersion = remember {
                        getAppVersionName(applicationContext)
                    }
                    val pwas by produceState<List<PwaEntity>?>(
                        initialValue = null,
                        key1 = viewModel
                    ) {
                        viewModel.pwaList.collect { value = it }
                    }
                    val folders by produceState<List<PwaFolderEntity>?>(
                        initialValue = null,
                        key1 = viewModel
                    ) {
                        viewModel.pwaFolderList.collect { value = it }
                    }
                    val launchTarget by pwaLaunchTargets.collectAsState()
                    val memoryPressureSignal by memoryPressureSignals.collectAsState()

                    LaunchedEffect(updateChecker) {
                        availableUpdate = updateChecker.check(currentAppVersion)
                    }

                    if (pwas != null && folders != null) {
                        val loadedPwas = pwas.orEmpty()
                        PwaSessionHost(
                            viewModel = viewModel,
                            pwas = loadedPwas,
                            folders = folders.orEmpty(),
                            externalLaunch = launchTarget?.let { target ->
                                PwaExternalLaunch(
                                    pwaId = target.pwaId,
                                    notificationId = target.notificationId,
                                    source = if (target.fromShortcut) {
                                        PwaActivationSource.SHORTCUT
                                    } else {
                                        PwaActivationSource.NOTIFICATION
                                    }
                                )
                            },
                            onExternalLaunchConsumed = {
                                launchTarget
                                    ?.takeIf { it.fromShortcut }
                                    ?.let { target ->
                                        loadedPwas.firstOrNull { it.id == target.pwaId }
                                            ?.let { pwa ->
                                                runCatching {
                                                    ShortcutManagerCompat.reportShortcutUsed(
                                                        applicationContext,
                                                        pwaShortcutId(pwa.id)
                                                    )
                                                }
                                            }
                                    }
                                pwaLaunchTargets.value = null
                            },
                            memoryPressureSignal = memoryPressureSignal,
                            onHomeVisibilityChanged = { isHomeVisible = it },
                            onShortcutMissing = {
                                Toast.makeText(
                                    applicationContext,
                                    "该网页应用已被删除",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            themeMode = themeMode,
                            darkTheme = darkTheme,
                            onThemeModeChanged = { selectedMode ->
                                themeMode = selectedMode
                                settingsPreferences.saveThemeMode(selectedMode)
                            },
                            onCheckForUpdates = {
                                updateChecker.checkNow(currentAppVersion).fold(
                                    onSuccess = { update ->
                                        if (update == null) {
                                            ManualUpdateCheckResult.UpToDate
                                        } else {
                                            availableUpdate = update
                                            showManualUpdateDialog = true
                                            ManualUpdateCheckResult.UpdateAvailable
                                        }
                                    },
                                    onFailure = {
                                        ManualUpdateCheckResult.Failed(
                                            "检查更新失败，请确认网络连接后重试"
                                        )
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (isHomeVisible || showManualUpdateDialog) {
                        availableUpdate?.let { update ->
                            AppUpdateDialog(
                                update = update,
                                currentVersion = currentAppVersion,
                                onDismiss = {
                                    updateChecker.snooze(update.versionName)
                                    availableUpdate = null
                                    showManualUpdateDialog = false
                                },
                                onDownload = {
                                    updateChecker.snooze(update.versionName)
                                    val opened = runCatching {
                                        startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))
                                        )
                                    }.isSuccess
                                    val fallbackOpened = opened || (
                                        update.downloadUrl != update.releasePageUrl &&
                                            runCatching {
                                                startActivity(
                                                    Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(update.releasePageUrl)
                                                    )
                                                )
                                            }.isSuccess
                                        )
                                    if (!fallbackOpened) {
                                        Toast.makeText(
                                            applicationContext,
                                            "无法打开更新链接",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    availableUpdate = null
                                    showManualUpdateDialog = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pwaLaunchTargets.value = pwaLaunch(intent)
        consumePwaLaunchIntent()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val underPressure =
            level == TRIM_MEMORY_RUNNING_LOW ||
                level == TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= TRIM_MEMORY_BACKGROUND
        if (underPressure) {
            memoryPressureSignals.value = SystemClock.elapsedRealtime()
        }
    }

    private fun pwaLaunch(intent: Intent?): PwaLaunch? {
        val action = intent?.action
        if (action != ACTION_OPEN_PWA_NOTIFICATION && action != ACTION_OPEN_PWA_SHORTCUT) {
            return null
        }
        val pwaId = intent.getLongExtra(EXTRA_PWA_ID, -1L).takeIf { it > 0L }
            ?: return null
        return PwaLaunch(
            pwaId = pwaId,
            notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID),
            fromShortcut = action == ACTION_OPEN_PWA_SHORTCUT
        )
    }

    private fun consumePwaLaunchIntent() {
        val consumedIntent = intent ?: return
        if (
            consumedIntent.action != ACTION_OPEN_PWA_NOTIFICATION &&
            consumedIntent.action != ACTION_OPEN_PWA_SHORTCUT
        ) return
        setIntent(Intent(consumedIntent).apply {
            action = Intent.ACTION_MAIN
            data = null
            removeExtra(EXTRA_PWA_ID)
            removeExtra(EXTRA_NOTIFICATION_ID)
        })
    }

    companion object {
        const val ACTION_OPEN_PWA_NOTIFICATION =
            "com.pwa.shell.action.OPEN_PWA_NOTIFICATION"
        const val ACTION_OPEN_PWA_SHORTCUT =
            "com.pwa.shell.action.OPEN_PWA_SHORTCUT"
        const val EXTRA_PWA_ID = "pwa_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}

@Composable
private fun AppUpdateDialog(
    update: AppUpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${update.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前版本：$currentVersion")
                if (update.releaseNotes.isNotBlank()) {
                    Text(
                        text = update.releaseNotes,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = if (update.hasDirectApk) {
                        "将使用系统浏览器下载 APK，安装时由 Android 再次确认。"
                    } else {
                        "该发行版没有直接 APK，将打开 GitHub 发行页面。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(if (update.hasDirectApk) "下载更新" else "查看发行版")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后提醒")
            }
        }
    )
}

private data class PwaLaunch(
    val pwaId: Long,
    val notificationId: String?,
    val fromShortcut: Boolean
)
