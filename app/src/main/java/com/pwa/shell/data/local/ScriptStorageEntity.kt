package com.pwa.shell.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "script_storage",
    primaryKeys = ["pwaId", "storageKey"],
    foreignKeys = [
        ForeignKey(
            entity = PwaEntity::class,
            parentColumns = ["id"],
            childColumns = ["pwaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pwaId")]
)
data class ScriptStorageEntity(
    val pwaId: Long,
    val storageKey: String,
    val storageValue: String
)
