package com.cloudmonitor.domain

import android.util.Log
import com.cloudmonitor.shared.ExitEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tier-2 detection: ask the operating system whether a process is alive.
 *
 * This needs elevated privileges (root). On a stock phone the probe reports
 * [isAvailable] `false` and the detector falls back to tier 1.
 */
interface ProcessProbe {
    /** Whether privileged shell access could be established. */
    val isAvailable: Boolean

    /**
     * `true` when the process exists, `false` when it is definitely gone and `null` when
     * the probe could not determine it.
     */
    suspend fun isProcessAlive(packageName: String): Boolean?

    /** The exit reason of the most recent termination, when it can be read. */
    suspend fun exitReason(packageName: String): ExitEvidence.ExitReason?

    /** True when a privileged dump shows the process in an ANR state. */
    suspend fun hasAnrEvidence(packageName: String): Boolean
}

/**
 * Root-shell implementation of [ProcessProbe].
 *
 * Commands used:
 *  * `pidof <package>`                     -> is the process alive
 *  * `dumpsys activity exit-info <package>` -> why did it die
 *
 * `su` is used when present; otherwise a plain shell is tried, which works on devices that
 * were set up with an adb shell (some cloud-phone providers ship this).
 */
@Singleton
class RootProcessProbe @Inject constructor() : ProcessProbe {

    private companion object {
        const val TAG = "RootProcessProbe"
        const val COMMAND_TIMEOUT_SECONDS = 3L
        val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    }

    @Volatile
    private var rootAvailable: Boolean? = null

    override val isAvailable: Boolean
        get() = rootAvailable ?: synchronized(this) {
            rootAvailable ?: detectRoot().also { rootAvailable = it }
        }

    private fun detectRoot(): Boolean {
        // `su` may live in different places depending on the root solution; try the common
        // ones and fall back to a plain shell (adb-shell enabled devices).
        val hasSu = File("/system/bin/su").exists() ||
            File("/system/xbin/su").exists() ||
            File("/sbin/su").exists() ||
            File("/debug_ramdisk/su").exists()
        if (hasSu) {
            return runShellBlocking("id").contains("uid=0")
        }
        return runShellBlocking("id").contains("uid=0")
    }

    override suspend fun isProcessAlive(packageName: String): Boolean? {
        if (!isValidPackage(packageName)) return null
        val output = runShell("pidof $packageName").trim()
        if (output.isEmpty()) {
            // `pidof` prints nothing when there is no such process. On very old Android
            // versions it prints nothing at all, so we cross-check with a ps listing.
            val ps = runShell("ps -A 2>/dev/null | grep $packageName")
            return if (ps.contains(packageName)) true else false
        }
        return true
    }

    override suspend fun exitReason(packageName: String): ExitEvidence.ExitReason? {
        if (!isValidPackage(packageName)) return null
        val dump = runShell("dumpsys activity exit-info $packageName")
        if (dump.isBlank()) return null

        val reason = Regex("""reason=([A-Z_]+)""").find(dump)?.groupValues?.get(1)
            ?: return null
        val description = Regex("""description=([^\n\r]+)""").find(dump)?.groupValues?.get(1)?.trim()
            ?: reason
        return ExitEvidence.ExitReason(
            reason = reason,
        ).also {
            Log.i(TAG, "exit-info for $packageName: $reason ($description)")
        }
    }

    /** True when the dump mentions that the process is in an ANR state. */
    override suspend fun hasAnrEvidence(packageName: String): Boolean {
        if (!isValidPackage(packageName)) return false
        val dump = runShell("dumpsys activity processes | grep -A5 $packageName")
        return dump.contains("ANR", ignoreCase = true)
    }

    private fun isValidPackage(packageName: String): Boolean =
        PACKAGE_PATTERN.matches(packageName)

    private suspend fun runShell(command: String): String = withContext(Dispatchers.IO) {
        runShellBlocking(command)
    }

    /** Blocking variant, used from contexts that cannot suspend. */
    private fun runShellBlocking(command: String): String {
        val full = if (rootAvailable == true) "su -c '$command'" else command
        return runCatching {
            val process = ProcessBuilder("sh", "-c", full)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                return ""
            }
            process.inputStream.bufferedReader().readText()
        }.getOrElse {
            Log.w(TAG, "shell command '$command' failed", it)
            ""
        }
    }
}
