package com.cloudmonitor.di

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
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

        val usageAccess = try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            appOps?.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            ) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
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

    /** Intent that opens the "Notification access" screen of this app. */
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
