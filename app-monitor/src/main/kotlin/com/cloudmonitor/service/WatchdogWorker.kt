package com.cloudmonitor.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Watchdog: makes sure the foreground service is actually running.
 *
 * WorkManager is used ONLY for this. It is deliberately not used for event delivery, because
 * WorkManager's minimum periodic interval (15 minutes) is far too coarse for the
 * "notification removal to push <= 10 s" target.
 *
 * `BOOT_COMPLETED` does not fire on an in-place update, so this worker (which is scheduled
 * by the service itself) also covers the "app was updated" case.
 */
class WatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "WatchdogWorker"
        const val WORK_NAME = "cloud-monitor-watchdog"

        /** WorkManager's minimum periodic interval. */
        const val INTERVAL_MINUTES = 15L

        /** Schedule (or reschedule) the watchdog. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(
                INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    override suspend fun doWork(): Result {
        if (MonitorForegroundService.isRunning) {
            Log.d(TAG, "monitor service is alive")
            return Result.success()
        }
        Log.w(TAG, "monitor service is NOT running, requesting a restart")
        val outcome = MonitorForegroundService.start(applicationContext)
        if (!outcome.requested) {
            // A WorkManager worker is NOT one of the Android 12+ background-start
            // exemptions. Unless the user disabled battery optimisation for the app, the
            // platform will refuse this start. Retrying forever would only burn the
            // Android 16 job quota, so record the reason and let the user restart the
            // monitor from the app (which is a user-visible, exempt action).
            Log.w(
                TAG,
                "watchdog restart refused by Android: ${outcome.message}. " +
                    "The user must open Cloud Monitor and tap Start, or grant an " +
                    "unrestricted battery setting.",
            )
        }
        return Result.success()
    }
}
