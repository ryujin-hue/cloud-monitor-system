package com.cloudmonitor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<EventLogEntity>)

    @Query("SELECT * FROM event_log ORDER BY occurred_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventLogEntity>>

    @Query("SELECT * FROM event_log WHERE code = :code ORDER BY occurred_at DESC LIMIT :limit")
    fun observeByCode(code: String, limit: Int): Flow<List<EventLogEntity>>

    @Query("SELECT COUNT(*) FROM event_log")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM event_log WHERE occurred_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM event_log")
    suspend fun clear()
}
