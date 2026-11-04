package com.cloudalert.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AlertEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<AlertEventEntity>)

    @Query("SELECT * FROM alert_events ORDER BY occurred_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AlertEventEntity>>

    @Query("SELECT * FROM alert_events WHERE code = :code ORDER BY occurred_at DESC LIMIT :limit")
    fun observeByCode(code: String, limit: Int): Flow<List<AlertEventEntity>>

    @Query("SELECT * FROM alert_events WHERE device_id = :deviceId ORDER BY occurred_at DESC LIMIT :limit")
    fun observeByDevice(deviceId: String, limit: Int): Flow<List<AlertEventEntity>>

    @Query("SELECT COUNT(*) FROM alert_events")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM alert_events WHERE occurred_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM alert_events")
    suspend fun clear()
}

@Dao
interface AlertSubscriptionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(subscription: AlertSubscriptionEntity)

    @Query("DELETE FROM alert_subscriptions WHERE alert_device_id = :alertDeviceId AND monitor_device_id = :monitorDeviceId")
    suspend fun delete(alertDeviceId: String, monitorDeviceId: String)

    @Query("SELECT * FROM alert_subscriptions WHERE alert_device_id = :alertDeviceId")
    fun observeFor(alertDeviceId: String): Flow<List<AlertSubscriptionEntity>>

    @Query("SELECT monitor_device_id FROM alert_subscriptions WHERE alert_device_id = :alertDeviceId")
    suspend fun monitorDeviceIds(alertDeviceId: String): List<String>
}
