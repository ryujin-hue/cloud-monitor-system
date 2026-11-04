package com.cloudmonitor.shared.dto

import kotlinx.serialization.Serializable

/**
 * Request of `POST /api/register`.
 *
 * A single endpoint serves two purposes, distinguished by the presence of [pairingCode]:
 *
 *  * **without** [pairingCode]: register / refresh this Cloud Alert device and its FCM token.
 *  * **with** [pairingCode]: additionally subscribe to the cloud phone that currently shows
 *    that 6-digit code. One alert device can subscribe to any number of cloud phones.
 */
@Serializable
data class RegisterRequestDto(
    /** Stable id of this Cloud Alert device. */
    val alertDeviceId: String,

    /** Current FCM registration token, or null when Firebase is not configured. */
    val fcmToken: String? = null,

    /** 6-digit pairing code shown by the cloud phone. */
    val pairingCode: String? = null,

    /** Human readable name for this alert device. */
    val label: String = "",

    val appVersion: String = "",

    val versionCode: Long = 0,

    /** Always `android` today; kept explicit so the backend can log the platform. */
    val platform: String = "android",
)

/**
 * Response of `POST /api/register`.
 */
@Serializable
data class RegisterResponseDto(
    val ok: Boolean = false,

    /** Human readable result, suitable for a snackbar. */
    val message: String = "",

    /** Cloud phone ids this alert device is subscribed to after the call. */
    val subscribedDeviceIds: List<String> = emptyList(),

    /** Echo of the alert device id, normalised by the backend. */
    val alertDeviceId: String = "",
)
