package com.cloudmonitor.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One event waiting to be delivered to the backend.
 *
 * The payload is the serialised [com.cloudmonitor.shared.dto.EventDto]. It is stored as JSON
 * rather than in typed columns so that adding a field to the DTO never requires a schema
 * migration for the outbox.
 */
@Entity(
    tableName = "queued_events",
    indices = [Index(value = ["next_attempt_at"])],
)
data class QueuedEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "payload")
    val payload: String,

    /** Device timestamp of the observation (epoch millis). */
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    /** Device timestamp of the moment the event was queued. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** Number of delivery attempts so far. */
    @ColumnInfo(name = "attempts")
    val attempts: Int,

    /** Earliest epoch millis at which the next attempt may run. */
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long,

    /**
     * PENDING / IN_FLIGHT / FAILED.
     *
     * Added in schema version 2. The default keeps the rows that were written by version 1
     * usable without a data rewrite.
     */
    @ColumnInfo(name = "state", defaultValue = "PENDING")
    val state: String = STATE_PENDING,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_IN_FLIGHT = "IN_FLIGHT"
        const val STATE_FAILED = "FAILED"
    }
}
