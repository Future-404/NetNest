package com.pwa.shell.ui

import android.content.Context
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
import java.io.File

class MainViewModel(context: Context) : ViewModel() {

    private val pwaDao: PwaDao = AppDatabase.getDatabase(context).pwaDao()
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

    fun addPwaManually(name: String, url: String, iconPath: String, themeColor: String?, useChromeUa: Boolean, useDevConsole: Boolean, useFullscreen: Boolean) {
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
                useFullscreen = useFullscreen
            )
            pwaDao.insert(pwaEntity)
            _uiState.value = UiState.Success("PWA 手动添加成功")
        }
    }

    fun updatePwa(pwa: PwaEntity) {
        viewModelScope.launch {
            pwaDao.update(pwa)
        }
    }

    fun deletePwa(pwa: PwaEntity) {
        viewModelScope.launch {
            // Delete local icon cache
            if (pwa.iconPath.isNotEmpty()) {
                val file = File(pwa.iconPath)
                if (file.exists()) {
                    file.delete()
                }
            }
            pwaDao.delete(pwa)
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
