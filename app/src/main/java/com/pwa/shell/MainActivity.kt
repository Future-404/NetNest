package com.pwa.shell

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.pwa.shell.data.local.PwaEntity
import com.pwa.shell.ui.HomeScreen
import com.pwa.shell.ui.MainViewModel
import com.pwa.shell.ui.PwaWebViewScreen
import com.pwa.shell.ui.pwaShortcutId
import com.pwa.shell.ui.theme.NetNestTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private val pwaLaunchTargets = MutableStateFlow<PwaLaunch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pwaLaunchTargets.value = pwaLaunch(intent)
        consumePwaLaunchIntent()
        
        // Enable edge-to-edge immersion globally
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Directly instantiate the MainViewModel with application context
        val viewModel = MainViewModel(applicationContext)

        setContent {
            NetNestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                    LaunchedEffect(viewModel) {
                        pwaLaunchTargets.filterNotNull().collect { target ->
                            val pwa = viewModel.pwaList.first()
                                .firstOrNull { it.id == target.pwaId }
                            if (pwa != null) {
                                if (target.fromShortcut) {
                                    runCatching {
                                        ShortcutManagerCompat.reportShortcutUsed(
                                            applicationContext,
                                            pwaShortcutId(pwa.id)
                                        )
                                    }
                                }
                                currentScreen = Screen.WebView(
                                    pwa = pwa,
                                    notificationId = target.notificationId
                                )
                            } else if (target.fromShortcut) {
                                currentScreen = Screen.Home
                                Toast.makeText(
                                    applicationContext,
                                    "该网页应用已被删除",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            pwaLaunchTargets.value = null
                        }
                    }

                    when (val screen = currentScreen) {
                        is Screen.Home -> {
                            // Apply status bars padding globally in MainActivity to avoid top overlap
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.statusBars)
                            ) {
                                HomeScreen(
                                    viewModel = viewModel,
                                    onPwaClick = { pwa ->
                                        currentScreen = Screen.WebView(pwa)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        is Screen.WebView -> {
                            key(screen.pwa.id) {
                                PwaWebViewScreen(
                                    pwa = screen.pwa,
                                    notificationClickId = screen.notificationId,
                                    onNotificationClickConsumed = {
                                        currentScreen = screen.copy(notificationId = null)
                                    },
                                    onBackToHome = {
                                        currentScreen = Screen.Home
                                    },
                                    onUpdatePwa = { updatedPwa ->
                                        viewModel.updatePwa(updatedPwa)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
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

private data class PwaLaunch(
    val pwaId: Long,
    val notificationId: String?,
    val fromShortcut: Boolean
)

sealed interface Screen {
    object Home : Screen
    data class WebView(
        val pwa: PwaEntity,
        val notificationId: String? = null
    ) : Screen
}
