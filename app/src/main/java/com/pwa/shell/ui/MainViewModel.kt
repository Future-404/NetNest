package com.pwa.shell.ui

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pwa.shell.data.local.AppDatabase
import com.pwa.shell.data.local.PendingWebProfileDeletionEntity
import com.pwa.shell.data.local.PwaDao
import com.pwa.shell.data.local.PwaEntity
import com.pwa.shell.data.remote.IconDownloader
import com.pwa.shell.data.remote.PwaManifestFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    val database = AppDatabase.getDatabase(appContext)
    private val pwaDao: PwaDao = database.pwaDao()
    private val pendingProfileDeletionDao = database.pendingWebProfileDeletionDao()
    val userScriptDao = database.userScriptDao()
    val scriptStorageDao = database.scriptStorageDao()
    private val client = OkHttpClient()
    private val fetcher = PwaManifestFetcher(client)
    private val downloader = IconDownloader(client)

    val pwaList = pwaDao.getAllPwas()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var profileDeletionRetryJob: Job? = null
    private var profileDeletionRetryRequested = false

    init {
        retryPendingWebProfileDeletions()
    }

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
                    addedTime = System.currentTimeMillis(),
                    webProfileId = newPwaWebProfileId()
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
                trustedDomains = trustedDomains,
                webProfileId = newPwaWebProfileId()
            )
            pwaDao.insert(pwaEntity)
            _uiState.value = UiState.Success("PWA 手动添加成功")
        }
    }

    fun updatePwa(pwa: PwaEntity) {
        viewModelScope.launch {
            val previous = pwaDao.getAllPwasOnce().firstOrNull { it.id == pwa.id }
            // Profile identity and compatibility history are lifecycle-owned fields.
            // UI/security updates may carry an older PwaEntity snapshot and must not
            // overwrite them or move a PWA back into the shared profile.
            val safeUpdate = previous?.let {
                pwa.copy(
                    webProfileId = it.webProfileId,
                    usedSharedCompatibility = it.usedSharedCompatibility
                )
            } ?: pwa
            pwaDao.update(safeUpdate)
            if (
                previous != null &&
                (previous.name != safeUpdate.name || previous.iconPath != safeUpdate.iconPath)
            ) {
                runCatching { updatePinnedPwaShortcut(appContext, safeUpdate) }
                    .onFailure {
                        Log.e(
                            "MainViewModel",
                            "Failed to update shortcut for PWA ${safeUpdate.id}",
                            it
                        )
                    }
            }
            previous?.iconPath
                ?.takeIf { it != safeUpdate.iconPath }
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
            database.withTransaction {
                pwa.webProfileId?.let { profileName ->
                    pendingProfileDeletionDao.upsert(
                        PendingWebProfileDeletionEntity(profileName)
                    )
                }
                pwaDao.delete(pwa)
            }

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
            retryPendingWebProfileDeletions()
        }
    }

    private suspend fun deleteIconIfUnreferenced(iconPath: String) {
        if (iconPath.isBlank()) return
        val stillReferenced = pwaDao.getAllPwasOnce().any { it.iconPath == iconPath }
        if (!stillReferenced) PwaIconManager.deleteManagedIcon(appContext, iconPath)
    }

    fun setUsedSharedCompatibility(pwaId: Long, used: Boolean) {
        viewModelScope.launch {
            pwaDao.setUsedSharedCompatibility(pwaId, used)
        }
    }

    fun retryPendingWebProfileDeletions() {
        profileDeletionRetryRequested = true
        if (profileDeletionRetryJob?.isActive == true) return
        profileDeletionRetryJob = viewModelScope.launch {
            do {
                profileDeletionRetryRequested = false
                pendingProfileDeletionDao.getAll().forEach { pending ->
                    try {
                        val result = PwaWebProfileManager.deleteProfile(pending.profileName)
                        if (result != null) {
                            pendingProfileDeletionDao.delete(pending.profileName)
                        }
                    } catch (error: IllegalStateException) {
                        Log.i(
                            "MainViewModel",
                            "Web profile ${pending.profileName} is still active; deletion deferred",
                            error
                        )
                    } catch (error: Exception) {
                        Log.e(
                            "MainViewModel",
                            "Failed to delete Web profile ${pending.profileName}",
                            error
                        )
                    }
                }
            } while (profileDeletionRetryRequested)
        }
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
