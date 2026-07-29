package com.pwa.shell.data.local

import androidx.room.Dao
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

    @Query("SELECT * FROM pwas ORDER BY displayOrder ASC, addedTime DESC")
    suspend fun getAllPwasOnce(): List<PwaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pwa: PwaEntity): Long

    @Update
    suspend fun update(pwa: PwaEntity)

    @Query("DELETE FROM pwas WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pwas SET usedSharedCompatibility = :used WHERE id = :pwaId")
    suspend fun setUsedSharedCompatibility(pwaId: Long, used: Boolean)

    @Query("UPDATE pwas SET displayOrder = :displayOrder WHERE id = :pwaId")
    suspend fun updateDisplayOrder(pwaId: Long, displayOrder: Int)

    @Transaction
    suspend fun batchUpdateDisplayOrder(pwas: List<PwaEntity>) {
        pwas.forEach { pwa ->
            updateDisplayOrder(pwa.id, pwa.displayOrder)
        }
    }
}
