package com.cloudmonitor.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import javax.inject.Inject

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

        /** True while the service is running. Used by the watchdog. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Start (or restart) the service. Safe to call from a receiver. */
        fun start(context: Context) {
            val intent = Intent(context, MonitorForegroundService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "could not start foreground service", it) }
        }

        /** Stop the service. */
        fun stop(context: Context) {
            val intent = Intent(context, MonitorForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.e(TAG, "could not stop foreground service", it) }
        }
    }

    @Inject
    lateinit var runtime: MonitorRuntime

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundCompat()
        runtime.start(scope)
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
        runtime.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
