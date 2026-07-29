package com.pwa.shell.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pwa_folders")
data class PwaFolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val displayOrder: Int,
    val addedTime: Long
)
