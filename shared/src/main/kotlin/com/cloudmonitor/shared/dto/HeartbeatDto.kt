package com.cloudmonitor.shared.dto

import kotlinx.serialization.Serializable

/**
 * Which of the special permissions the cloud phone currently has.
 *
 * Reported with every heartbeat so that Cloud Alert can tell "the cloud phone is fine but
 * someone revoked Notification Access" apart from "the cloud phone died".
 */
@Serializable
data class PermissionStateDto(
    /** Notification Access (NotificationListenerService) granted. */
    val notificationAccess: Boolean = false,

    /** Usage Access granted (UsageStatsManager). */
    val usageAccess: Boolean = false,

    /** The app is exempt from battery optimisation. */
    val batteryUnrestricted: Boolean = false,

    /** The app may post notifications (Android 13+ runtime permission). */
    val notificationsEnabled: Boolean = false,
)

/**
 * Liveness signal sent to `POST /api/heartbeat`.
 *
 * The heartbeat also doubles as the pairing channel: [pairingCode] is the 6-digit code that
 * Cloud Alert types in to subscribe to this cloud phone.
 */
@Serializable
data class HeartbeatDto(
    val deviceId: String,

    /** Device wall-clock time (epoch millis). */
    val occurredAt: Long,

    /** Monotonic per-device counter. */
    val seq: Long,

    val appVersion: String = "",
    val versionCode: Long = 0,

    /** Human readable name for the cloud phone, shown in Cloud Alert. */
    val label: String = "",

    /** True while the foreground service is alive. */
    val foregroundServiceActive: Boolean = true,

    /** Device uptime in millis. A decreasing value means the phone rebooted. */
    val uptimeMillis: Long = 0,

    /** False when the phone currently has no usable network. */
    val networkAvailable: Boolean = true,

    val permissions: PermissionStateDto = PermissionStateDto(),

    /** Packages the cloud phone is currently watching. */
    val watchedPackages: List<String> = emptyList(),

    /** Current 6-digit pairing code, or null when pairing is disabled. */
    val pairingCode: String? = null,

    /** Epoch millis at which [pairingCode] stops being valid. */
    val pairingExpiresAt: Long? = null,
)
