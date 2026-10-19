package com.callnotes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {

    @Query("SELECT * FROM calls ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CallEntity>>

    @Insert
    suspend fun insert(call: CallEntity): Long

    @Query("DELETE FROM calls WHERE id = :id")
    suspend fun delete(id: Long)
}
