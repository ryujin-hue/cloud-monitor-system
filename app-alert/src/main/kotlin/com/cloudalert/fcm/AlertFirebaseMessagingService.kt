package com.cloudalert.fcm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cloudalert.AlertApplication
import com.cloudalert.R
import com.cloudalert.ui.MainActivity
import com.cloudmonitor.shared.ErrorCode
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives DATA-ONLY FCM messages and builds the notification itself.
 *
 * Data-only messages are used on purpose: the wording, the channel and the deep link stay
 * under our control, and nothing but the delivery itself ever touches Firebase.
 */
@AndroidEntryPoint
class AlertFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AlertFCM"

        const val KEY_TYPE = "type"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_DEVICE_LABEL = "deviceLabel"
        const val KEY_CODE = "code"
        const val KEY_PACKAGE = "packageName"
        const val KEY_EVENT_ID = "eventId"
        const val KEY_OCCURRED_AT = "occurredAt"
        const val KEY_RECEIVED_AT = "receivedAt"
        const val KEY_TITLE = "title"
        const val KEY_MESSAGE = "message"
        const val KEY_SEVERITY = "severity"
    }

    @Inject
    lateinit var notifier: AlertNotifier

    @Inject
    lateinit var tokenRefresher: TokenRefresher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        Log.i(TAG, "push received: ${message.data}")
        notifier.notify(message.data)
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated")
        scope.launch { tokenRefresher.onNewToken(token) }
    }
}

/**
 * Builds and posts the alert notifications.
 */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "AlertNotifier"
        const val NOTIFICATION_ID_BASE = 9000
    }

    /** Post a notification for a push payload. */
    fun notify(data: Map<String, String>) {
        val code = data[AlertFirebaseMessagingService.KEY_CODE].orEmpty()
        val codeEnum = ErrorCode.fromWire(code)
        val title = data[AlertFirebaseMessagingService.KEY_TITLE]
            ?: codeEnum?.title
            ?: "Cloud alert"
        val message = data[AlertFirebaseMessagingService.KEY_MESSAGE].orEmpty()
        val deviceLabel = data[AlertFirebaseMessagingService.KEY_DEVICE_LABEL].orEmpty()
        val eventId = data[AlertFirebaseMessagingService.KEY_EVENT_ID].orEmpty()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("eventId", eventId)
        }
        val pending = PendingIntent.getActivity(
            context,
            eventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, AlertApplication.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(if (deviceLabel.isBlank()) title else "$deviceLabel · $title")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = NOTIFICATION_ID_BASE + (eventId.hashCode() and 0xFFF)
        runCatching { manager.notify(id, notification) }
            .onFailure { Log.e(TAG, "could not post notification", it) }
    }
}

/**
 * Keeps the FCM token registered with the backend.
 *
 * FCM rotates tokens; whenever that happens the backend must be told, otherwise pushes
 * stop arriving. Tokens that the backend reports as UNREGISTERED are deleted on the device
 * so that they are not retried.
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val repository: com.cloudalert.data.repo.AlertRepository,
    private val prefs: com.cloudalert.data.prefs.AlertPreferences,
) {

    private companion object {
        const val TAG = "TokenRefresher"
    }

    /** A new token arrived: store it and re-register with the backend. */
    suspend fun onNewToken(token: String) {
        prefs.setFcmToken(token)
        val settings = prefs.current()
        if (!settings.hasApiKey) {
            Log.i(TAG, "new token stored, but no API key configured yet")
            return
        }
        val message = repository.register()
        Log.i(TAG, "re-registered after token rotation: $message")
    }

    /** The backend told us the token is invalid. */
    suspend fun onTokenInvalid() {
        Log.w(TAG, "FCM token rejected by the backend, deleting it")
        repository.onFcmTokenInvalid()
    }
}
