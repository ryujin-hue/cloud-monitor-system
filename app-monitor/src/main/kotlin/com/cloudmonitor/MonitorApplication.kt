package com.cloudmonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Also owns the two notification channels used by the app:
 *  * the silent "service" channel that carries the ongoing foreground-service notification,
 *  * the "alerts" channel used for local notifications produced by the monitor itself.
 */
@HiltAndroidApp
class MonitorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val service = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_channel_service_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_alerts_description)
        }

        manager.createNotificationChannels(listOf(service, alerts))
    }

    companion object {
        const val CHANNEL_SERVICE = "monitor_service"
        const val CHANNEL_ALERTS = "monitor_alerts"

        @Volatile
        private var instance: Application? = null

        /** Application instance, used by components that cannot be injected (receivers). */
        fun app(): Application =
            instance ?: error("MonitorApplication.onCreate() has not run yet")
    }
}
