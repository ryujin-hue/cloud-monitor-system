package com.cloudmonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the monitor after the cloud phone reboots.
 *
 * `BOOT_COMPLETED` is delivered after a reboot (and, on some devices, after the user
 * swipes the lock screen). `ACTION_BOOT_COMPLETED`, `ACTION_LOCKED_BOOT_COMPLETED` and
 * `ACTION_MY_PACKAGE_REPLACED` are explicit exemptions from the Android 12+ background
 * foreground-service start restriction, so starting here is permitted. Android 15/16
 * additionally restrict *which* FGS types may be launched from BOOT_COMPLETED; this
 * service is `specialUse`, which is not one of the restricted types, and the start result
 * is checked anyway so a refusal is never reported as success.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        Log.i(TAG, "boot detected ($action), starting monitor")
        val outcome = MonitorForegroundService.start(context)
        if (!outcome.requested) {
            Log.w(TAG, "boot-start was refused: ${outcome.message}")
        }
    }
}
