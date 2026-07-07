package com.pwa.shell.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.*
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import com.pwa.shell.data.local.PwaEntity
import java.io.File

@Composable
fun PwaWebViewScreen(
    pwa: PwaEntity,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    var webView: WebView? by remember { mutableStateOf(null) }
    LaunchedEffect(pwa.useFullscreen) {
        val activity = context as? Activity
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (pwa.useFullscreen) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    // File Chooser Callback for <input type="file">
    var uploadMessageCallback: ValueCallback<Array<Uri>>? by remember { mutableStateOf(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val dataUri = result.data?.data
            val clipData = result.data?.clipData
            val results = when {
                dataUri != null -> arrayOf(dataUri)
                clipData != null && clipData.itemCount > 0 -> {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                }
                else -> null
            }
            uploadMessageCallback?.onReceiveValue(results)
        } else {
            uploadMessageCallback?.onReceiveValue(null)
        }
        uploadMessageCallback = null
    }

    // System Back Button Handling
    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBackToHome()
        }
    }

    // Immersive status bar control
    DisposableEffect(pwa) {
        val activity = context as? Activity
        val window = activity?.window
        if (window != null && !pwa.useFullscreen) {
            // Keep edge-to-edge layout false
            WindowCompat.setDecorFitsSystemWindows(window, false)
            
            // Set status bar background color
            val pwaColor = pwa.themeColor?.let { parseHexColor(it) } ?: Color.Transparent
            window.statusBarColor = pwaColor.toArgb()
            
            // Adjust status bar text color based on luminance
            val isLight = isColorLight(pwaColor)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isLight
        }

        onDispose {
            window?.let { w ->
                // Restore default transparent system bars
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                val controller = WindowCompat.getInsetsController(w, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    // Full screen web container
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Enable remote web debugging for developer diagnostic profiling
                    WebView.setWebContentsDebuggingEnabled(true)

                    // Accept cookies & third party cookies configuration
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    configureSettings(this, pwa.useChromeUa)
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Flush cookies immediately to ensure persistence
                            CookieManager.getInstance().flush()

                             // Dynamically update status bar color based on webpage theme color or body background
                            if (view != null) {
                                updateStatusBarFromWeb(view, pwa.useFullscreen)
                                // Delayed check for SPA dynamic rendering/hydration
                                view.postDelayed({ updateStatusBarFromWeb(view, pwa.useFullscreen) }, 500)
                            }

                            // Dynamically inject Tencent vConsole in-app debugger if enabled
                            if (pwa.useDevConsole) {
                                val ctx = view?.context
                                if (ctx != null) {
                                    val vConsoleJs = getAssetFileString(ctx, "vconsole.min.js")
                                    if (vConsoleJs.isNotEmpty()) {
                                        val injectScript = """
                                            $vConsoleJs
                                            (function() {
                                                try {
                                                    if (!window.vConsoleInstance && (window.vConsole || window.VConsole)) {
                                                        window.vConsoleInstance = new window.VConsole();
                                                    }
                                                } catch(e) {}
                                            })();
                                        """.trimIndent()
                                        view.evaluateJavascript(injectScript, null)
                                    }
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            Log.e(
                                "WebViewError",
                                "Failed to load resource: ${request?.url} Error: ${error?.description}"
                            )
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            Log.e(
                                "WebViewError",
                                "HTTP error ${errorResponse?.statusCode} for ${request?.url}: ${errorResponse?.reasonPhrase}"
                            )
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            return handleUrlRedirection(view, url)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            url: String?
                        ): Boolean {
                            if (url == null) return false
                            return handleUrlRedirection(view, url)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            Log.d(
                                "WebViewConsole",
                                "${consoleMessage?.message()} (at ${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})"
                            )
                            return true
                        }

                        // Forward window.open popup requests back to the primary WebView
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            val mainWebView = view ?: return false
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            if (transport != null) {
                                transport.webView = mainWebView
                                resultMsg.sendToTarget()
                                return true
                            }
                            return false
                        }

                        // Web API Permission request bridge (Camera, Mic, Location)
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            val resources = request?.resources ?: return
                            request.grant(resources)
                        }

                        // Input type="file" callback launcher
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            uploadMessageCallback?.onReceiveValue(null)
                            uploadMessageCallback = filePathCallback

                            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "*/*"
                                addCategory(Intent.CATEGORY_OPENABLE)
                            }
                            try {
                                fileChooserLauncher.launch(intent)
                            } catch (e: Exception) {
                                uploadMessageCallback?.onReceiveValue(null)
                                uploadMessageCallback = null
                                return false
                            }
                            return true
                        }
                    }

                    loadUrl(pwa.url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureSettings(webView: WebView, useChromeUa: Boolean) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true) // Required for window.open popups
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        allowFileAccess = true
        allowContentAccess = true
        cacheMode = WebSettings.LOAD_DEFAULT
        mediaPlaybackRequiresUserGesture = false
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false

        // Custom User-Agent cleaner logic
        val defaultUa = WebSettings.getDefaultUserAgent(webView.context)
        if (useChromeUa && defaultUa.isNotEmpty()) {
            // Strip WebView signature '; wv' and version code to masquerade as standard mobile Chrome
            val cleanedUa = defaultUa.replace("Version/4.0 ", "").replace("; wv", "")
            userAgentString = cleanedUa
        }
    }
}

private fun handleUrlRedirection(view: WebView?, url: String): Boolean {
    if (url.startsWith("http://") || url.startsWith("https://")) {
        return false // Handled natively inside the WebView
    }
    
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        view?.context?.startActivity(intent)
        return true
    } catch (e: Exception) {
        Toast.makeText(view?.context, "未找到打开链接的应用：$url", Toast.LENGTH_LONG).show()
        return true
    }
}

private fun isColorLight(color: Color): Boolean {
    if (color.alpha < 0.1f) return false
    val red = color.red
    val green = color.green
    val blue = color.blue
    val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
    return luminance > 0.5f
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

private fun getAssetFileString(context: android.content.Context, fileName: String): String {
    return try {
        context.assets.open(fileName).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        android.util.Log.e("WebViewError", "Failed to read asset $fileName: ${e.message}")
        ""
    }
}

private fun updateStatusBarFromWeb(view: WebView, useFullscreen: Boolean) {
    val js = """
        (function() {
            var meta = document.querySelector('meta[name="theme-color"]');
            if (meta && meta.getAttribute('content')) {
                return meta.getAttribute('content');
            }
            if (document.body) {
                var bodyBg = window.getComputedStyle(document.body).backgroundColor;
                return bodyBg;
            }
            return null;
        })()
    """.trimIndent()

    view.evaluateJavascript(js) { colorStr ->
        if (colorStr == null || colorStr == "null") return@evaluateJavascript
        val cleaned = colorStr.replace("\"", "").trim()
        if (cleaned.isEmpty()) return@evaluateJavascript

        var parsedColor: Color? = null
        try {
            if (cleaned.startsWith("#")) {
                parsedColor = Color(android.graphics.Color.parseColor(cleaned))
            } else if (cleaned.startsWith("rgb")) {
                val parts = cleaned.substringAfter("(").substringBefore(")").split(",")
                if (parts.size >= 3) {
                    val r = parts[0].trim().toInt()
                    val g = parts[1].trim().toInt()
                    val b = parts[2].trim().toInt()
                    parsedColor = Color(r, g, b)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        if (parsedColor != null && !useFullscreen) {
            val activity = view.context as? Activity
            val window = activity?.window
            if (window != null) {
                window.statusBarColor = parsedColor.toArgb()
                val isLight = isColorLight(parsedColor)
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isLight
            }
        }
    }
}
