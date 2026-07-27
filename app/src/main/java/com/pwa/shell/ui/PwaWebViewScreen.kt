package com.pwa.shell.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
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
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import com.pwa.shell.data.local.PwaEntity
import com.pwa.shell.data.local.AppDatabase
import com.pwa.shell.data.local.RunAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class SecurityDecision { ALLOW_ONCE, BLOCK_ONCE, TRUST_DOMAIN, BLOCK_ALL }

class SecurityPolicyStore(initial: PwaEntity) {
    private data class CachedDecision(val decision: SecurityDecision, val expiresAt: Long)
    @Volatile private var policy: PwaEntity = initial
    private val cache = mutableMapOf<String, CachedDecision>()

    fun snapshot(): PwaEntity = policy

    @Synchronized
    fun update(updated: PwaEntity) {
        policy = updated
    }

    @Synchronized
    fun remember(host: String, leakType: String, decision: SecurityDecision) {
        cache["$host|$leakType"] = CachedDecision(decision, System.currentTimeMillis() + 10_000)
    }

    @Synchronized
    fun cached(host: String, leakType: String): SecurityDecision? {
        val key = "$host|$leakType"
        val entry = cache[key] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            cache.remove(key)
            return null
        }
        return entry.decision
    }

    @Synchronized
    fun trustDomain(host: String) {
        val current = policy
        val domains = current.trustedDomains.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (domains.none { it.equals(host, ignoreCase = true) }) {
            policy = current.copy(trustedDomains = (domains + host).joinToString(","))
        }
    }

    @Synchronized
    fun blockAll() {
        policy = policy.copy(securityMode = 2)
        cache.clear()
    }
}

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
    var blockedLeakType by remember { mutableStateOf("") }
    var currentCallback by remember { mutableStateOf<((SecurityDecision) -> Unit)?>(null) }
    var pendingWebPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    val bridgeCapabilityToken = remember(pwa.id) { UUID.randomUUID().toString() }
    val securityPolicyStore = remember(pwa.id) { SecurityPolicyStore(pwa) }
    LaunchedEffect(pwa) { securityPolicyStore.update(pwa) }

    val db = remember { AppDatabase.getDatabase(context) }
    val userScriptDao = remember { db.userScriptDao() }
    val scriptStorageDao = remember { db.scriptStorageDao() }
    val coroutineScope = rememberCoroutineScope()

    // Helper function to query and inject scripts for a specific phase
    fun injectScriptsForPhase(view: WebView, url: String, phase: RunAt) {
        coroutineScope.launch(Dispatchers.IO) {
            val allScripts = userScriptDao.getScriptsForPwa(pwa.id)
            val phaseScripts = allScripts.filter {
                it.enabled && MatchPatternMatcher.matches(url, it.matchPatterns) && it.runAt == phase
            }
            if (phaseScripts.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val currentUrl = view.url ?: ""
                    val stillMatching = phaseScripts.filter {
                        MatchPatternMatcher.matches(currentUrl, it.matchPatterns)
                    }
                    if (stillMatching.isNotEmpty()) {
                        val compiledJs = buildInjectionScript(
                            stillMatching,
                            phase,
                            bridgeCapabilityToken
                        )
                        view.evaluateJavascript(compiledJs, null)
                    }
                }
            }
        }
    }
    LaunchedEffect(pwa.useFullscreen) {
        val activity = context.findActivity()
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (pwa.useFullscreen) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode = 
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = attrs
                }
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode = 
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    window.attributes = attrs
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

    val webPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val request = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (request == null) return@rememberLauncherForActivityResult

        val grantedResources = request.resources.filter { resource ->
            val permission = androidPermissionForWebResource(resource)
            permission != null && (
                grants[permission] == true ||
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                )
        }
        if (grantedResources.isEmpty()) {
            request.deny()
        } else {
            request.grant(grantedResources.toTypedArray())
        }
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
        val activity = context.findActivity()
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
            CookieManager.getInstance().flush()
            pendingWebPermissionRequest?.deny()
            pendingWebPermissionRequest = null
            currentCallback?.invoke(SecurityDecision.BLOCK_ONCE)
            currentCallback = null
            webView?.apply {
                stopLoading()
                removeJavascriptInterface("NetNestSecurity")
                removeJavascriptInterface("NetNestScriptBridge")
                webChromeClient = null
                webViewClient = WebViewClient()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webView = null
            window?.let { w ->
                // Restore default transparent system bars
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val attrs = w.attributes
                    attrs.layoutInDisplayCutoutMode = 
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    w.attributes = attrs
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
                    // Remote inspection must never be enabled in production builds.
                    WebView.setWebContentsDebuggingEnabled(
                        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    )

                    // Keep first-party sessions while preventing cross-site cookie tracking.
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

                    // Add Security Sandbox Javascript Interface Bridge
                    addJavascriptInterface(
                        SecurityBridge(securityPolicyStore, bridgeCapabilityToken) { host, leakType, callback ->
                            blockedUrl = host
                            blockedLeakType = leakType
                            currentCallback = callback
                            showSecurityDialog = true
                        },
                        "NetNestSecurity"
                    )

                    // Add User Script storage bridge
                    addJavascriptInterface(
                        NetNestScriptBridge(
                            pwa.id,
                            scriptStorageDao,
                            bridgeCapabilityToken
                        ) { level, message ->
                            ScriptLogCollector.addLog(level, message)
                        },
                        "NetNestScriptBridge"
                    )

                    configureSettings(this, pwa)
                    
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        try {
                            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                                this,
                                getFingerprintJs(pwa) + getSecuritySandboxJs(bridgeCapabilityToken),
                                setOf("*")
                            )
                        } catch (e: Exception) {
                            Log.e("PwaWebViewScreen", "addDocumentStartJavaScript error", e)
                        }
                    }
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            if (view != null) {
                                injectSecuritySandbox(view, pwa, bridgeCapabilityToken)
                                if (url != null) {
                                    injectScriptsForPhase(view, url, RunAt.DOCUMENT_START)
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Flush cookies immediately to ensure persistence
                            CookieManager.getInstance().flush()

                            if (view != null) {
                                injectSecuritySandbox(view, pwa, bridgeCapabilityToken)
                                if (url != null) {
                                    injectScriptsForPhase(view, url, RunAt.DOCUMENT_END)
                                    view.postDelayed({
                                        injectScriptsForPhase(view, url, RunAt.DOCUMENT_IDLE)
                                    }, 500)
                                }
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

                        // Handle window.open popups using a temporary background WebView
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            if (!isUserGesture) return false
                            val mainWebView = view ?: return false
                            val context = mainWebView.context
                            val tempWebView = WebView(context)
                            
                            // Configure settings to match main WebView
                            configureSettings(tempWebView, pwa)
                            
                            tempWebView.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    tempView: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    // Redirect HTTP/HTTPS navigations back to the main WebView
                                    if (url.startsWith("http://") || url.startsWith("https://")) {
                                        mainWebView.loadUrl(url)
                                        tempView?.destroy()
                                        return true
                                    }
                                    // Handle custom schemes (e.g., intent:, mailto:) externally
                                    val handled = handleUrlRedirection(tempView, url)
                                    if (handled) {
                                        tempView?.destroy()
                                    }
                                    return handled
                                }

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(
                                    tempView: WebView?,
                                    url: String?
                                ): Boolean {
                                    if (url == null) return false
                                    if (url.startsWith("http://") || url.startsWith("https://")) {
                                        mainWebView.loadUrl(url)
                                        tempView?.destroy()
                                        return true
                                    }
                                    val handled = handleUrlRedirection(tempView, url)
                                    if (handled) {
                                        tempView?.destroy()
                                    }
                                    return handled
                                }

                                override fun onPageFinished(tempView: WebView?, url: String?) {
                                    super.onPageFinished(tempView, url)
                                    // Clean up temporary WebView if it's just a blank popup
                                    if (url == "about:blank") {
                                        tempView?.destroy()
                                    }
                                }
                            }
                            
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            if (transport != null) {
                                transport.webView = tempWebView
                                resultMsg.sendToTarget()
                                return true
                            }
                            tempWebView.destroy()
                            return false
                        }

                        // Web API Permission request bridge (Camera, Mic, Location)
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request ?: return
                            if (
                                pendingWebPermissionRequest != null ||
                                !WebSecurityPolicy.isTrustedUrl(
                                    request.origin.toString(),
                                    pwa.url,
                                    pwa.trustedDomains
                                )
                            ) {
                                request.deny()
                                return
                            }

                            val knownResources = request.resources.filter {
                                androidPermissionForWebResource(it) != null
                            }
                            if (knownResources.isEmpty()) {
                                request.deny()
                                return
                            }

                            val missingPermissions = knownResources
                                .mapNotNull(::androidPermissionForWebResource)
                                .distinct()
                                .filter {
                                    ContextCompat.checkSelfPermission(context, it) !=
                                        PackageManager.PERMISSION_GRANTED
                                }

                            if (missingPermissions.isEmpty()) {
                                request.grant(knownResources.toTypedArray())
                            } else {
                                pendingWebPermissionRequest = request
                                webPermissionLauncher.launch(missingPermissions.toTypedArray())
                            }
                        }

                        override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                            if (pendingWebPermissionRequest === request) {
                                pendingWebPermissionRequest = null
                            }
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
            modifier = if (pwa.useFullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
            }
        )

        // Privacy Security Sandbox Warning Dialog
        if (showSecurityDialog) {
            fun resolveSecurityDecision(decision: SecurityDecision) {
                val callback = currentCallback
                currentCallback = null
                showSecurityDialog = false
                when (decision) {
                    SecurityDecision.TRUST_DOMAIN -> {
                        securityPolicyStore.trustDomain(blockedUrl)
                        onUpdatePwa(securityPolicyStore.snapshot())
                    }
                    SecurityDecision.BLOCK_ALL -> {
                        securityPolicyStore.blockAll()
                        onUpdatePwa(securityPolicyStore.snapshot())
                    }
                    SecurityDecision.ALLOW_ONCE,
                    SecurityDecision.BLOCK_ONCE -> {
                        securityPolicyStore.remember(blockedUrl, blockedLeakType, decision)
                    }
                }
                callback?.invoke(decision)
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    resolveSecurityDecision(SecurityDecision.BLOCK_ONCE)
                },
                title = { Text("🔒 隐私安全警报") },
                text = {
                    Text("检测到网页向外部域名上传疑似敏感数据：\n\n目标地址：$blockedUrl\n数据类型：$blockedLeakType\n\n请选择本次或后续请求的处理方式。")
                },
                confirmButton = {
                    androidx.compose.foundation.layout.Column(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.TextButton(
                            onClick = { resolveSecurityDecision(SecurityDecision.BLOCK_ONCE) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("拦截本次（10秒内同类请求）", color = Color.Red)
                        }
                        androidx.compose.material3.TextButton(
                            onClick = { resolveSecurityDecision(SecurityDecision.ALLOW_ONCE) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("允许本次（10秒内同类请求）", color = Color.Gray)
                        }
                        androidx.compose.material3.TextButton(
                            onClick = { resolveSecurityDecision(SecurityDecision.TRUST_DOMAIN) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("信任此域名", color = Color(0xFF4CAF50))
                        }
                        androidx.compose.material3.TextButton(
                            onClick = { resolveSecurityDecision(SecurityDecision.BLOCK_ALL) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("拦截所有疑似泄露", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                dismissButton = null
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureSettings(webView: WebView, pwa: PwaEntity) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true) // Required for window.open popups
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        allowFileAccess = false
        allowContentAccess = false
        cacheMode = WebSettings.LOAD_DEFAULT
        mediaPlaybackRequiresUserGesture = false
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false

        // Custom User-Agent cleaner logic
        val defaultUa = WebSettings.getDefaultUserAgent(webView.context)
        if (!pwa.customUserAgent.isNullOrBlank()) {
            userAgentString = pwa.customUserAgent
        } else if (pwa.useChromeUa && defaultUa.isNotEmpty()) {
            // Strip WebView signature '; wv' and version code to masquerade as standard mobile Chrome
            val cleanedUa = defaultUa.replace("Version/4.0 ", "").replace("; wv", "")
            userAgentString = cleanedUa
        }
    }
}

private fun getFingerprintJs(pwa: PwaEntity): String {
    val language = org.json.JSONObject.quote(pwa.customLanguage)
    val platform = org.json.JSONObject.quote(pwa.customPlatform)
    val width = pwa.screenWidth
    val height = pwa.screenHeight
    val dpr = pwa.deviceScaleFactor
    return """
        (function() {
            try {
                const language = $language;
                const platform = $platform;
                if (language) {
                    Object.defineProperty(Navigator.prototype, 'language', { get: () => language, configurable: true });
                    Object.defineProperty(Navigator.prototype, 'languages', { get: () => [language], configurable: true });
                }
                if (platform) {
                    Object.defineProperty(Navigator.prototype, 'platform', { get: () => platform, configurable: true });
                }
                if ($width > 0) Object.defineProperty(Screen.prototype, 'width', { get: () => $width, configurable: true });
                if ($height > 0) Object.defineProperty(Screen.prototype, 'height', { get: () => $height, configurable: true });
                if ($dpr > 0) Object.defineProperty(window, 'devicePixelRatio', { get: () => $dpr, configurable: true });
            } catch (_) {}
        })();
    """.trimIndent()
}

private fun handleUrlRedirection(view: WebView?, url: String): Boolean {
    if (url.startsWith("https://") || url == "about:blank" || url.startsWith("blob:")) {
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
            val activity = view.context.findActivity()
            val window = activity?.window
            if (window != null) {
                window.statusBarColor = parsedColor.toArgb()
                val isLight = isColorLight(parsedColor)
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isLight
            }
        }
    }
}

private fun getSecuritySandboxJs(capabilityToken: String): String {
    val encodedToken = org.json.JSONObject.quote(capabilityToken)
    return """
       (function() {
           if (window.__netnest_sandbox_injected) return;
           window.__netnest_sandbox_injected = true;
           const capabilityToken = $encodedToken;
           const securityBridge = window.NetNestSecurity;
           if (securityBridge) {
               delete window.NetNestSecurity;
           }

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
           if (window.fetch) {
               const originalFetch = window.fetch;
               window.fetch = async function(input, init) {
                   const url = typeof input === 'string' ? input : (input ? input.url : '');
                   const method = (init && init.method) || 'GET';
                   const body = (init && init.body) || '';
                   
                   if (securityBridge && securityBridge.auditRequest) {
                       const decision = securityBridge.auditRequest(
                           capabilityToken,
                           url,
                           method,
                           serializeBody(body)
                       );
                       if (decision === 'BLOCK') {
                           console.warn('[NetNest Sandbox] Blocked upload to: ' + url);
                           throw new TypeError('Failed to fetch: Request blocked by NetNest Security Sandbox.');
                       }
                   }
                   return originalFetch.apply(this, arguments);
               };
               window.fetch.toString = function() { return 'function fetch() { [native code] }'; };
               window.fetch.toString.toString = function() { return 'function toString() { [native code] }'; };
           }

           // 2. Intercept XMLHttpRequest
           if (window.XMLHttpRequest) {
               const originalOpen = XMLHttpRequest.prototype.open;
               const originalSend = XMLHttpRequest.prototype.send;
               
               XMLHttpRequest.prototype.open = function(method, url) {
                   this._url = url;
                   this._method = method;
                   return originalOpen.apply(this, arguments);
               };
               XMLHttpRequest.prototype.open.toString = function() { return 'function open() { [native code] }'; };
               XMLHttpRequest.prototype.open.toString.toString = function() { return 'function toString() { [native code] }'; };
               
               XMLHttpRequest.prototype.send = function(body) {
                   const url = this._url || '';
                   const method = this._method || 'GET';
                   const reqBody = body || '';
                   
                   if (securityBridge && securityBridge.auditRequest) {
                       const decision = securityBridge.auditRequest(
                           capabilityToken,
                           url,
                           method,
                           serializeBody(reqBody)
                       );
                       if (decision === 'BLOCK') {
                           console.warn('[NetNest Sandbox] Blocked XHR upload to: ' + url);
                           const errEvent = new ProgressEvent('error');
                           this.dispatchEvent(errEvent);
                           throw new Error('Network request blocked by NetNest Security Sandbox.');
                       }
                   }
                   return originalSend.apply(this, arguments);
               };
               XMLHttpRequest.prototype.send.toString = function() { return 'function send() { [native code] }'; };
               XMLHttpRequest.prototype.send.toString.toString = function() { return 'function toString() { [native code] }'; };
           }

           // 3. Intercept sendBeacon
           if (navigator.sendBeacon) {
               const originalSendBeacon = navigator.sendBeacon;
               navigator.sendBeacon = function(url, data) {
                   const reqBody = data || '';
                   if (securityBridge && securityBridge.auditRequest) {
                       const decision = securityBridge.auditRequest(
                           capabilityToken,
                           url,
                           'POST',
                           serializeBody(reqBody)
                       );
                       if (decision === 'BLOCK') {
                           console.warn('[NetNest Sandbox] Blocked sendBeacon upload to: ' + url);
                           return false;
                       }
                   }
                   return originalSendBeacon.apply(this, arguments);
               };
               navigator.sendBeacon.toString = function() { return 'function sendBeacon() { [native code] }'; };
               navigator.sendBeacon.toString.toString = function() { return 'function toString() { [native code] }'; };
           }

            // 4. Override script integrity to bypass self-destruction checks dynamically
             try {
                 Object.defineProperty(HTMLScriptElement.prototype, 'integrity', {
                     get: function() {
                         return this.getAttribute('integrity') || '';
                     },
                     set: function(val) {
                         this.setAttribute('integrity', val);
                     },
                     configurable: true,
                     enumerable: true
                 });
             } catch (e) {
                 console.error('Failed to define integrity polyfill:', e);
             }

             // 5. Hide DevTools Debugging Libraries (vConsole, Eruda) from detection (DebugLib type 7)
             try {
                 let realVcOrigConsole = undefined;
                 Object.defineProperty(window, '_vcOrigConsole', {
                     get: function() {
                         const stack = new Error().stack || '';
                         if (stack.indexOf('vconsole') !== -1 || stack.indexOf('vConsole') !== -1) {
                             return realVcOrigConsole;
                         }
                         return undefined;
                     },
                     set: function(val) { realVcOrigConsole = val; },
                     configurable: true,
                     enumerable: true
                 });

                 let realEruda = undefined;
                 Object.defineProperty(window, 'eruda', {
                     get: function() {
                         const stack = new Error().stack || '';
                         if (stack.indexOf('eruda') !== -1) {
                             return realEruda;
                         }
                         return undefined;
                     },
                     set: function(val) { realEruda = val; },
                     configurable: true,
                     enumerable: true
                 });

                 const originalGetElementById = document.getElementById;
                 document.getElementById = function(id) {
                     if (id && (id.indexOf('vconsole') !== -1 || id.indexOf('eruda') !== -1)) {
                         const stack = new Error().stack || '';
                         if (stack.indexOf('vconsole') === -1 && stack.indexOf('eruda') === -1 && stack.indexOf('vConsole') === -1) {
                             return null;
                         }
                     }
                     return originalGetElementById.apply(this, arguments);
                 };
                 document.getElementById.toString = function() { return 'function getElementById() { [native code] }'; };

                 const originalQuerySelector = document.querySelector;
                 document.querySelector = function(selector) {
                     if (selector && (selector.indexOf('vconsole') !== -1 || selector.indexOf('vc-toggle') !== -1 || selector.indexOf('eruda') !== -1)) {
                         const stack = new Error().stack || '';
                         if (stack.indexOf('vconsole') === -1 && stack.indexOf('eruda') === -1 && stack.indexOf('vConsole') === -1) {
                             return null;
                         }
                     }
                     return originalQuerySelector.apply(this, arguments);
                 };
                 document.querySelector.toString = function() { return 'function querySelector() { [native code] }'; };

                 const originalQuerySelectorAll = document.querySelectorAll;
                 document.querySelectorAll = function(selector) {
                     if (selector && (selector.indexOf('vconsole') !== -1 || selector.indexOf('vc-toggle') !== -1 || selector.indexOf('eruda') !== -1)) {
                         const stack = new Error().stack || '';
                         if (stack.indexOf('vconsole') === -1 && stack.indexOf('eruda') === -1 && stack.indexOf('vConsole') === -1) {
                             return document.createDocumentFragment().childNodes;
                         }
                     }
                     return originalQuerySelectorAll.apply(this, arguments);
                         };
                         document.querySelectorAll.toString = function() { return 'function querySelectorAll() { [native code] }'; };
             } catch (e) {
                 console.error('Failed to initialize DevTools stealth polyfill:', e);
             }
        })();
    """.trimIndent()
}

private fun injectSecuritySandbox(webView: WebView, pwa: PwaEntity, capabilityToken: String) {
    if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
        webView.evaluateJavascript(getFingerprintJs(pwa) + getSecuritySandboxJs(capabilityToken), null)
    }
}

class SecurityBridge(
    private val policyStore: SecurityPolicyStore,
    capabilityToken: String,
    private val onShowBlockDialog: (host: String, leakType: String, callback: (SecurityDecision) -> Unit) -> Unit
) {
    private val dialogLock = Any()
    private val capabilityTokenBytes = capabilityToken.toByteArray(Charsets.UTF_8)

    @android.webkit.JavascriptInterface
    fun auditRequest(token: String, url: String, method: String, body: String): String {
        if (!isAuthorized(token)) return "BLOCK"
        val policy = policyStore.snapshot()
        if (policy.securityMode == 0) return "ALLOW"

        val uri = Uri.parse(url)
        val host = uri.host ?: ""
        if (host.isEmpty()) return "ALLOW"

        if (WebSecurityPolicy.isTrustedUrl(url, policy.url, policy.trustedDomains)) {
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
            val latestPolicy = policyStore.snapshot()
            if (latestPolicy.securityMode == 2 || !latestPolicy.securityPromptEnabled) {
                return "BLOCK"
            }

            policyStore.cached(host, leakType)?.let { cached ->
                return if (cached == SecurityDecision.ALLOW_ONCE) "ALLOW" else "BLOCK"
            }

            val decision = AtomicReference(SecurityDecision.BLOCK_ONCE)
            val latch = java.util.concurrent.CountDownLatch(1)

            onShowBlockDialog(host, leakType) { selected ->
                decision.set(selected)
                latch.countDown()
            }

            try {
                latch.await(8, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            val selected = decision.get()
            return when (selected) {
                SecurityDecision.TRUST_DOMAIN,
                SecurityDecision.ALLOW_ONCE -> "ALLOW"
                SecurityDecision.BLOCK_ALL,
                SecurityDecision.BLOCK_ONCE -> "BLOCK"
            }
        }
    }

    private fun isAuthorized(token: String): Boolean {
        return java.security.MessageDigest.isEqual(
            capabilityTokenBytes,
            token.toByteArray(Charsets.UTF_8)
        )
    }
}

private fun androidPermissionForWebResource(resource: String): String? {
    return when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }
}

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}
