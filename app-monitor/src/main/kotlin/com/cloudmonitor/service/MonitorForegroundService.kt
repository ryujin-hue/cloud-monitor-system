package com.cloudmonitor.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cloudmonitor.MonitorApplication
import com.cloudmonitor.R
import com.cloudmonitor.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CancellationException
import javax.inject.Inject

/**
 * The result of asking the platform to start the foreground service.
 *
 * `Context.startForegroundService()` only *requests* a start; on Android 12+ the system
 * can refuse it (background start restriction) or the service can fail inside
 * `startForeground()`. Callers must not claim success until [isRunning] confirms it.
 */
data class StartOutcome(val requested: Boolean, val message: String)

/** The result of asking the platform to stop the foreground service. */
data class StopOutcome(val requested: Boolean, val message: String)

/**
 * Keeps the monitor alive.
 *
 * * `START_STICKY` so the platform restarts us after a low-memory kill.
 * * `foregroundServiceType="specialUse"` with a justification, as required on Android 14+.
 * * A silent, ongoing notification that proves the service is alive to the user.
 *
 * The service itself is intentionally thin: it owns the lifecycle and the notification, and
 * delegates everything else to [MonitorRuntime].
 */
@AndroidEntryPoint
class MonitorForegroundService : Service() {

    companion object {
        private const val TAG = "MonitorForegroundService"

        const val ACTION_STOP = "com.cloudmonitor.action.STOP"

        /** Id of the ongoing notification. */
        const val NOTIFICATION_ID = 4711

        /** True only after `startForeground()` succeeded. Used by the watchdog and the UI. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Last reason the service could not enter the foreground. The UI reads this so it
         * never shows "Monitor started" when the platform actually refused the start.
         */
        @Volatile
        var lastStartFailure: String? = null
            private set

        /**
         * Start (or restart) the service. Safe to call from a receiver; the returned
         * [StartOutcome] explains any restriction the platform imposed.
         */
        fun start(context: Context): StartOutcome {
            val intent = Intent(context, MonitorForegroundService::class.java)
            return try {
                ContextCompat.startForegroundService(context, intent)
                val warning = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    " Notification permission is off: enable it in Settings so the status " +
                        "notification and Android 16 task-manager entry are visible."
                } else {
                    ""
                }
                StartOutcome(true, "Monitor start requested.$warning")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastStartFailure = describeStartFailure(e)
                Log.e(TAG, "could not start foreground service: ${lastStartFailure}", e)
                StartOutcome(false, lastStartFailure!!)
            }
        }

        /** Stop the service. */
        fun stop(context: Context): StopOutcome = try {
            val intent = Intent(context, MonitorForegroundService::class.java)
            val requested = context.stopService(intent)
            StopOutcome(
                requested,
                if (requested) "Monitor stop requested" else "Monitor was not running",
            )
        } catch (e: Throwable) {
            Log.e(TAG, "could not stop foreground service", e)
            StopOutcome(false, "Could not stop the monitor: ${e.message ?: "unknown error"}")
        }

        /**
         * Translate a platform exception into an actionable, user-readable message.
         * Referencing `ForegroundServiceStartNotAllowedException` by name (rather than by
         * type) keeps this safe on API < 31 where the class does not exist.
         */
        private fun describeStartFailure(error: Throwable): String = when {
            error.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException" ->
                "Android blocked the monitor because it was started from the background. " +
                    "Open Cloud Monitor and tap Start, or set the app's battery usage to " +
                    "Unrestricted (Settings → Apps → Cloud Monitor → Battery)."

            error is SecurityException ->
                "A required permission is missing (FOREGROUND_SERVICE / POST_NOTIFICATIONS). " +
                    "Reinstall Cloud Monitor or grant the permission in Settings."

            error is IllegalStateException && (error.message?.contains("background", true) == true) ->
                "Android 12+ does not allow a foreground service to start from the background. " +
                    "Open Cloud Monitor and tap Start."

            else -> "The monitor service failed to start: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    @Inject
    lateinit var runtime: MonitorRuntime

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        try {
            // Must happen within seconds of startForegroundService(), before anything else.
            startForegroundCompat()
            isRunning = true
            lastStartFailure = null
            runtime.start(scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // An exception here used to leave `isRunning = true` forever while the service
            // was actually dead, so the dashboard lied about the monitoring state.
            isRunning = false
            lastStartFailure = describeStartFailure(e)
            Log.e(TAG, "foreground service startup failed: ${lastStartFailure}", e)
            try {
                stopSelf()
            } catch (_: Throwable) {
                // Nothing else we can do; the UI will surface lastStartFailure.
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        // Restart if the system kills us.
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            runtime.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "runtime stop failed", e)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        ensureNotificationChannel()
        val notification = buildNotification()
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE was introduced in API 34. Passing it to the
        // 3-argument startForeground() on API 29-33 throws IllegalArgumentException
        // ("Invalid foreground service type"), so use the 2-argument overload there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** The Application normally creates channels; recreate here in case it did not run. */
    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(MonitorApplication.CHANNEL_SERVICE) != null) return
        val channel = NotificationChannel(
            MonitorApplication.CHANNEL_SERVICE,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_channel_service_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, MonitorApplication.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Watching selected apps and Discord screen share")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .build()
    }
}
