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
    val useFullscreen: Boolean = false
)
