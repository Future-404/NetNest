package com.pwa.shell.data.local

import androidx.room.Entity

@Entity(tableName = "script_storage", primaryKeys = ["pwaId", "storageKey"])
data class ScriptStorageEntity(
    val pwaId: Long,
    val storageKey: String,
    val storageValue: String
)
