package com.cloudmonitor.service

import com.cloudmonitor.data.prefs.MonitorPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Generates and rotates the 6-digit pairing code.
 *
 * The code is shown on the cloud phone and typed into Cloud Alert. It is time limited, so a
 * photo of the screen left around on a chat is not enough to hijack the cloud phone.
 */
@Singleton
class PairingCodeProvider @Inject constructor(
    private val prefs: MonitorPreferences,
) {

    companion object {
        /**
         * How long a generated code stays valid.
         *
         * 30 minutes, because every rotation writes the device record to KV and the KV
         * free plan only allows 1000 writes per day for the whole backend.
         */
        const val VALIDITY_MILLIS = 30 * 60 * 1000L

        /** Number of digits. */
        const val DIGITS = 6
    }

    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    /**
     * Return the current code, generating a new one when there is none or it expired.
     */
    suspend fun ensureCode(now: Long = System.currentTimeMillis()): String? {
        val settings = prefs.current()
        if (!settings.pairingEnabled) {
            _code.value = null
            return null
        }
        val existing = settings.pairingCode
        val expiresAt = settings.pairingExpiresAt
        if (existing != null && expiresAt != null && expiresAt > now) {
            _code.value = existing
            return existing
        }
        return rotate(now)
    }

    /** Generate and store a brand new code. */
    suspend fun rotate(now: Long = System.currentTimeMillis()): String {
        val generated = generateCode()
        prefs.setPairingCode(generated, now + VALIDITY_MILLIS)
        _code.value = generated
        return generated
    }

    /** Disable pairing entirely. */
    suspend fun disable() {
        prefs.setPairingEnabled(false)
        prefs.setPairingCode(null, null)
        _code.value = null
    }

    private fun generateCode(): String = buildString {
        repeat(DIGITS) { append(Random.nextInt(0, 10)) }
    }
}
