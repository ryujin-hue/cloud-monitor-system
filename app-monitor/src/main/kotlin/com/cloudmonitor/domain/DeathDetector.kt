package com.cloudmonitor.domain

import android.util.Log
import com.cloudmonitor.data.prefs.MonitorPreferences
import com.cloudmonitor.monitor.DiscordConfig
import com.cloudmonitor.monitor.DiscordShareTracker
import com.cloudmonitor.domain.ProcessProbe
import com.cloudmonitor.monitor.UsageStatsMonitor
import com.cloudmonitor.shared.CodeMapper
import com.cloudmonitor.shared.Confidence
import com.cloudmonitor.shared.DetectionTier
import com.cloudmonitor.shared.ErrorCode
import com.cloudmonitor.shared.ExitEvidence
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns raw observations into [DetectionOutcome]s.
 *
 * Detection tiers
 * ----------------
 * **Tier 2 (privileged)** — a root shell is available. We ask the OS directly whether the
 * process exists (`pidof`) and, when it is gone, WHY it went away
 * (`dumpsys activity exit-info`). That yields `E-102` (known exit reason) or `E-204` (ANR).
 *
 * **Tier 1 (no privileges)** — the default on a stock cloud phone. Two weak signals are
 * combined:
 *   * the removal of the app's ONGOING notification (an app that is alive keeps it), and
 *   * usage-event staleness (the app was in the foreground and then stopped producing any
 *     usage event at all).
 *
 * Neither tier-1 signal alone is proof, so the resulting confidence is MEDIUM at best and
 * LOW when only staleness was available. When there is no evidence at all we fall back to
 * `E-101` with LOW confidence, exactly as specified.
 */
@Singleton
class DeathDetector @Inject constructor(
    private val prefs: MonitorPreferences,
    private val usage: UsageStatsMonitor,
    private val probe: ProcessProbe,
    private val shareTracker: DiscordShareTracker,
) {

    companion object {
        const val TAG = "DeathDetector"

        /** How often the foreground service runs a detection scan. */
        const val SCAN_INTERVAL_SECONDS = 15L

        /** A package is considered "idle" after this long without any usage event. */
        const val STALENESS_MILLIS = 45_000L

        /** The app must have been in the foreground within this window to be a candidate. */
        const val RECENT_FOREGROUND_WINDOW_MILLIS = 120_000L

        /** How long an ANR observation may be attributed to a package. */
        const val ANR_ATTRIBUTION_WINDOW_MILLIS = 120_000L
    }

    /** Packages we have seen alive at least once. Only those can "die". */
    private val seenAlive = ConcurrentHashMap.newKeySet<String>()

    /** Timestamps of ongoing-notification removals, per package. */
    private val removalSignals = ConcurrentHashMap<String, Long>()

    /** Timestamps of observed ANR dialogs. */
    private val anrTimestamps = ConcurrentLinkedQueue<Long>()

    /** Records that an app's ongoing notification was removed (strong tier-1 signal). */
    fun onOngoingNotificationRemoved(packageName: String, at: Long = System.currentTimeMillis()) {
        removalSignals[packageName] = at
    }

    /** Records an ANR dialog observation. */
    fun onAnrObserved(at: Long = System.currentTimeMillis()) {
        anrTimestamps.add(at)
    }

    /**
     * Run one detection scan.
     */
    suspend fun scan(): List<DetectionOutcome> {
        val settings = prefs.current()
        val watched = settings.watchedPackages
        if (watched.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val usageSnapshot = usage.query(now = now)
        val discordConfig = DiscordConfig(
            packageName = settings.discordPackage,
            notificationId = settings.discordNotificationId,
            notificationTag = settings.discordNotificationTag,
            channelId = settings.discordChannelId,
        )
        val shareActive = shareTracker.shareActive.value
        val privileged = probe.isAvailable

        // Attribute an ANR dialog to the app that was in the foreground most recently.
        val anrFor = consumeAnr(watched, usageSnapshot)

        val outcomes = ArrayList<DetectionOutcome>()

        for (pkg in watched) {
            val appUsage = usageSnapshot[pkg]
            if (appUsage != null && appUsage.eventsInWindow > 0) {
                seenAlive += pkg
            }

            if (!usage.isInstalled(pkg)) {
                Log.d(TAG, "$pkg is not installed, skipping")
                continue
            }

            if (privileged) {
                val alive = probe.isProcessAlive(pkg)
                if (alive == null) {
                    // Probe failed: fall through to the tier-1 heuristics.
                } else if (alive) {
                    seenAlive += pkg
                    outcomes += DetectionOutcome.Recovery(pkg)
                    continue
                } else {
                    if (pkg !in seenAlive) continue
                    val evidence = probe.exitReason(pkg)
                        ?: if (probe.hasAnrEvidence(pkg)) ExitEvidence.Anr else null
                    val code = CodeMapper.mapDeathCode(pkg, discordConfig.packageName, shareActive, evidence)
                    val confidence = CodeMapper.confidenceFor(code, DetectionTier.TIER_2, evidence)
                    outcomes += DetectionOutcome.Death(
                        DeathSignal(
                            packageName = pkg,
                            code = code,
                            tier = DetectionTier.TIER_2,
                            confidence = confidence,
                            metadata = buildMap {
                                evidence?.let { put("evidence", it.reason) }
                                put("probe", "pidof")
                            },
                        ),
                    )
                    continue
                }
            }

            // ---- Tier 1 ----
            val removalAt = removalSignals.remove(pkg)
            if (removalAt != null) {
                val evidence = if (anrFor == pkg) ExitEvidence.Anr else null
                val code = CodeMapper.mapDeathCode(pkg, discordConfig.packageName, shareActive, evidence)
                val confidence = CodeMapper.confidenceFor(code, DetectionTier.TIER_1, evidence)
                outcomes += DetectionOutcome.Death(
                    DeathSignal(
                        packageName = pkg,
                        code = code,
                        tier = DetectionTier.TIER_1,
                        confidence = confidence,
                        metadata = buildMap {
                            put("signal", "ongoing_notification_removed")
                            if (evidence != null) put("evidence", evidence.reason)
                        },
                    ),
                )
                continue
            }

            if (anrFor == pkg) {
                val code = CodeMapper.mapDeathCode(pkg, discordConfig.packageName, shareActive, ExitEvidence.Anr)
                outcomes += DetectionOutcome.Death(
                    DeathSignal(
                        packageName = pkg,
                        code = code,
                        tier = DetectionTier.TIER_1,
                        confidence = CodeMapper.confidenceFor(code, DetectionTier.TIER_1, ExitEvidence.Anr),
                        metadata = mapOf("signal" to "anr_dialog", "evidence" to "ANR"),
                    ),
                )
                continue
            }

            val recentlyForeground = appUsage != null &&
                appUsage.lastResumeAt > 0 &&
                now - appUsage.lastResumeAt < RECENT_FOREGROUND_WINDOW_MILLIS
            val idle = appUsage == null || (appUsage.lastEventAt in 1 until (now - STALENESS_MILLIS))

            if (recentlyForeground && idle) {
                val code = CodeMapper.mapDeathCode(pkg, discordConfig.packageName, shareActive, null)
                outcomes += DetectionOutcome.Death(
                    DeathSignal(
                        packageName = pkg,
                        code = code,
                        tier = DetectionTier.TIER_1,
                        confidence = CodeMapper.confidenceFor(code, DetectionTier.TIER_1, null),
                        metadata = mapOf("signal" to "usage_staleness"),
                    ),
                )
            }
        }

        return outcomes
    }

    /** Forget everything we know (used when the watched package list changes). */
    fun reset() {
        seenAlive.clear()
        removalSignals.clear()
        anrTimestamps.clear()
    }

    /**
     * Consume the oldest ANR observation within a short window and attribute it to a package.
     */
    private fun consumeAnr(
        watched: Set<String>,
        usageSnapshot: Map<String, UsageStatsMonitor.AppUsage>,
    ): String? {
        val observation = anrTimestamps.poll() ?: return null
        val now = System.currentTimeMillis()
        if (now - observation > ANR_ATTRIBUTION_WINDOW_MILLIS) return null

        // The ANR dialog is drawn by the system on top of the app that froze, so the app
        // that was in the foreground most recently is the best available attribution.
        return watched
            .filter { usageSnapshot[it]?.lastResumeAt != null }
            .maxByOrNull { usageSnapshot[it]?.lastResumeAt ?: 0L }
    }
}
