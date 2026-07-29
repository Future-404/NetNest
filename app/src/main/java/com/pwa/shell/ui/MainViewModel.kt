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
import com.pwa.shell.data.local.PwaFolderEntity
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
    private val pwaFolderDao = database.pwaFolderDao()
    private val pendingProfileDeletionDao = database.pendingWebProfileDeletionDao()
    val userScriptDao = database.userScriptDao()
    val scriptStorageDao = database.scriptStorageDao()
    private val client = OkHttpClient()
    private val fetcher = PwaManifestFetcher(client)
    private val downloader = IconDownloader(client)

    val pwaList = pwaDao.getAllPwas()
    val pwaFolderList = pwaFolderDao.getAllFolders()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var profileDeletionRetryJob: Job? = null
    private var profileDeletionRetryRequested = false

    init {
        retryPendingWebProfileDeletions()
    }

    fun addPwa(
        url: String,
        context: Context,
        dataSpace: PwaDataSpace = PwaDataSpace.SHARED
    ) {
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
                    webProfileId = webProfileIdFor(dataSpace)
                )
                pwaDao.insert(pwaEntity)
                _uiState.value = UiState.Success("PWA 添加成功")
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    message = e.localizedMessage ?: "获取元数据失败",
                    fallbackUrl = url,
                    dataSpace = dataSpace
                )
            }
        }
    }

    fun addPwaManually(
        name: String,
        url: String,
        iconPath: String,
        themeColor: String?,
        useChromeUa: Boolean,
        useDevConsole: Boolean,
        useFullscreen: Boolean,
        securityMode: Int = 1,
        trustedDomains: String = "",
        dataSpace: PwaDataSpace = PwaDataSpace.SHARED
    ) {
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
                webProfileId = webProfileIdFor(dataSpace)
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
                    usedSharedCompatibility = it.usedSharedCompatibility,
                    folderId = it.folderId,
                    folderOrder = it.folderOrder
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
                pwaDao.deleteById(pwa.id)
                pwa.folderId?.let { cleanupSmallFolderAfterDeletion(it) }
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

    fun reorderHomeItems(
        entries: List<HomeOrderEntry>,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        runHomeOrganizationMutation(onResult) {
            database.withTransaction {
                persistHomeOrder(entries)
            }
        }
    }

    fun createFolder(
        firstPwaId: Long,
        secondPwaId: Long,
        name: String,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        val folderName = normalizedFolderName(name)
        if (folderName == null || firstPwaId == secondPwaId) {
            onResult(Result.failure(IllegalArgumentException("请输入 1–30 个字符的文件夹名称")))
            return
        }
        runHomeOrganizationMutation(onResult) {
            database.withTransaction {
                val pwas = pwaDao.getAllPwasOnce()
                val folders = pwaFolderDao.getAllFoldersOnce()
                val first = pwas.firstOrNull { it.id == firstPwaId && it.folderId == null }
                    ?: error("第一个应用已不在首页")
                val second = pwas.firstOrNull { it.id == secondPwaId && it.folderId == null }
                    ?: error("第二个应用已不在首页")
                val rootOrder = persistentHomeOrder(pwas, folders)
                val firstIndex = rootOrder.indexOf(HomeOrderEntry.Pwa(first.id))
                val secondIndex = rootOrder.indexOf(HomeOrderEntry.Pwa(second.id))
                check(firstIndex >= 0 && secondIndex >= 0) { "无法确定应用的首页位置" }
                val insertionIndex = minOf(firstIndex, secondIndex)
                val folderId = pwaFolderDao.insert(
                    PwaFolderEntity(
                        name = folderName,
                        displayOrder = insertionIndex,
                        addedTime = System.currentTimeMillis()
                    )
                )
                val orderedMembers = listOf(first, second).sortedBy {
                    rootOrder.indexOf(HomeOrderEntry.Pwa(it.id))
                }
                orderedMembers.forEachIndexed { index, member ->
                    pwaDao.updateFolder(member.id, folderId, index)
                }
                val updatedRoot = rootOrder
                    .filterNot {
                        it == HomeOrderEntry.Pwa(first.id) ||
                            it == HomeOrderEntry.Pwa(second.id)
                    }
                    .toMutableList()
                    .apply { add(insertionIndex, HomeOrderEntry.Folder(folderId)) }
                persistHomeOrder(updatedRoot)
            }
        }
    }

    fun addPwaToFolder(
        pwaId: Long,
        folderId: Long,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        runHomeOrganizationMutation(onResult) {
            database.withTransaction {
                val pwas = pwaDao.getAllPwasOnce()
                val folders = pwaFolderDao.getAllFoldersOnce()
                val pwa = pwas.firstOrNull { it.id == pwaId && it.folderId == null }
                    ?: error("该应用已不在首页")
                check(folders.any { it.id == folderId }) { "目标文件夹不存在" }
                val nextOrder = pwas.count { it.folderId == folderId }
                pwaDao.updateFolder(pwa.id, folderId, nextOrder)
                persistHomeOrder(
                    persistentHomeOrder(pwas, folders)
                        .filterNot { it == HomeOrderEntry.Pwa(pwa.id) }
                )
            }
        }
    }

    fun removePwaFromFolder(
        pwaId: Long,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        runHomeOrganizationMutation(onResult) {
            database.withTransaction {
                val pwas = pwaDao.getAllPwasOnce()
                val folders = pwaFolderDao.getAllFoldersOnce()
                val pwa = pwas.firstOrNull { it.id == pwaId }
                    ?: error("应用不存在")
                val folderId = pwa.folderId ?: error("该应用不在文件夹中")
                val folder = folders.firstOrNull { it.id == folderId }
                    ?: error("文件夹不存在")
                val members = pwas
                    .filter { it.folderId == folderId }
                    .sortedWith(compareBy<PwaEntity> { it.folderOrder }.thenBy { it.addedTime })
                if (members.size <= 2) {
                    dissolveFolderInTransaction(folder, members, pwas, folders)
                } else {
                    pwaDao.updateFolder(pwa.id, null, 0)
                    members.filterNot { it.id == pwa.id }.forEachIndexed { index, member ->
                        pwaDao.updateFolder(member.id, folderId, index)
                    }
                    val rootOrder = persistentHomeOrder(pwas, folders).toMutableList()
                    val folderIndex = rootOrder.indexOf(HomeOrderEntry.Folder(folderId))
                    rootOrder.add(
                        (folderIndex + 1).coerceIn(0, rootOrder.size),
                        HomeOrderEntry.Pwa(pwa.id)
                    )
                    persistHomeOrder(rootOrder)
                }
            }
        }
    }

    fun renameFolder(
        folderId: Long,
        name: String,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        val folderName = normalizedFolderName(name)
        if (folderName == null) {
            onResult(Result.failure(IllegalArgumentException("请输入 1–30 个字符的文件夹名称")))
            return
        }
        runHomeOrganizationMutation(onResult) {
            pwaFolderDao.rename(folderId, folderName)
        }
    }

    fun dissolveFolder(
        folderId: Long,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        runHomeOrganizationMutation(onResult) {
            database.withTransaction {
                val pwas = pwaDao.getAllPwasOnce()
                val folders = pwaFolderDao.getAllFoldersOnce()
                val folder = folders.firstOrNull { it.id == folderId }
                    ?: error("文件夹不存在")
                val members = pwas
                    .filter { it.folderId == folderId }
                    .sortedWith(compareBy<PwaEntity> { it.folderOrder }.thenBy { it.addedTime })
                dissolveFolderInTransaction(folder, members, pwas, folders)
            }
        }
    }

    fun reorderFolderMembers(
        folderId: Long,
        memberIds: List<Long>,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        runHomeOrganizationMutation(onResult) {
            database.withTransaction {
                val members = pwaDao.getPwasInFolderOnce(folderId)
                check(members.map { it.id }.toSet() == memberIds.toSet()) {
                    "文件夹内容已发生变化，请重试"
                }
                memberIds.forEachIndexed { index, memberId ->
                    pwaDao.updateFolder(memberId, folderId, index)
                }
            }
        }
    }

    private fun runHomeOrganizationMutation(
        onResult: (Result<Unit>) -> Unit,
        mutation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            onResult(runCatching { mutation() })
        }
    }

    private suspend fun persistHomeOrder(entries: List<HomeOrderEntry>) {
        entries.forEachIndexed { index, entry ->
            when (entry) {
                is HomeOrderEntry.Pwa -> pwaDao.updateDisplayOrder(entry.id, index)
                is HomeOrderEntry.Folder ->
                    pwaFolderDao.updateDisplayOrder(entry.id, index)
            }
        }
    }

    private suspend fun dissolveFolderInTransaction(
        folder: PwaFolderEntity,
        members: List<PwaEntity>,
        pwas: List<PwaEntity>,
        folders: List<PwaFolderEntity>
    ) {
        members.forEach { member -> pwaDao.updateFolder(member.id, null, 0) }
        pwaFolderDao.delete(folder)
        val rootOrder = persistentHomeOrder(pwas, folders).toMutableList()
        val folderIndex = rootOrder.indexOf(HomeOrderEntry.Folder(folder.id))
        if (folderIndex >= 0) {
            rootOrder.removeAt(folderIndex)
            rootOrder.addAll(
                folderIndex,
                members.map { HomeOrderEntry.Pwa(it.id) }
            )
        }
        persistHomeOrder(rootOrder)
    }

    private suspend fun cleanupSmallFolderAfterDeletion(folderId: Long) {
        val folder = pwaFolderDao.getAllFoldersOnce().firstOrNull { it.id == folderId }
            ?: return
        val members = pwaDao.getPwasInFolderOnce(folderId)
        if (members.size > 1) return
        val pwas = pwaDao.getAllPwasOnce()
        val folders = pwaFolderDao.getAllFoldersOnce()
        dissolveFolderInTransaction(folder, members, pwas, folders)
    }

    fun resetState() {
        _uiState.value = UiState.Idle
    }
}

sealed interface UiState {
    object Idle : UiState
    data class Loading(val message: String) : UiState
    data class Success(val message: String) : UiState
    data class Error(
        val message: String,
        val fallbackUrl: String,
        val dataSpace: PwaDataSpace
    ) : UiState
}
