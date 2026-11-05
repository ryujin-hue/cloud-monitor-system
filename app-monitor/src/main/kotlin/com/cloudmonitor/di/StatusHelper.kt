package com.cloudmonitor.di

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the onboarding screen needs to know about the special permissions the monitor
 * depends on.
 */
data class PermissionStatus(
    /** Notification Access: the primary tier-1 detection channel. */
    val notificationAccess: Boolean = false,

    /** Usage Access: foreground/background transition data. */
    val usageAccess: Boolean = false,

    /** Exemption from battery optimisation. */
    val batteryUnrestricted: Boolean = false,

    /** Runtime permission to post notifications (Android 13+). */
    val notificationsEnabled: Boolean = false,
) {
    /** The monitor cannot work at all without these two. */
    val essentialsGranted: Boolean get() = notificationAccess && usageAccess

    val allGranted: Boolean
        get() = essentialsGranted && batteryUnrestricted && notificationsEnabled
}

/**
 * Reads the state of the special permissions and builds the intent that opens the right
 * settings screen for each of them.
 */
@Singleton
class PermissionStatusProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun current(): PermissionStatus {
        val notificationAccess = try {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        } catch (_: Throwable) {
            false
        }

        // `unsafeCheckOpNoThrow` is API 29+, but minSdk is 26. On Android 8.0/8.1 it throws
        // NoSuchMethodError, which used to be swallowed by the broad catch below and made the
        // app silently report "Usage Access not granted" even when it was granted. Use the
        // API-19 `checkOpNoThrow` on those versions instead.
        val usageAccess = try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            val mode = if (appOps == null) {
                null
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: SecurityException) {
            false
        }

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryUnrestricted = try {
            power?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: Throwable) {
            false
        }

        val notificationsEnabled = try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (_: Throwable) {
            false
        }

        return PermissionStatus(
            notificationAccess = notificationAccess,
            usageAccess = usageAccess,
            batteryUnrestricted = batteryUnrestricted,
            notificationsEnabled = notificationsEnabled,
        )
    }

    /**
     * Intent that opens the "Notification access" screen of this app.
     *
     * `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` is only public from API 30, but it is a
     * compile-time inlined String constant, so referencing it on API 26-29 does not throw.
     * Those versions simply ignore the extra and show the full list, where the user finds
     * this app manually (documented in TROUBLESHOOTING.md).
     */
    @SuppressLint("InlinedApi")
    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                "${context.packageName}/${context.packageName}.monitor.MonitorNotificationListener",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Intent that opens the "Usage access" screen. */
    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Intent that asks for a battery-optimisation exemption. */
    fun batteryExemptionIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Intent that opens this app's notification settings (Android 13+ runtime permission). */
    fun notificationPermissionIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Intent that opens the accessibility settings (optional, ANR evidence only). */
    fun accessibilityIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** True when the OEM autostart screen is known to exist on this device. */
    fun hasOemAutostartScreen(): Boolean {
        val candidates = listOf(
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.oppo.safe",
            "com.vivo.permissionmanager",
            "com.samsung.android.lool",
            "com.asus.mobilemanager",
            "com.oneplus.security",
        )
        return candidates.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
