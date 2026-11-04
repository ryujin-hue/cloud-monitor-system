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
        MonitorForegroundService.start(context)
    }
}
