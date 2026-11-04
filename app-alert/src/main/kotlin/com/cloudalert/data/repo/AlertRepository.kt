package com.cloudalert.data.repo

import com.cloudalert.data.local.AlertEventDao
import com.cloudalert.data.local.AlertEventEntity
import com.cloudalert.data.local.AlertSubscriptionDao
import com.cloudalert.data.local.AlertSubscriptionEntity
import com.cloudalert.data.prefs.AlertPreferences
import com.cloudalert.data.remote.AlertApi
import com.cloudalert.data.remote.AlertApiResult
import com.cloudalert.fcm.FcmGateway
import com.cloudmonitor.shared.dto.DeviceStatusDto
import com.cloudmonitor.shared.dto.EventDto
import com.cloudmonitor.shared.dto.RegisterRequestDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All backend interaction of Cloud Alert.
 */
@Singleton
class AlertRepository @Inject constructor(
    private val api: AlertApi,
    private val prefs: AlertPreferences,
    private val eventDao: AlertEventDao,
    private val subscriptionDao: AlertSubscriptionDao,
    private val fcm: FcmGateway,
) {

    /**
     * Register this alert device and (optionally) subscribe to a cloud phone by pairing code.
     *
     * @param pairingCode the 6-digit code shown on the cloud phone, or null to only refresh
     *                    the registration.
     */
    suspend fun register(pairingCode: String? = null): String {
        val settings = prefs.current()
        val alertDeviceId = prefs.alertDeviceId()
        val token = fcm.currentToken()
        if (!token.isNullOrEmpty()) prefs.setFcmToken(token)

        val request = RegisterRequestDto(
            alertDeviceId = alertDeviceId,
            fcmToken = token,
            pairingCode = pairingCode?.trim()?.takeIf { it.isNotEmpty() },
            label = settings.label,
            appVersion = com.cloudalert.BuildConfig.VERSION_NAME,
            versionCode = com.cloudalert.BuildConfig.VERSION_CODE.toLong(),
        )

        return when (val result = api.register(settings.apiKey, request)) {
            is AlertApiResult.Ok -> {
                prefs.setSubscriptions(result.value.subscribedDeviceIds.toSet())
                val subscribed = result.value.subscribedDeviceIds.size
                if (pairingCode.isNullOrEmpty()) {
                    "Registered. $subscribed cloud phone(s) subscribed."
                } else {
                    "Paired. Now subscribed to $subscribed cloud phone(s)."
                }
            }

            is AlertApiResult.Unauthorized -> "The backend rejected the API key."
            is AlertApiResult.Failed -> "Backend error ${result.status}."
            is AlertApiResult.NetworkError -> "Network error: ${result.cause.message}"
        }
    }

    /** Fetch the dashboard state. */
    suspend fun refreshStatus(): Result<List<DeviceStatusDto>> {
        val settings = prefs.current()
        if (!settings.hasApiKey) return Result.failure(IllegalStateException("No API key configured"))
        return when (val result = api.status(settings.apiKey)) {
            is AlertApiResult.Ok -> {
                prefs.setLastSyncAt(System.currentTimeMillis())
                Result.success(result.value.devices)
            }

            is AlertApiResult.Unauthorized -> Result.failure(IllegalStateException("Unauthorized"))
            is AlertApiResult.Failed -> Result.failure(IllegalStateException("HTTP ${result.status}"))
            is AlertApiResult.NetworkError -> Result.failure(result.cause)
        }
    }

    /** Fetch the event history, optionally filtered by device and/or code. */
    suspend fun refreshEvents(
        deviceId: String? = null,
        code: String? = null,
        limit: Int = 200,
    ): Result<List<EventDto>> {
        val settings = prefs.current()
        if (!settings.hasApiKey) return Result.failure(IllegalStateException("No API key configured"))
        return when (val result = api.events(settings.apiKey, deviceId, code, limit = limit)) {
            is AlertApiResult.Ok -> Result.success(result.value.events)
            is AlertApiResult.Unauthorized -> Result.failure(IllegalStateException("Unauthorized"))
            is AlertApiResult.Failed -> Result.failure(IllegalStateException("HTTP ${result.status}"))
            is AlertApiResult.NetworkError -> Result.failure(result.cause)
        }
    }

    /** Persist an event that arrived through FCM (or polling) into the local history. */
    suspend fun storeEvent(event: EventDto, deviceLabel: String = "") {
        eventDao.insert(
            AlertEventEntity(
                eventId = event.eventId,
                deviceId = event.deviceId,
                deviceLabel = deviceLabel,
                type = event.type.name,
                code = event.code,
                packageName = event.packageName,
                occurredAt = event.occurredAt,
                receivedAt = event.receivedAt,
                seq = event.seq,
                appVersion = event.appVersion,
                versionCode = event.versionCode,
            ),
        )
    }

    /** Subscribe to a cloud phone locally (after a successful backend registration). */
    suspend fun rememberSubscription(monitorDeviceId: String) {
        val alertDeviceId = prefs.alertDeviceId()
        subscriptionDao.insert(
            AlertSubscriptionEntity(
                alertDeviceId = alertDeviceId,
                monitorDeviceId = monitorDeviceId,
                subscribedAt = System.currentTimeMillis(),
                label = "",
            ),
        )
    }

    /** Unsubscribe from a cloud phone. */
    suspend fun forgetSubscription(monitorDeviceId: String) {
        val alertDeviceId = prefs.alertDeviceId()
        subscriptionDao.delete(alertDeviceId, monitorDeviceId)
        val remaining = prefs.current().subscribedDeviceIds - monitorDeviceId
        prefs.setSubscriptions(remaining)
    }

    /** The FCM token is invalid: drop it so the backend stops using it. */
    suspend fun onFcmTokenInvalid() {
        fcm.deleteToken()
        prefs.setFcmToken("")
    }
}
