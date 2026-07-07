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
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth

@Composable
fun PwaWebViewScreen(
    pwa: PwaEntity,
    onBackToHome: () -> Unit,
    onUpdatePwa: (PwaEntity) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    var webView: WebView? by remember { mutableStateOf(null) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var blockedUrl by remember { mutableStateOf("") }
    var currentCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    LaunchedEffect(pwa.useFullscreen) {
        val activity = context as? Activity
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (pwa.useFullscreen) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    window.attributes.layoutInDisplayCutoutMode = 
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    window.attributes.layoutInDisplayCutoutMode = 
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
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
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    w.attributes.layoutInDisplayCutoutMode = 
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
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

                    // Add Security Sandbox Javascript Interface Bridge
                    addJavascriptInterface(
                        SecurityBridge(pwa) { urlAndLeakType, callback ->
                            blockedUrl = urlAndLeakType
                            currentCallback = callback
                            showSecurityDialog = true
                        },
                        "NetNestSecurity"
                    )

                    configureSettings(this, pwa.useChromeUa)
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            if (view != null) {
                                injectSecuritySandbox(view)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Flush cookies immediately to ensure persistence
                            CookieManager.getInstance().flush()

                            if (view != null) {
                                injectSecuritySandbox(view)
                            }

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

        // Privacy Security Sandbox Warning Dialog
        if (showSecurityDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    currentCallback?.invoke(false)
                    showSecurityDialog = false
                },
                title = { Text("🔒 隐私安全警报") },
                text = {
                    Text("NetNest 沙箱检测到网页正在尝试秘密上传您的私密数据：\n\n目标地址：$blockedUrl\n\n该行为可能会泄露您的聊天历史、API密钥或账号凭证。是否拦截该上传行为？")
                },
                confirmButton = {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                currentCallback?.invoke(false) // Block!
                                showSecurityDialog = false
                            }
                        ) {
                            Text("拦截 (推荐)", color = Color.Red)
                        }
                        androidx.compose.material3.TextButton(
                            onClick = {
                                currentCallback?.invoke(true) // Allow once!
                                showSecurityDialog = false
                            }
                        ) {
                            Text("允许一次", color = Color.Gray)
                        }
                        androidx.compose.material3.TextButton(
                            onClick = {
                                val host = blockedUrl.substringBefore(" ").trim()
                                val newTrusted = if (pwa.trustedDomains.isEmpty()) host else "${pwa.trustedDomains},$host"
                                onUpdatePwa(pwa.copy(trustedDomains = newTrusted))
                                currentCallback?.invoke(true) // Allow permanently!
                                showSecurityDialog = false
                            }
                        ) {
                            Text("信任该域名", color = Color(0xFF4CAF50))
                        }
                    }
                },
                dismissButton = null
            )
        }
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

private fun injectSecuritySandbox(webView: WebView) {
    val js = """
       (function() {
           if (window.__netnest_sandbox_injected) return;
           window.__netnest_sandbox_injected = true;

           function serializeBody(body) {
               if (!body) return "";
               if (typeof body === "string") return body;
               if (body instanceof URLSearchParams) return body.toString();
               if (body instanceof FormData) {
                   var parts = [];
                   for (let [key, val] of body.entries()) {
                       parts.push(key + "=" + (typeof val === "string" ? val : "[File/Blob]"));
                   }
                   return parts.join("&");
               }
               return String(body);
           }

           // 1. Intercept fetch
           const originalFetch = window.fetch;
           window.fetch = async function(input, init) {
               const url = typeof input === 'string' ? input : (input ? input.url : '');
               const method = (init && init.method) || 'GET';
               const body = (init && init.body) || '';
               
               if (window.NetNestSecurity && window.NetNestSecurity.auditRequest) {
                   const decision = window.NetNestSecurity.auditRequest(url, method, serializeBody(body));
                   if (decision === 'BLOCK') {
                       console.warn('[NetNest Sandbox] Blocked upload to: ' + url);
                       throw new TypeError('Failed to fetch: Request blocked by NetNest Security Sandbox.');
                   }
               }
               return originalFetch.apply(this, arguments);
           };

           // 2. Intercept XMLHttpRequest
           const originalOpen = XMLHttpRequest.prototype.open;
           const originalSend = XMLHttpRequest.prototype.send;
           
           XMLHttpRequest.prototype.open = function(method, url) {
               this._url = url;
               this._method = method;
               return originalOpen.apply(this, arguments);
           };
           
           XMLHttpRequest.prototype.send = function(body) {
               const url = this._url || '';
               const method = this._method || 'GET';
               const reqBody = body || '';
               
               if (window.NetNestSecurity && window.NetNestSecurity.auditRequest) {
                   const decision = window.NetNestSecurity.auditRequest(url, method, serializeBody(reqBody));
                   if (decision === 'BLOCK') {
                       console.warn('[NetNest Sandbox] Blocked XHR upload to: ' + url);
                       const errEvent = new ProgressEvent('error');
                       this.dispatchEvent(errEvent);
                       throw new Error('Network request blocked by NetNest Security Sandbox.');
                   }
               }
               return originalSend.apply(this, arguments);
           };

           // 3. Intercept sendBeacon
           if (navigator.sendBeacon) {
               const originalSendBeacon = navigator.sendBeacon;
               navigator.sendBeacon = function(url, data) {
                   const reqBody = data || '';
                   if (window.NetNestSecurity && window.NetNestSecurity.auditRequest) {
                       const decision = window.NetNestSecurity.auditRequest(url, 'POST', serializeBody(reqBody));
                       if (decision === 'BLOCK') {
                           console.warn('[NetNest Sandbox] Blocked sendBeacon upload to: ' + url);
                           return false;
                       }
                   }
                   return originalSendBeacon.apply(this, arguments);
               };
           }
       })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

class SecurityBridge(
    private val pwa: PwaEntity,
    private val onShowBlockDialog: (urlAndLeakType: String, callback: (Boolean) -> Unit) -> Unit
) {
    private val dialogLock = Any()

    @android.webkit.JavascriptInterface
    fun auditRequest(url: String, method: String, body: String): String {
        if (pwa.securityMode == 0) return "ALLOW"

        val uri = Uri.parse(url)
        val host = uri.host ?: ""
        if (host.isEmpty()) return "ALLOW"

        // Always trust PWA host itself
        val pwaUri = Uri.parse(pwa.url)
        val pwaHost = pwaUri.host ?: ""
        if (host.equals(pwaHost, ignoreCase = true)) {
            return "ALLOW"
        }

        // Check whitelisted trustedDomains
        val whitelist = pwa.trustedDomains.split(",").map { it.trim().lowercase() }
        if (whitelist.any { it.isNotEmpty() && (host.endsWith(it) || it.endsWith(host)) }) {
            return "ALLOW"
        }

        // Analyze POST/PUT upload data
        val isPost = method.equals("POST", ignoreCase = true) || method.equals("PUT", ignoreCase = true)
        var detectedLeak = false
        var leakType = ""

        if (isPost && body.isNotEmpty()) {
            val lowerBody = body.lowercase()
            if (lowerBody.contains("role") && (lowerBody.contains("content") || lowerBody.contains("messages"))) {
                detectedLeak = true
                leakType = "聊天记录"
            } else if (body.contains("sk-") || body.contains("x-goog-api-key")) {
                detectedLeak = true
                leakType = "API 密钥"
            } else if (lowerBody.contains("password") || lowerBody.contains("passwd") || lowerBody.contains("session_token")) {
                detectedLeak = true
                leakType = "账号凭证"
            }
        }

        if (!detectedLeak) {
            return "ALLOW"
        }

        synchronized(dialogLock) {
            // Block silently
            if (pwa.securityMode == 2) {
                return "BLOCK"
            }

            // Show dialog and block thread
            var isAllowed = false
            val latch = java.util.concurrent.CountDownLatch(1)

            onShowBlockDialog("$host ($leakType)") { allowed ->
                isAllowed = allowed
                latch.countDown()
            }

            try {
                latch.await()
            } catch (e: InterruptedException) {
                // Default to block
            }

            return if (isAllowed) "ALLOW" else "BLOCK"
        }
    }
}
