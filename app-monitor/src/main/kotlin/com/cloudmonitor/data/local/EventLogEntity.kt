package com.cloudmonitor.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local history of everything this cloud phone observed.
 *
 * This is the offline copy used by the in-app event log; the authoritative history lives on
 * the backend.
 */
@Entity(
    tableName = "event_log",
    indices = [Index(value = ["occurred_at"]), Index(value = ["code"])],
)
data class EventLogEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    /** DOWN / RECOVERED / HEARTBEAT. */
    @ColumnInfo(name = "type")
    val type: String,

    /** Canonical error code, e.g. `E-101`. */
    @ColumnInfo(name = "code")
    val code: String,

    @ColumnInfo(name = "package_name")
    val packageName: String?,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "seq")
    val seq: Long,

    /** JSON object with the code specific evidence. Added in schema version 2. */
    @ColumnInfo(name = "metadata_json", defaultValue = "{}")
    val metadataJson: String = "{}",

    /** Version of Cloud Monitor that produced the event. Added in schema version 2. */
    @ColumnInfo(name = "app_version", defaultValue = "")
    val appVersion: String = "",

    /** versionCode of Cloud Monitor that produced the event. Added in schema version 2. */
    @ColumnInfo(name = "version_code", defaultValue = "0")
    val versionCode: Long = 0,

    /** Detection confidence. Added in schema version 2. */
    @ColumnInfo(name = "confidence", defaultValue = "")
    val confidence: String = "",

    /** Detection tier. Added in schema version 2. */
    @ColumnInfo(name = "detection", defaultValue = "")
    val detection: String = "",
)
