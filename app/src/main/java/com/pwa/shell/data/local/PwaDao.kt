package com.pwa.shell.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PwaDao {
    @Query("SELECT * FROM pwas ORDER BY displayOrder ASC, addedTime DESC")
    fun getAllPwas(): Flow<List<PwaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pwa: PwaEntity): Long

    @Update
    suspend fun update(pwa: PwaEntity)

    @Delete
    suspend fun delete(pwa: PwaEntity)

    @Transaction
    suspend fun batchUpdateDisplayOrder(pwas: List<PwaEntity>) {
        pwas.forEach { pwa ->
            update(pwa)
        }
    }
}
