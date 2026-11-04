package com.cloudalert.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.alertDataStore: DataStore<Preferences> by preferencesDataStore(name = "alert_prefs")

/**
 * Settings of the Cloud Alert app.
 */
data class AlertSettings(
    /** Stable id of this alert device. */
    val alertDeviceId: String = "",
    /** Backend API key (encrypted). */
    val apiKey: String = "",
    /** Human readable name of this alert device. */
    val label: String = "",
    /** Current FCM token (encrypted). */
    val fcmToken: String = "",
    /** Cloud phone ids we are subscribed to. */
    val subscribedDeviceIds: Set<String> = emptySet(),
    /** Whether push notifications are enabled in the app UI. */
    val notificationsEnabled: Boolean = true,
    /** Last time the dashboard was refreshed successfully. */
    val lastSyncAt: Long = 0L,
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()
    val isPaired: Boolean get() = subscribedDeviceIds.isNotEmpty()
}

@Singleton
class AlertPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) {

    val settings: Flow<AlertSettings> = context.alertDataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs ->
            AlertSettings(
                alertDeviceId = prefs[KEY_ALERT_DEVICE_ID].orEmpty(),
                apiKey = decrypt(prefs[KEY_API_KEY]),
                label = prefs[KEY_LABEL].orEmpty(),
                fcmToken = decrypt(prefs[KEY_FCM_TOKEN]),
                subscribedDeviceIds = prefs[KEY_SUBSCRIPTIONS].orEmpty(),
                notificationsEnabled = prefs[KEY_NOTIFICATIONS_ENABLED] ?: true,
                lastSyncAt = prefs[KEY_LAST_SYNC_AT] ?: 0L,
            )
        }

    suspend fun current(): AlertSettings = settings.first()

    suspend fun alertDeviceId(): String {
        val existing = context.alertDataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it[KEY_ALERT_DEVICE_ID] }
            .first()
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        context.alertDataStore.edit { it[KEY_ALERT_DEVICE_ID] = generated }
        return generated
    }

    suspend fun setApiKey(value: String) = context.alertDataStore.edit {
        it[KEY_API_KEY] = if (value.isEmpty()) "" else cipher.encrypt(value)
    }

    suspend fun setLabel(value: String) = context.alertDataStore.edit { it[KEY_LABEL] = value }

    suspend fun setFcmToken(value: String) = context.alertDataStore.edit {
        it[KEY_FCM_TOKEN] = if (value.isEmpty()) "" else cipher.encrypt(value)
    }

    suspend fun setSubscriptions(deviceIds: Set<String>) = context.alertDataStore.edit {
        it[KEY_SUBSCRIPTIONS] = deviceIds
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) = context.alertDataStore.edit {
        it[KEY_NOTIFICATIONS_ENABLED] = enabled
    }

    suspend fun setLastSyncAt(timestamp: Long) = context.alertDataStore.edit {
        it[KEY_LAST_SYNC_AT] = timestamp
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return cipher.decrypt(value).orEmpty()
    }

    companion object {
        // ---- Stable preference keys. Never rename, never reuse. ----
        private val KEY_ALERT_DEVICE_ID = stringPreferencesKey("alert_device_id")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_LABEL = stringPreferencesKey("label")
        private val KEY_FCM_TOKEN = stringPreferencesKey("fcm_token")
        private val KEY_SUBSCRIPTIONS = androidx.datastore.preferences.core.stringSetPreferencesKey("subscriptions")
        private val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        private val KEY_LAST_SYNC_AT = androidx.datastore.preferences.core.longPreferencesKey("last_sync_at")
    }
}
