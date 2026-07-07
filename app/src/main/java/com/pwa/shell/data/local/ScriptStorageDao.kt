package com.pwa.shell.data.local

import androidx.room.*

@Dao
interface ScriptStorageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(storage: ScriptStorageEntity)

    @Query("SELECT * FROM script_storage WHERE pwaId = :pwaId AND storageKey = :key LIMIT 1")
    fun get(pwaId: Long, key: String): ScriptStorageEntity?

    @Query("DELETE FROM script_storage WHERE pwaId = :pwaId AND storageKey = :key")
    fun delete(pwaId: Long, key: String)

    @Query("SELECT storageKey FROM script_storage WHERE pwaId = :pwaId")
    fun listKeys(pwaId: Long): List<String>
}
