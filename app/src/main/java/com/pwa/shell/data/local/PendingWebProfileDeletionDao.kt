package com.pwa.shell.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingWebProfileDeletionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pending: PendingWebProfileDeletionEntity)

    @Query("SELECT * FROM pending_web_profile_deletions ORDER BY requestedAt ASC")
    suspend fun getAll(): List<PendingWebProfileDeletionEntity>

    @Query("DELETE FROM pending_web_profile_deletions WHERE profileName = :profileName")
    suspend fun delete(profileName: String)
}
