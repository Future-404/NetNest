package com.pwa.shell.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pwa.shell.data.local.AppDatabase
import com.pwa.shell.data.local.PwaDao
import com.pwa.shell.data.local.PwaEntity
import com.pwa.shell.data.remote.IconDownloader
import com.pwa.shell.data.remote.PwaManifestFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    val database = AppDatabase.getDatabase(appContext)
    private val pwaDao: PwaDao = database.pwaDao()
    val userScriptDao = database.userScriptDao()
    val scriptStorageDao = database.scriptStorageDao()
    private val client = OkHttpClient()
    private val fetcher = PwaManifestFetcher(client)
    private val downloader = IconDownloader(client)

    val pwaList = pwaDao.getAllPwas()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun addPwa(url: String, context: Context) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading("正在获取 PWA 元数据...")
            try {
                val result = fetcher.fetchPwaInfo(url)
                val localIconPath = result.iconUrl?.let { downloader.downloadIcon(context, it) } ?: ""
                
                val pwaEntity = PwaEntity(
                    name = result.name,
                    url = result.url,
                    iconPath = localIconPath,
                    themeColor = result.themeColor,
                    displayOrder = 0,
                    addedTime = System.currentTimeMillis()
                )
                pwaDao.insert(pwaEntity)
                _uiState.value = UiState.Success("PWA 添加成功")
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    message = e.localizedMessage ?: "获取元数据失败",
                    fallbackUrl = url
                )
            }
        }
    }

    fun addPwaManually(name: String, url: String, iconPath: String, themeColor: String?, useChromeUa: Boolean, useDevConsole: Boolean, useFullscreen: Boolean, securityMode: Int = 1, trustedDomains: String = "") {
        viewModelScope.launch {
            val pwaEntity = PwaEntity(
                name = name,
                url = url,
                iconPath = iconPath,
                themeColor = themeColor,
                displayOrder = 0,
                addedTime = System.currentTimeMillis(),
                useChromeUa = useChromeUa,
                useDevConsole = useDevConsole,
                useFullscreen = useFullscreen,
                securityMode = securityMode,
                trustedDomains = trustedDomains
            )
            pwaDao.insert(pwaEntity)
            _uiState.value = UiState.Success("PWA 手动添加成功")
        }
    }

    fun updatePwa(pwa: PwaEntity) {
        viewModelScope.launch {
            val previous = pwaDao.getAllPwasOnce().firstOrNull { it.id == pwa.id }
            pwaDao.update(pwa)
            if (
                previous != null &&
                (previous.name != pwa.name || previous.iconPath != pwa.iconPath)
            ) {
                runCatching { updatePinnedPwaShortcut(appContext, pwa) }
                    .onFailure {
                        Log.e("MainViewModel", "Failed to update shortcut for PWA ${pwa.id}", it)
                    }
            }
            previous?.iconPath
                ?.takeIf { it != pwa.iconPath }
                ?.let { deleteIconIfUnreferenced(it) }
        }
    }

    suspend fun downloadWebsiteIcon(url: String): Result<String> = runCatching {
        val result = fetcher.fetchPwaInfo(url)
        val iconUrl = result.iconUrl ?: error("该网站没有提供可用图标")
        downloader.downloadIcon(appContext, iconUrl)
            ?: error("网站图标下载失败")
    }

    fun deletePwa(pwa: PwaEntity) {
        viewModelScope.launch {
            // WebView cookies/storage are process-global. Only clear them when no other
            // configured PWA could be using the same host or a parent/child subdomain.
            try {
                val uri = Uri.parse(pwa.url)
                val scheme = uri.scheme ?: "https"
                val host = uri.host ?: ""
                if (host.isNotEmpty()) {
                    val hasRelatedPwa = pwaDao.getAllPwasOnce()
                        .filter { it.id != pwa.id }
                        .any { hostsShareCookieScope(host, Uri.parse(it.url).host.orEmpty()) }
                    if (hasRelatedPwa) {
                        Log.i("MainViewModel", "Skipping WebView cleanup for ${pwa.url}; another PWA shares cookie scope")
                    } else {
                    val port = uri.port
                    val origin = if (port != -1) {
                        "$scheme://$host:$port"
                    } else {
                        "$scheme://$host"
                    }

                    // Delete storage for the configured origin. We intentionally avoid global
                    // removeAllCookies()/deleteAllData() because they would affect unrelated PWAs.
                    WebStorage.getInstance().deleteOrigin(origin)

                    // Expire cookies visible to this exact URL. WebView does not expose cookie
                    // paths/domains, so unknown redirect/third-party cookies are left intact.
                    val cookieManager = CookieManager.getInstance()
                    val cookieString = cookieManager.getCookie(pwa.url)
                    if (!cookieString.isNullOrEmpty()) {
                        cookieString.split(";").forEach { cookie ->
                            val parts = cookie.split("=")
                            if (parts.isNotEmpty()) {
                                val name = parts[0].trim()
                                // Clear cookie by setting it with an expired date
                                cookieManager.setCookie(pwa.url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                            }
                        }
                        cookieManager.flush()
                    }
                    }
                }
            } catch (e: java.lang.Exception) {
                Log.e("MainViewModel", "Failed to clean WebView cache for ${pwa.url}", e)
            }

            pwaDao.delete(pwa)
            runCatching { disablePinnedPwaShortcut(appContext, pwa.id) }
                .onFailure {
                    Log.e("MainViewModel", "Failed to disable shortcut for PWA ${pwa.id}", it)
                }
            deleteIconIfUnreferenced(pwa.iconPath)
            runCatching { clearPwaNotificationData(appContext, pwa) }
                .onFailure {
                    Log.e(
                        "MainViewModel",
                        "Failed to clear notification data for PWA ${pwa.id}",
                        it
                    )
                }
        }
    }

    private suspend fun deleteIconIfUnreferenced(iconPath: String) {
        if (iconPath.isBlank()) return
        val stillReferenced = pwaDao.getAllPwasOnce().any { it.iconPath == iconPath }
        if (!stillReferenced) PwaIconManager.deleteManagedIcon(appContext, iconPath)
    }

    private fun hostsShareCookieScope(first: String, second: String): Boolean {
        val a = first.trim('.').lowercase()
        val b = second.trim('.').lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.endsWith(".$b") || b.endsWith(".$a")
    }



    fun reorderPwas(pwas: List<PwaEntity>) {
        viewModelScope.launch {
            val updated = pwas.mapIndexed { index, pwaEntity ->
                pwaEntity.copy(displayOrder = index)
            }
            pwaDao.batchUpdateDisplayOrder(updated)
        }
    }

    fun resetState() {
        _uiState.value = UiState.Idle
    }
}

sealed interface UiState {
    object Idle : UiState
    data class Loading(val message: String) : UiState
    data class Success(val message: String) : UiState
    data class Error(val message: String, val fallbackUrl: String) : UiState
}
