package com.pwa.shell.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pwa.shell.data.local.PwaEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun <T> putBoundedNavigationState(
    states: LinkedHashMap<Long, T>,
    pwaId: Long,
    state: T,
    maxStates: Int
) {
    require(maxStates > 0) { "maxStates must be positive" }
    states.remove(pwaId)
    states[pwaId] = state
    while (states.size > maxStates) {
        states.remove(states.keys.first())
    }
}

data class PwaExternalLaunch(
    val pwaId: Long,
    val notificationId: String?,
    val source: PwaActivationSource
)

@Composable
fun PwaSessionHost(
    viewModel: MainViewModel,
    pwas: List<PwaEntity>,
    externalLaunch: PwaExternalLaunch?,
    onExternalLaunchConsumed: () -> Unit,
    memoryPressureSignal: Long,
    onHomeVisibilityChanged: (Boolean) -> Unit,
    onShortcutMissing: () -> Unit,
    themeMode: AppThemeMode,
    darkTheme: Boolean,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onCheckForUpdates: suspend () -> ManualUpdateCheckResult,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val preferences = remember { PwaSwitcherPreferences(context) }
    val globalSettingsPreferences = remember { GlobalSettingsPreferences(context) }
    val manager = remember {
        PwaSessionManager(preferences.loadRecentPwaIds())
    }
    var revision by remember { mutableIntStateOf(0) }
    var placement by remember { mutableStateOf(preferences.loadPlacement()) }
    var drawerOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var blockedMessageNonce by remember { mutableLongStateOf(0L) }
    val webViews = remember { mutableStateMapOf<Long, WebView>() }
    val savedNavigationStates = remember { linkedMapOf<Long, Bundle>() }
    val switchLocks = remember { mutableStateMapOf<Long, Boolean>() }
    val notificationClicks = remember { mutableStateMapOf<Long, String>() }
    val snackbarHostState = remember { SnackbarHostState() }
    var backgroundCleanupJob by remember { mutableStateOf<Job?>(null) }

    val snapshot = remember(revision) { manager.snapshot() }
    val pwasById = remember(pwas) { pwas.associateBy(PwaEntity::id) }
    val activePwa = snapshot.activePwaId?.let(pwasById::get)
    val isHome = activePwa == null && !settingsOpen
    val isNativeScreen = activePwa == null

    fun saveNavigationState(pwaId: Long) {
        val state = Bundle()
        val saved = runCatching { webViews[pwaId]?.saveState(state) }.getOrNull()
        if (saved != null) {
            putBoundedNavigationState(
                states = savedNavigationStates,
                pwaId = pwaId,
                state = state,
                maxStates = MAX_SAVED_NAVIGATION_STATES
            )
        }
    }

    fun applyRemoval(removedIds: Set<Long>, saveState: Boolean = true) {
        removedIds.forEach { pwaId ->
            if (saveState) saveNavigationState(pwaId)
            switchLocks.remove(pwaId)
        }
        if (removedIds.isNotEmpty()) revision++
    }

    fun persistState() {
        preferences.saveRecentPwaIds(manager.snapshot().recentPwaIds)
    }

    fun activate(pwaId: Long, source: PwaActivationSource): Boolean {
        val beforeActivation = manager.snapshot()
        val currentId = beforeActivation.activePwaId
        if (currentId != null && currentId != pwaId && switchLocks[currentId] == true) {
            blockedMessageNonce++
            return false
        }
        if (
            beforeActivation.liveSessions.none { it.pwaId == pwaId } &&
            beforeActivation.liveSessions.size >= PwaSessionManager.MAX_LIVE_SESSIONS
        ) {
            beforeActivation.liveSessions
                .filter { it.phase == PwaSessionPhase.PENDING_CLOSE }
                .forEach {
                    saveNavigationState(it.pwaId)
                    manager.finalizePendingClose(it.pwaId)
                }
        }
        val evicted = manager.activate(pwaId, source, SystemClock.elapsedRealtime())
        applyRemoval(evicted)
        revision++
        settingsOpen = false
        drawerOpen = false
        persistState()
        return true
    }

    fun goHome() {
        val currentId = manager.snapshot().activePwaId
        if (currentId != null && switchLocks[currentId] == true) {
            blockedMessageNonce++
            return
        }
        manager.goHome(SystemClock.elapsedRealtime())
        revision++
        settingsOpen = false
        drawerOpen = false
        persistState()
    }

    LaunchedEffect(blockedMessageNonce) {
        if (blockedMessageNonce > 0L) {
            snackbarHostState.showSnackbar("请先处理当前请求")
        }
    }

    LaunchedEffect(pwasById.keys) {
        val removed = manager.reconcile(pwasById.keys)
        applyRemoval(removed, saveState = false)
        savedNavigationStates.keys.retainAll(pwasById.keys)
        persistState()
    }

    LaunchedEffect(externalLaunch, pwasById, switchLocks.toMap()) {
        val launch = externalLaunch ?: return@LaunchedEffect
        val pwa = pwasById[launch.pwaId]
        if (pwa != null) {
            if (!activate(pwa.id, launch.source)) return@LaunchedEffect
            launch.notificationId?.let { notificationClicks[pwa.id] = it }
        } else if (launch.source == PwaActivationSource.SHORTCUT) {
            goHome()
            onShortcutMissing()
        }
        onExternalLaunchConsumed()
    }

    LaunchedEffect(isHome) {
        onHomeVisibilityChanged(isHome)
    }

    LaunchedEffect(isNativeScreen, darkTheme) {
        if (isNativeScreen) {
            val window = context.findHostActivity()?.window ?: return@LaunchedEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = WindowCompat.getInsetsController(window, rootView)
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.isAppearanceLightStatusBars = !darkTheme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attributes = window.attributes
                attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                window.attributes = attributes
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    manager.onAppBackgrounded(SystemClock.elapsedRealtime())
                    backgroundCleanupJob?.cancel()
                    backgroundCleanupJob = scope.launch {
                        delay(PwaSessionManager.BACKGROUND_WARM_TIMEOUT_MS)
                        val removed = manager.onAppForegrounded(SystemClock.elapsedRealtime())
                        applyRemoval(removed)
                    }
                }
                Lifecycle.Event.ON_START -> {
                    backgroundCleanupJob?.cancel()
                    backgroundCleanupJob = null
                    val removed = manager.onAppForegrounded(SystemClock.elapsedRealtime())
                    applyRemoval(removed)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            backgroundCleanupJob?.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            applyRemoval(manager.evictIdleWarmSessions(SystemClock.elapsedRealtime()))
        }
    }

    LaunchedEffect(memoryPressureSignal) {
        if (memoryPressureSignal > 0L) {
            applyRemoval(manager.onMemoryPressure())
        }
    }

    LaunchedEffect(activePwa?.showSwitcherHandle) {
        if (activePwa?.showSwitcherHandle != true) drawerOpen = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        val liveEntries = snapshot.liveSessions.sortedBy {
            if (it.pwaId == snapshot.activePwaId) 1 else 0
        }
        liveEntries.forEach { entry ->
            val pwa = pwasById[entry.pwaId] ?: return@forEach
            val active = entry.pwaId == snapshot.activePwaId
            key(entry.pwaId) {
                PwaWebViewScreen(
                    pwa = pwa,
                    isActive = active,
                    initialSavedState = savedNavigationStates.remove(entry.pwaId),
                    onWebViewChanged = { webView ->
                        if (webView == null) webViews.remove(entry.pwaId)
                        else webViews[entry.pwaId] = webView
                    },
                    onSwitchBlockedChanged = { blocked ->
                        if (blocked) switchLocks[entry.pwaId] = true
                        else switchLocks.remove(entry.pwaId)
                    },
                    onAttentionChanged = { attention ->
                        manager.markAttention(entry.pwaId, attention)
                        revision++
                    },
                    onRendererGone = { pwaId ->
                        savedNavigationStates.remove(pwaId)
                        manager.invalidate(pwaId)
                        revision++
                        scope.launch {
                            snackbarHostState.showSnackbar("网页进程已退出，下次打开会重新加载")
                        }
                    },
                    notificationClickId = notificationClicks[entry.pwaId],
                    onNotificationClickConsumed = {
                        notificationClicks.remove(entry.pwaId)
                    },
                    onBackToHome = ::goHome,
                    onUpdatePwa = viewModel::updatePwa,
                    onCompatibilityFallbackUsed = { pwaId ->
                        viewModel.setUsedSharedCompatibility(pwaId, true)
                    },
                    onIsolationActivationAcknowledged = { pwaId ->
                        viewModel.setUsedSharedCompatibility(pwaId, false)
                    },
                    onWebViewDisposed = viewModel::retryPendingWebProfileDeletions,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (active) 1f else 0f)
                        .graphicsLayer { alpha = if (active) 1f else 0f }
                )
            }
        }

        if (isNativeScreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                if (settingsOpen) {
                    SettingsScreen(
                        themeMode = themeMode,
                        onThemeModeChanged = onThemeModeChanged,
                        onCheckForUpdates = onCheckForUpdates,
                        onBack = { settingsOpen = false },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    HomeScreen(
                        viewModel = viewModel,
                        settingsTileIndex = normalizeSettingsTileIndex(
                            globalSettingsPreferences.loadSettingsTileIndex(),
                            pwas.size
                        ),
                        onSettingsTileIndexChanged = { index ->
                            globalSettingsPreferences.saveSettingsTileIndex(index)
                        },
                        addAppTileIndex = normalizeSystemTileIndex(
                            globalSettingsPreferences.loadAddAppTileIndex(),
                            pwas.size
                        ),
                        onAddAppTileIndexChanged = { index ->
                            globalSettingsPreferences.saveAddAppTileIndex(index)
                        },
                        onSettingsClick = { settingsOpen = true },
                        onPwaClick = { activate(it.id, PwaActivationSource.HOME) },
                        onPwaUpdate = { previous, updated ->
                            if (requiresWebSessionRestart(previous, updated)) {
                                saveNavigationState(previous.id)
                                manager.invalidate(previous.id)
                                savedNavigationStates.remove(previous.id)
                                revision++
                            }
                            viewModel.updatePwa(updated)
                        },
                        onPwaDelete = { pwa ->
                            manager.removePwa(pwa.id)
                            savedNavigationStates.remove(pwa.id)
                            revision++
                            persistState()
                            scope.launch {
                                withFrameNanos { }
                                viewModel.deletePwa(pwa)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (activePwa?.showSwitcherHandle == true) {
            PwaSwitcherOverlay(
                currentPwa = activePwa,
                placement = placement,
                drawerOpen = drawerOpen,
                drawerPwas = snapshot.recentPwaIds
                    .asSequence()
                    .filter { it != activePwa.id }
                    .mapNotNull(pwasById::get)
                    .take(4)
                    .toList(),
                livePwaIds = snapshot.liveSessions
                    .filter { it.phase != PwaSessionPhase.PENDING_CLOSE }
                    .map { it.pwaId }
                    .toSet(),
                attentionPwaIds = snapshot.liveSessions
                    .filter { it.phase == PwaSessionPhase.ATTENTION }
                    .map { it.pwaId }
                    .toSet(),
                onDrawerOpenChange = { drawerOpen = it },
                onPlacementChange = {
                    placement = it.normalized()
                },
                onPlacementChangeFinished = {
                    val normalized = it.normalized()
                    placement = normalized
                    preferences.savePlacement(normalized)
                },
                onGestureStart = { manager.beginGesture() },
                onGestureSwitch = { direction ->
                    if (switchLocks[activePwa.id] == true) {
                        blockedMessageNonce++
                    } else {
                        manager.gestureTarget(direction)?.let {
                            activate(it, PwaActivationSource.GESTURE)
                        }
                    }
                },
                onPwaSelected = { activate(it.id, PwaActivationSource.DRAWER) },
                onHomeSelected = ::goHome,
                onCloseWarmPwa = { pwa ->
                    if (manager.beginPendingClose(pwa.id)) {
                        revision++
                        persistState()
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            val result = withTimeoutOrNull(5_000L) {
                                snackbarHostState.showSnackbar(
                                    message = "已关闭 ${pwa.name} 的后台页面",
                                    actionLabel = "撤销",
                                    duration = SnackbarDuration.Indefinite
                                )
                            }
                            if (result ==
                                androidx.compose.material3.SnackbarResult.ActionPerformed
                            ) {
                                manager.undoPendingClose(pwa.id)
                            } else {
                                saveNavigationState(pwa.id)
                                manager.finalizePendingClose(pwa.id)
                            }
                            revision++
                            persistState()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(6f)
        )
    }
}

private const val MAX_SAVED_NAVIGATION_STATES = PwaSessionManager.MAX_RECENT_PWAS

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
