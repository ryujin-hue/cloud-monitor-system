package com.cloudmonitor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QueuedEventDao {

    /**
     * Returns the row id, or -1 when the row already existed (IGNORE strategy).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: QueuedEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<QueuedEventEntity>)

    @Update
    suspend fun update(event: QueuedEventEntity)

    @Query("SELECT * FROM queued_events WHERE next_attempt_at <= :now ORDER BY created_at ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<QueuedEventEntity>

    @Query("DELETE FROM queued_events WHERE event_id IN (:eventIds)")
    suspend fun deleteByIds(eventIds: List<String>)

    @Query("DELETE FROM queued_events WHERE event_id = :eventId")
    suspend fun deleteById(eventId: String)

    @Query("SELECT COUNT(*) FROM queued_events")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM queued_events")
    suspend fun count(): Int

    /** Removes the oldest rows when the queue overflows. */
    @Query("DELETE FROM queued_events WHERE event_id IN (SELECT event_id FROM queued_events ORDER BY created_at ASC LIMIT :limit)")
    suspend fun deleteOldest(limit: Int)

    @Query("SELECT * FROM queued_events ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<QueuedEventEntity>>
}
