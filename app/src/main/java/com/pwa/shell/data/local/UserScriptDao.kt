package com.pwa.shell.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserScriptDao {
    @Query("SELECT * FROM user_scripts WHERE pwaId = :pwaId ORDER BY sortOrder ASC, id ASC")
    fun getScriptsForPwaFlow(pwaId: Long): Flow<List<UserScriptEntity>>

    @Query("SELECT * FROM user_scripts WHERE pwaId = :pwaId ORDER BY sortOrder ASC, id ASC")
    suspend fun getScriptsForPwa(pwaId: Long): List<UserScriptEntity>

    @Query("SELECT * FROM user_scripts WHERE id = :id")
    suspend fun getScriptById(id: Long): UserScriptEntity?

    @Query("SELECT * FROM user_scripts WHERE pwaId = :pwaId AND name = :name LIMIT 1")
    suspend fun getScriptByName(pwaId: Long, name: String): UserScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(script: UserScriptEntity): Long

    @Update
    suspend fun update(script: UserScriptEntity)

    @Delete
    suspend fun delete(script: UserScriptEntity)

    @Query("UPDATE user_scripts SET enabled = :enabled WHERE id = :id")
    suspend fun toggleEnabled(id: Long, enabled: Boolean)

    @Transaction
    suspend fun updateSortOrders(scripts: List<UserScriptEntity>) {
        scripts.forEach { script ->
            update(script)
        }
    }
}
