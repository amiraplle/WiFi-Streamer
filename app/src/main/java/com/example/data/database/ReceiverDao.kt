package com.example.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiverDao {

    @Query("SELECT * FROM receivers ORDER BY isDefault DESC, lastConnected DESC, id DESC")
    fun getAllReceivers(): Flow<List<ReceiverEntity>>

    @Query("SELECT * FROM receivers WHERE id = :id LIMIT 1")
    suspend fun getReceiverById(id: Long): ReceiverEntity?

    @Query("SELECT * FROM receivers WHERE host = :host LIMIT 1")
    suspend fun getReceiverByHost(host: String): ReceiverEntity?

    @Query("SELECT * FROM receivers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultReceiver(): ReceiverEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceiver(receiver: ReceiverEntity): Long

    @Update
    suspend fun updateReceiver(receiver: ReceiverEntity)

    @Delete
    suspend fun deleteReceiver(receiver: ReceiverEntity)

    @Query("UPDATE receivers SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE receivers SET isDefault = 1 WHERE id = :id")
    suspend fun setDefaultReceiver(id: Long)

    @Query("UPDATE receivers SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long)
}
