package com.cloudmonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the monitor after an in-place update.
 *
 * IMPORTANT: `BOOT_COMPLETED` is NOT delivered when an APK is replaced. Without this
 * receiver the monitor would stay dead after every update until the cloud phone is
 * rebooted, and Cloud Alert would only see an `E-HB-01` outage.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageReplacedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "package replaced, restarting monitor")
        val outcome = MonitorForegroundService.start(context)
        if (!outcome.requested) {
            // MY_PACKAGE_REPLACED is an exemption from the Android 12+ background start
            // restriction, but OEMs can be stricter. Never claim the monitor is running
            // when the platform refused the start.
            Log.w(TAG, "restart after update was refused: ${outcome.message}")
        }
    }
}
