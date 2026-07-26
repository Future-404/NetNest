package com.pwa.shell.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pwas")
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
    val trustedDomains: String = "", // Comma-separated trusted hosts
    val customUserAgent: String? = null,
    val customLanguage: String = "",
    val customPlatform: String = "",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val deviceScaleFactor: Float = 0f
)
