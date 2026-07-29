package com.pwa.shell.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pwas",
    indices = [Index(value = ["folderId"])]
)
data class PwaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val iconPath: String,
    val themeColor: String?,
    val displayOrder: Int,
    val addedTime: Long,
    val useChromeUa: Boolean = true,
    val useDevConsole: Boolean = false,
    val useFullscreen: Boolean = false,
    val securityMode: Int = 1, // 0: Disabled, 1: Block & Warn, 2: Silent Block
    val securityPromptEnabled: Boolean = true,
    val trustedDomains: String = "", // Comma-separated trusted hosts
    val customUserAgent: String? = null,
    val customLanguage: String = "",
    val customPlatform: String = "",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val deviceScaleFactor: Float = 0f,
    /**
     * Null selects NetNest's shared WebView data space. An opaque profile name
     * selects an isolated data space owned only by this PWA.
     */
    val webProfileId: String? = null,
    /**
     * Records that this isolated PWA has previously used the shared compatibility
     * profile because the installed WebView provider did not support multi-profile.
     */
    val usedSharedCompatibility: Boolean = false,
    /**
     * Per-PWA visual preference. The session manager remains available for deep
     * links and shortcuts even when this app hides the switcher handle.
     */
    val showSwitcherHandle: Boolean = true,
    /**
     * Home-screen organization only. Folder membership never changes WebView
     * profile ownership, cookies, storage, shortcuts, or session lifecycle.
     */
    val folderId: Long? = null,
    val folderOrder: Int = 0
)
