package com.cloudalert.fcm

import android.content.Context
import android.util.Log
import com.cloudmonitor.shared.CancellationGuard
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Access to Firebase Cloud Messaging.
 *
 * The whole app must build and run WITHOUT `google-services.json`. In that case Firebase is
 * never initialised, so this gateway reports itself as unavailable and the app falls back to
 * polling the backend.
 */
interface FcmGateway {
    /** Whether Firebase is initialised and usable. */
    val isAvailable: Boolean

    /** The current registration token, or null. */
    suspend fun currentToken(): String?

    /** Delete the token (used when the backend rejects it). */
    suspend fun deleteToken()
}

@Singleton
class FirebaseFcmGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : FcmGateway {

    companion object {
        private const val TAG = "FirebaseFcmGateway"
    }

    private val messaging: FirebaseMessaging? = runCatching {
        FirebaseMessaging.getInstance()
    }.getOrElse {
        Log.w(TAG, "Firebase is not initialised (no google-services.json?): ${it.message}")
        null
    }

    override val isAvailable: Boolean get() = messaging != null

    override suspend fun currentToken(): String? {
        val service = messaging ?: return null
        return try {
            suspendCancellableCoroutine { continuation ->
                service.token.addOnCompleteListener { task ->
                    // The callback can arrive after the coroutine was cancelled; resume only
                    // while it is still active to avoid an "already resumed" crash.
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) continuation.resume(task.result)
                    else continuation.resume(null)
                }
            }
        } catch (e: Throwable) {
            CancellationGuard.rethrowCancellation(e)
            Log.w(TAG, "could not read the FCM token: ${e.message}")
            null
        }
    }

    override suspend fun deleteToken() {
        try {
            messaging?.deleteToken()
        } catch (e: Throwable) {
            CancellationGuard.rethrowCancellation(e)
            Log.w(TAG, "could not delete the FCM token", e)
        }
    }
}

/**
 * No-op implementation used when Firebase is not configured.
 */
@Singleton
class NoopFcmGateway @Inject constructor() : FcmGateway {
    override val isAvailable: Boolean = false
    override suspend fun currentToken(): String? = null
    override suspend fun deleteToken() = Unit
}
