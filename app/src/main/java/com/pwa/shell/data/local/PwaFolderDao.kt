package com.pwa.shell.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PwaFolderDao {
    @Query("SELECT * FROM pwa_folders ORDER BY displayOrder ASC, addedTime ASC")
    fun getAllFolders(): Flow<List<PwaFolderEntity>>

    @Query("SELECT * FROM pwa_folders ORDER BY displayOrder ASC, addedTime ASC")
    suspend fun getAllFoldersOnce(): List<PwaFolderEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: PwaFolderEntity): Long

    @Query("UPDATE pwa_folders SET name = :name WHERE id = :folderId")
    suspend fun rename(folderId: Long, name: String)

    @Query("UPDATE pwa_folders SET displayOrder = :displayOrder WHERE id = :folderId")
    suspend fun updateDisplayOrder(folderId: Long, displayOrder: Int)

    @Delete
    suspend fun delete(folder: PwaFolderEntity)

    @Query("DELETE FROM pwa_folders WHERE id = :folderId")
    suspend fun deleteById(folderId: Long)
}
