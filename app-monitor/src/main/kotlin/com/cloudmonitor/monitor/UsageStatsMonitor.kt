package com.cloudmonitor.monitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads foreground/background transitions from [UsageStatsManager].
 *
 * IMPORTANT LIMIT: `UsageStatsManager` reports *activity* transitions, NOT process death.
 * A process can be killed while its last activity event stays in the log, and an app that
 * is simply idle in the background looks exactly like a dead app if you only look at
 * staleness. This class therefore only produces WEAK signals; the hard tier-1 signal comes
 * from the notification listener.
 */
@Singleton
class UsageStatsMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * @param lastResumeAt   timestamp of the last ACTIVITY_RESUMED
     * @param lastEventAt    timestamp of the last usage event of any kind
     * @param foreground     true when the app was in the foreground at the end of the window
     * @param eventsInWindow number of usage events seen in the query window
     */
    data class AppUsage(
        val packageName: String,
        val lastResumeAt: Long,
        val lastEventAt: Long,
        val foreground: Boolean,
        val eventsInWindow: Int,
    )

    companion object {
        /** Usage event constants. The foreground-service ones only exist from API 29 on. */
        private const val EVENT_ACTIVITY_RESUMED = 1
        private const val EVENT_ACTIVITY_PAUSED = 2
        private const val EVENT_ACTIVITY_STOPPED = 23
        private const val EVENT_FOREGROUND_SERVICE_STARTED = 24
        private const val EVENT_FOREGROUND_SERVICE_STOPPED = 25

        /** How far back the detector looks when asking for usage events. */
        const val LOOKBACK_MILLIS = 5 * 60 * 1000L
    }

    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** Whether the user granted Usage Access to this app. */
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Query recent usage events and fold them into a per-package summary.
     */
    fun query(lookbackMillis: Long = LOOKBACK_MILLIS, now: Long = System.currentTimeMillis()): Map<String, AppUsage> {
        val manager = usageStatsManager ?: return emptyMap()
        val result = HashMap<String, AppUsage>()

        val events = try {
            manager.queryEvents(now - lookbackMillis, now)
        } catch (_: Throwable) {
            return emptyMap()
        }

        while (events.hasNextEvent()) {
            val event = UsageEvents.Event()
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val current = result[pkg] ?: AppUsage(pkg, 0L, 0L, false, 0)
            var lastResumeAt = current.lastResumeAt
            var lastEventAt = current.lastEventAt
            var foreground = current.foreground

            when (event.eventType) {
                EVENT_ACTIVITY_RESUMED -> {
                    lastResumeAt = maxOf(lastResumeAt, event.timeStamp)
                    foreground = true
                }

                EVENT_ACTIVITY_PAUSED -> {
                    foreground = false
                }

                EVENT_ACTIVITY_STOPPED -> {
                    foreground = false
                }

                EVENT_FOREGROUND_SERVICE_STARTED -> foreground = true
                EVENT_FOREGROUND_SERVICE_STOPPED -> foreground = false
            }

            result[pkg] = current.copy(
                lastResumeAt = lastResumeAt,
                lastEventAt = maxOf(lastEventAt, event.timeStamp),
                foreground = foreground,
                eventsInWindow = current.eventsInWindow + 1,
            )
        }
        return result
    }

    /** Whether [packageName] is installed on this device. */
    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Human readable label of an installed package. */
    fun labelOf(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: Throwable) {
        packageName
    }
}
