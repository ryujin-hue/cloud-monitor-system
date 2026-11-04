package com.cloudmonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the monitor after the cloud phone reboots.
 *
 * `BOOT_COMPLETED` is delivered after a reboot (and, on some devices, after the user
 * swipes the lock screen). On Android 12+ the app must not start a foreground service from
 * a background broadcast receiver unless it is exempt; the monitor asks for a battery
 * optimisation exemption in the onboarding flow, and the service degrades gracefully if the
 * platform refuses the start.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        Log.i(TAG, "boot detected ($action), starting monitor")
        MonitorForegroundService.start(context)
    }
}
