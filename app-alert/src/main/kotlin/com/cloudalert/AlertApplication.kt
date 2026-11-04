package com.cloudalert

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for Cloud Alert.
 *
 * Firebase is optional: the app builds and runs without `google-services.json`, in which
 * case push delivery is disabled and the dashboard simply polls the backend.
 */
@HiltAndroidApp
class AlertApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    getString(R.string.notification_channel_alerts),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.notification_channel_alerts_description)
                    enableVibration(true)
                },
                NotificationChannel(
                    CHANNEL_SERVICE,
                    getString(R.string.notification_channel_service),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = getString(R.string.notification_channel_service_description)
                    setShowBadge(false)
                    setSound(null, null)
                },
            ),
        )
    }

    companion object {
        const val CHANNEL_ALERTS = "cloud_alerts"
        const val CHANNEL_SERVICE = "alert_service"

        @Volatile
        private var instance: Application? = null

        fun app(): Application = instance ?: error("AlertApplication.onCreate() has not run yet")
    }
}
