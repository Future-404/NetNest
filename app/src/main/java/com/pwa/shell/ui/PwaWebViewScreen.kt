package com.pwa.shell.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pwa.shell.data.local.PwaEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PwaWebViewScreen(
    pwa: PwaEntity,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf(pwa.name) }
    var currentUrl by remember { mutableStateOf(pwa.url) }

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

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBackToHome()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Navigation bar
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = currentTitle,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBackToHome) {
                    Icon(Icons.Default.Close, contentDescription = "Close PWA")
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                        context.startActivity(intent)
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Open in Browser")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        // Web view container
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        configureSettings(this)
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.let {
                                    canGoBack = it.canGoBack()
                                    canGoForward = it.canGoForward()
                                    currentTitle = it.title ?: pwa.name
                                    currentUrl = it.url ?: pwa.url
                                }
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                view?.let {
                                    canGoBack = it.canGoBack()
                                    canGoForward = it.canGoForward()
                                    currentUrl = it.url ?: pwa.url
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
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

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                            }
                        }

                        loadUrl(pwa.url)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom Web controller controls
        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { webView?.goBack() },
                    enabled = canGoBack
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go Back")
                }

                IconButton(
                    onClick = { webView?.goForward() },
                    enabled = canGoForward
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go Forward")
                }

                IconButton(
                    onClick = { webView?.reload() }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload Page")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureSettings(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        allowFileAccess = true
        allowContentAccess = true
        builtInZoomControls = true
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
    }
}
