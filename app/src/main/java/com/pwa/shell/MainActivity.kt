package com.pwa.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.pwa.shell.data.local.PwaEntity
import com.pwa.shell.ui.HomeScreen
import com.pwa.shell.ui.MainViewModel
import com.pwa.shell.ui.PwaWebViewScreen
import com.pwa.shell.ui.theme.NetNestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
                            PwaWebViewScreen(
                                pwa = screen.pwa,
                                onBackToHome = {
                                    currentScreen = Screen.Home
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

sealed interface Screen {
    object Home : Screen
    data class WebView(val pwa: PwaEntity) : Screen
}
