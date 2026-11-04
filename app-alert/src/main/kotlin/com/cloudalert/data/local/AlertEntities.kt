package com.cloudalert.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One event received from a cloud phone, either pushed or polled.
 */
@Entity(
    tableName = "alert_events",
    indices = [Index(value = ["occurred_at"]), Index(value = ["code"]), Index(value = ["device_id"])],
)
data class AlertEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "device_label")
    val deviceLabel: String = "",

    /** DOWN / RECOVERED / HEARTBEAT. */
    @ColumnInfo(name = "type")
    val type: String,

    /** Canonical error code, e.g. `E-101`. */
    @ColumnInfo(name = "code")
    val code: String,

    @ColumnInfo(name = "package_name")
    val packageName: String?,

    /** Device timestamp of the observation. */
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    /** Server timestamp of the delivery. */
    @ColumnInfo(name = "received_at")
    val receivedAt: Long?,

    @ColumnInfo(name = "seq")
    val seq: Long,

    /** Version of Cloud Monitor on the cloud phone. Added in schema version 2. */
    @ColumnInfo(name = "app_version", defaultValue = "")
    val appVersion: String = "",

    /** versionCode of Cloud Monitor on the cloud phone. Added in schema version 2. */
    @ColumnInfo(name = "version_code", defaultValue = "0")
    val versionCode: Long = 0,
)

/**
 * A cloud phone this alert device is subscribed to.
 */
@Entity(
    tableName = "alert_subscriptions",
    primaryKeys = ["alert_device_id", "monitor_device_id"],
)
data class AlertSubscriptionEntity(
    @ColumnInfo(name = "alert_device_id")
    val alertDeviceId: String,

    @ColumnInfo(name = "monitor_device_id")
    val monitorDeviceId: String,

    @ColumnInfo(name = "subscribed_at")
    val subscribedAt: Long,

    /** Name of the cloud phone at the time of the pairing. Added in schema version 2. */
    @ColumnInfo(name = "label", defaultValue = "")
    val label: String = "",
)
