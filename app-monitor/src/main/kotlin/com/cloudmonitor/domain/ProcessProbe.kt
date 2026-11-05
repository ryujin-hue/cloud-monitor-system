package com.cloudmonitor.domain

import android.util.Log
import com.cloudmonitor.shared.ExitEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of a root probe.
 *
 * A device can be rooted without granting this app root access (for example when the user
 * denies the Magisk/KernelSU prompt). The two states must never be conflated: only
 * [rootGranted] permits privileged shell commands.
 *
 * @property suBinaryFound an `su` binary exists at one of the well-known locations. This
 *   alone is NOT proof that the app has root access.
 * @property rootGranted a privileged command actually returned `uid=0`. This is the only
 *   condition under which commands are run through `su`.
 * @property detail human-readable explanation shown in diagnostics/logcat.
 */
data class RootStatus(
    val suBinaryFound: Boolean = false,
    val rootGranted: Boolean = false,
    val detail: String = "not checked yet",
)

/**
 * Tier-2 detection: ask the operating system whether a process is alive.
 *
 * This needs elevated privileges (root or a shell that already runs as uid 0). On a stock
 * phone [isAvailable] is `false` and the detector falls back to tier 1.
 */
interface ProcessProbe {
    /** Cached result of the last detection. Reading it never performs I/O or blocks. */
    val isAvailable: Boolean

    /** Full cached result, so the UI can distinguish "rooted device" from "app has root". */
    val rootStatus: RootStatus

    /**
     * (Re)run detection on `Dispatchers.IO`. Safe to call repeatedly; negative results are
     * throttled so a resumed screen does not spawn a shell every time.
     */
    suspend fun refreshRootStatus(): RootStatus

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
 *  * `id`                                  -> did the shell get uid 0?
 *  * `pidof <package>`                     -> is the process alive
 *  * `dumpsys activity exit-info <package>` -> why did it die
 *
 * Detection is deliberately a separate, explicit, suspending step. The original code
 * computed `rootAvailable` lazily from inside `runShellBlocking()` while
 * `rootAvailable` was still `null`, so the very first `id` always ran through a plain
 * shell and a rooted device was reported as "no privileges". Detection also used to run
 * on whatever thread read [isAvailable] — including the main thread — because the getter
 * started a blocking `ProcessBuilder` call.
 */
@Singleton
class RootProcessProbe @Inject constructor() : ProcessProbe {

    companion object {
        const val TAG = "RootProcessProbe"
        const val COMMAND_TIMEOUT_MILLIS = 3_000L
        const val DRAIN_JOIN_MILLIS = 300L
        const val MAX_OUTPUT_CHARS = 64 * 1024
        const val NEGATIVE_RESULT_CACHE_MILLIS = 60_000L

        /** Common `su` locations across Magisk, KernelSU, SuperSU and legacy root. */
        val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/vendor/bin/su",
            "/system/sbin/su",
            "/su/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/adb/magisk/su",
        )

        val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
        val UID_ZERO = Regex("""(?:^|\s)uid=0(?:\D|$)""")

        /** True only when `id` reports the root user (uid=0), not uid=0xxx. */
        @JvmStatic
        fun isUidZero(output: String): Boolean = UID_ZERO.containsMatchIn(output)
    }

    private val detectionMutex = Mutex()

    @Volatile
    private var cachedStatus: RootStatus = RootStatus()

    /** Path used for privileged commands, null when no usable `su` binary was found. */
    @Volatile
    private var suCommand: String? = null

    @Volatile
    private var lastDetectionAt: Long = 0L

    override val isAvailable: Boolean get() = cachedStatus.rootGranted

    override val rootStatus: RootStatus get() = cachedStatus

    override suspend fun refreshRootStatus(): RootStatus = withContext(Dispatchers.IO) {
        detectionMutex.withLock {
            val now = System.currentTimeMillis()
            // A positive result is stable; a negative one is re-checked at most once a
            // minute so that a repeatedly refreshed screen stays cheap.
            if (cachedStatus.rootGranted) return@withLock cachedStatus
            if (lastDetectionAt > 0L && now - lastDetectionAt < NEGATIVE_RESULT_CACHE_MILLIS) {
                return@withLock cachedStatus
            }
            lastDetectionAt = now

            val suPath = findSuBinary()

            // Some cloud-phone images boot into an adb shell that already runs as uid 0.
            // Keep that fallback: it is the only way to get tier 2 without a su binary.
            val plainShell = execute(listOf("sh", "-c", "id"))
            var granted = isUidZero(plainShell)
            var detail = if (granted) {
                "shell already runs as uid=0 (no su required)"
            } else {
                "plain shell is unprivileged"
            }

            if (suPath != null && !granted) {
                val suOutput = execute(listOf(suPath, "-c", "id"))
                granted = isUidZero(suOutput)
                detail = when {
                    granted -> "su at $suPath granted uid=0"
                    suOutput.isBlank() -> "su at $suPath produced no output (denied, timed out or no binary access)"
                    else -> "su at $suPath did not grant uid=0: ${suOutput.take(120).trim()}"
                }
            }

            val status = RootStatus(
                suBinaryFound = suPath != null,
                rootGranted = granted,
                detail = detail,
            )
            suCommand = suPath
            cachedStatus = status
            Log.i(TAG, "root detection: $status")
            status
        }
    }

    /** Look for a `su` binary without assuming that its presence implies root access. */
    private fun findSuBinary(): String? {
        for (path in SU_PATHS) {
            try {
                if (File(path).exists()) return path
            } catch (e: Throwable) {
                Log.w(TAG, "cannot stat $path", e)
            }
        }
        // Fall back to PATH. `command -v` is a shell builtin, so no external `which` is
        // needed (some minimal Android images do not ship it).
        val onPath = execute(listOf("sh", "-c", "command -v su 2>/dev/null"))
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("/") }
        return onPath?.takeIf {
            try {
                File(it).exists()
            } catch (_: Throwable) {
                false
            }
        }
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

    /**
     * Blocking variant. Uses `su` only when detection actually proved that `su` works.
     * It never consults the value it is trying to determine, which was the original bug.
     */
    private fun runShellBlocking(command: String): String {
        val su = suCommand
        val args = if (cachedStatus.rootGranted && su != null) {
            // Passing the command as one argv element avoids shell quoting/injection.
            listOf(su, "-c", command)
        } else {
            listOf("sh", "-c", command)
        }
        return execute(args)
    }

    /**
     * Run a process with a hard timeout and a bounded output drain.
     *
     * The reader runs on a daemon thread because `waitFor()` alone deadlocks when the
     * child writes more than the pipe buffer (a `dumpsys` dump easily exceeds 64 KiB).
     */
    private fun execute(args: List<String>, timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS): String {
        val process = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            Log.w(TAG, "could not start ${args.firstOrNull()}: ${e.message}")
            return ""
        }

        val sink = StringBuilder()
        val drain = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    while (sink.length < MAX_OUTPUT_CHARS) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        sink.append(buffer, 0, count)
                    }
                }
            } catch (_: Throwable) {
                // Stream closed because the process was destroyed or exited.
            }
        }
        drain.isDaemon = true
        drain.start()

        return try {
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                drain.join(DRAIN_JOIN_MILLIS)
                Log.w(TAG, "command timed out after ${timeoutMillis}ms: ${args.firstOrNull()}")
                ""
            } else {
                drain.join(DRAIN_JOIN_MILLIS)
                sink.toString()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            Log.w(TAG, "command interrupted: ${args.firstOrNull()}")
            ""
        } catch (e: Throwable) {
            Log.w(TAG, "command failed: ${args.firstOrNull()}", e)
            ""
        }
    }
}
