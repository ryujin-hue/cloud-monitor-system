package com.cloudmonitor.monitor

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for components the system instantiates itself.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MonitorEntryPoint {
    fun notificationEventBus(): NotificationEventBus
    fun anrReporter(): AnrReporter
}

/**
 * Notification Access service: the primary tier-1 detection channel.
 *
 * What it gives us:
 *  * the removal of an app's ongoing notification, which is the strongest tier-1 signal that
 *    a foreground app just died (an app that is alive keeps its ongoing notification),
 *  * the exact package / id / tag / channel / ongoing flag of Discord's screen-share
 *    notification, discovered through the in-app Notification Inspector instead of guessing.
 */
class MonitorNotificationListener : NotificationListenerService() {

    private companion object {
        const val TAG = "MonitorNotificationListener"
    }

    private lateinit var bus: NotificationEventBus

    override fun onCreate() {
        super.onCreate()
        bus = EntryPointAccessors.fromApplication(
            applicationContext,
            MonitorEntryPoint::class.java,
        ).notificationEventBus()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        reconcileWithActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val snapshot = sbn?.toSnapshot() ?: return
        bus.addActive(snapshot)
        bus.publish(NotificationEvent.Posted(snapshot))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        val snapshot = sbn?.toSnapshot() ?: return
        bus.removeActive(snapshot.key)
        bus.publish(NotificationEvent.Removed(snapshot, reason))
        Log.d(TAG, "removed ${snapshot.key} reason=$reason ongoing=${snapshot.ongoing}")
    }

    /**
     * The system may drop the listener connection (for example when the user revokes
     * Notification Access and grants it again). `requestRebind` asks the platform to bind
     * us again; without it the monitor would stay silent forever.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "notification listener disconnected, requesting rebind")
        runCatching { requestRebind(null) }
            .onFailure { Log.e(TAG, "requestRebind failed", it) }
    }

    /**
     * Reconcile our view with the notifications that are currently posted. This covers the
     * case where the app was restarted (or updated) while a Discord screen share was already
     * running: we would otherwise believe that no share is active.
     */
    private fun reconcileWithActiveNotifications() {
        val active = try {
            activeNotifications
        } catch (e: Throwable) {
            Log.w(TAG, "could not read activeNotifications", e)
            return
        } ?: return

        val snapshots = active.map { it.toSnapshot() }
        bus.setActive(snapshots)
        snapshots.forEach { bus.publish(NotificationEvent.Posted(it)) }
        Log.i(TAG, "reconciled with ${snapshots.size} active notifications")
    }

    private fun StatusBarNotification.toSnapshot(): NotificationSnapshot {
        val extras = notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return NotificationSnapshot(
            packageName = packageName ?: "unknown",
            id = id,
            tag = tag,
            channelId = notification?.channelId,
            ongoing = ((notification?.flags ?: 0) and Notification.FLAG_ONGOING_EVENT) != 0,
            title = title,
            text = text,
            postTime = postTime,
        )
    }
}
