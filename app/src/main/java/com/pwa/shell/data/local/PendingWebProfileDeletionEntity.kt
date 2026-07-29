package com.pwa.shell.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_web_profile_deletions")
data class PendingWebProfileDeletionEntity(
    @PrimaryKey val profileName: String,
    val requestedAt: Long = System.currentTimeMillis()
)
