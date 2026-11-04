package com.cloudmonitor.data.prefs

import android.content.Context
import com.cloudmonitor.BuildConfig
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

private val Context.monitorDataStore: DataStore<Preferences> by preferencesDataStore(name = "monitor_prefs")

/**
 * Everything the cloud phone remembers between restarts.
 *
 * The preference keys are STABLE: they are never renamed or reused, because renaming a key
 * silently loses user configuration on an in-place update.
 */
data class MonitorSettings(
    /** Stable id of this cloud phone. Generated once and then never changed. */
    val deviceId: String = "",
    /** Human readable name shown in Cloud Alert. */
    val label: String = "",
    /** Backend API key (stored encrypted). */
    val apiKey: String = "",
    /** Packages this cloud phone watches. */
    val watchedPackages: Set<String> = emptySet(),
    /** Package name of Discord, discovered through the Notification Inspector. */
    val discordPackage: String? = null,
    /** Notification id of the Discord screen-share notification. */
    val discordNotificationId: Int? = null,
    /** Notification tag of the Discord screen-share notification. */
    val discordNotificationTag: String? = null,
    /** Notification channel id of the Discord screen-share notification. */
    val discordChannelId: String? = null,
    /** Heartbeat period in seconds. */
    val heartbeatIntervalSec: Int = 60,
    /** Whether a pairing code is generated and advertised. */
    val pairingEnabled: Boolean = true,
    /** Current 6-digit pairing code, if still valid. */
    val pairingCode: String? = null,
    /** Epoch millis at which the pairing code expires. */
    val pairingExpiresAt: Long? = null,
    /** Monotonic event counter. */
    val sequence: Long = 0L,
    /** Version of Cloud Monitor, e.g. `1.0.42`. Reported with every heartbeat/event. */
    val appVersion: String = "",
    /** versionCode of Cloud Monitor. Reported with every heartbeat/event. */
    val versionCode: Long = 0L,
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()
    val hasDiscordShareTarget: Boolean
        get() = discordPackage != null && discordNotificationId != null
}

/**
 * DataStore-backed preferences for Cloud Monitor.
 */
@Singleton
class MonitorPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) {

    val settings: Flow<MonitorSettings> = context.monitorDataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> prefs.toSettings() }

    suspend fun current(): MonitorSettings = settings.first()

    /**
     * Returns the persisted device id, creating it on first use.
     *
     * The id is written once and then preserved forever, which is what lets Cloud Alert keep
     * track of the same cloud phone across app updates.
     */
    suspend fun deviceId(): String {
        val existing = context.monitorDataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it[KEY_DEVICE_ID] }
            .first()
        if (!existing.isNullOrBlank()) return existing

        val generated = UUID.randomUUID().toString()
        context.monitorDataStore.edit { it[KEY_DEVICE_ID] = generated }
        return generated
    }

    suspend fun setLabel(value: String) = edit { it[KEY_LABEL] = value }

    suspend fun setApiKey(value: String) = edit {
        it[KEY_API_KEY] = if (value.isEmpty()) "" else cipher.encrypt(value)
    }

    suspend fun setWatchedPackages(packages: Set<String>) = edit {
        it[KEY_WATCHED_PACKAGES] = packages
    }

    suspend fun setDiscordTarget(
        packageName: String?,
        notificationId: Int?,
        notificationTag: String?,
        channelId: String?,
    ) = edit {
        if (packageName == null) {
            it.remove(KEY_DISCORD_PACKAGE)
            it.remove(KEY_DISCORD_NOTIFICATION_ID)
            it.remove(KEY_DISCORD_NOTIFICATION_TAG)
            it.remove(KEY_DISCORD_CHANNEL_ID)
        } else {
            it[KEY_DISCORD_PACKAGE] = packageName
            if (notificationId != null) it[KEY_DISCORD_NOTIFICATION_ID] = notificationId else it.remove(KEY_DISCORD_NOTIFICATION_ID)
            if (notificationTag != null) it[KEY_DISCORD_NOTIFICATION_TAG] = notificationTag else it.remove(KEY_DISCORD_NOTIFICATION_TAG)
            if (channelId != null) it[KEY_DISCORD_CHANNEL_ID] = channelId else it.remove(KEY_DISCORD_CHANNEL_ID)
        }
    }

    suspend fun setHeartbeatInterval(seconds: Int) = edit {
        it[KEY_HEARTBEAT_INTERVAL] = seconds.coerceIn(MIN_HEARTBEAT_SEC, MAX_HEARTBEAT_SEC)
    }

    suspend fun setPairingEnabled(enabled: Boolean) = edit { it[KEY_PAIRING_ENABLED] = enabled }

    /** Stores a freshly generated pairing code and its expiry. */
    suspend fun setPairingCode(code: String?, expiresAt: Long?) = edit {
        if (code == null) {
            it.remove(KEY_PAIRING_CODE)
            it.remove(KEY_PAIRING_EXPIRES_AT)
        } else {
            it[KEY_PAIRING_CODE] = code
            it[KEY_PAIRING_EXPIRES_AT] = expiresAt ?: 0L
        }
    }

    /** The pairing code, or null when pairing is disabled or the code expired. */
    suspend fun activePairingCode(now: Long = System.currentTimeMillis()): String? {
        val settings = current()
        if (!settings.pairingEnabled) return null
        val code = settings.pairingCode ?: return null
        val expiresAt = settings.pairingExpiresAt ?: return null
        return if (expiresAt > now) code else null
    }

    /** Atomically increments and returns the next event sequence number. */
    suspend fun nextSequence(): Long {
        var next = 0L
        context.monitorDataStore.edit { prefs ->
            val current = prefs[KEY_SEQUENCE] ?: 0L
            next = current + 1
            prefs[KEY_SEQUENCE] = next
        }
        return next
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.monitorDataStore.edit(block)
    }

    private fun Preferences.toSettings(): MonitorSettings {
        val encrypted = this[KEY_API_KEY].orEmpty()
        val decrypted = if (encrypted.isEmpty()) "" else cipher.decrypt(encrypted).orEmpty()
        return MonitorSettings(
            deviceId = this[KEY_DEVICE_ID].orEmpty(),
            label = this[KEY_LABEL].orEmpty(),
            apiKey = decrypted,
            watchedPackages = this[KEY_WATCHED_PACKAGES].orEmpty(),
            discordPackage = this[KEY_DISCORD_PACKAGE],
            discordNotificationId = this[KEY_DISCORD_NOTIFICATION_ID],
            discordNotificationTag = this[KEY_DISCORD_NOTIFICATION_TAG],
            discordChannelId = this[KEY_DISCORD_CHANNEL_ID],
            heartbeatIntervalSec = this[KEY_HEARTBEAT_INTERVAL] ?: 60,
            pairingEnabled = this[KEY_PAIRING_ENABLED] ?: true,
            pairingCode = this[KEY_PAIRING_CODE],
            pairingExpiresAt = this[KEY_PAIRING_EXPIRES_AT],
            sequence = this[KEY_SEQUENCE] ?: 0L,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
        )
    }

    companion object {
        const val MIN_HEARTBEAT_SEC = 15
        const val MAX_HEARTBEAT_SEC = 600

        // ---- Stable preference keys. Never rename, never reuse. ----
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_LABEL = stringPreferencesKey("label")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_WATCHED_PACKAGES = stringSetPreferencesKey("watched_packages")
        private val KEY_DISCORD_PACKAGE = stringPreferencesKey("discord_package")
        private val KEY_DISCORD_NOTIFICATION_ID = intPreferencesKey("discord_notif_id")
        private val KEY_DISCORD_NOTIFICATION_TAG = stringPreferencesKey("discord_notif_tag")
        private val KEY_DISCORD_CHANNEL_ID = stringPreferencesKey("discord_channel_id")
        private val KEY_HEARTBEAT_INTERVAL = intPreferencesKey("heartbeat_interval_sec")
        private val KEY_PAIRING_ENABLED = booleanPreferencesKey("pairing_enabled")
        private val KEY_PAIRING_CODE = stringPreferencesKey("pairing_code")
        private val KEY_PAIRING_EXPIRES_AT = longPreferencesKey("pairing_expires_at")
        private val KEY_SEQUENCE = longPreferencesKey("seq")
    }
}
