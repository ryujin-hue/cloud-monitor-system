package com.cloudmonitor.service

import android.os.SystemClock
import android.util.Log
import com.cloudmonitor.data.prefs.MonitorPreferences
import com.cloudmonitor.data.remote.ApiResult
import com.cloudmonitor.data.remote.MonitorApi
import com.cloudmonitor.data.repo.EventRepository
import com.cloudmonitor.data.repo.FlushResult
import com.cloudmonitor.data.repo.IncidentTracker
import com.cloudmonitor.di.PermissionStatusProvider
import com.cloudmonitor.domain.DeathDetector
import com.cloudmonitor.domain.DetectionOutcome
import com.cloudmonitor.monitor.AnrReporter
import com.cloudmonitor.monitor.DiscordConfig
import com.cloudmonitor.monitor.DiscordShareTracker
import com.cloudmonitor.monitor.NotificationEvent
import com.cloudmonitor.monitor.NotificationEventBus
import com.cloudmonitor.monitor.ShareEvent
import com.cloudmonitor.monitor.UsageStatsMonitor
import com.cloudmonitor.net.ConnectivityObserver
import com.cloudmonitor.shared.ErrorCode
import com.cloudmonitor.shared.dto.EventDto
import com.cloudmonitor.shared.dto.EventType
import com.cloudmonitor.shared.dto.HeartbeatDto
import com.cloudmonitor.shared.dto.PermissionStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The brain of Cloud Monitor.
 *
 * It owns the three loops that matter:
 *  * the **detection loop** (every [DeathDetector.SCAN_INTERVAL_SECONDS] seconds),
 *  * the **heartbeat loop** (every `heartbeatIntervalSec` seconds),
 *  * the **notification loop** (event driven).
 *
 * The foreground service only starts and stops this runtime; all the logic lives here so it
 * can be unit tested and reused.
 */
@Singleton
class MonitorRuntime @Inject constructor(
    private val prefs: MonitorPreferences,
    private val detector: DeathDetector,
    private val tracker: IncidentTracker,
    private val events: EventRepository,
    private val api: MonitorApi,
    private val bus: NotificationEventBus,
    private val shareTracker: DiscordShareTracker,
    private val anrReporter: AnrReporter,
    private val connectivity: ConnectivityObserver,
    private val permissionStatus: PermissionStatusProvider,
    private val pairing: PairingCodeProvider,
    private val usage: UsageStatsMonitor,
) {

    companion object {
        private const val TAG = "MonitorRuntime"

        /** How often the offline queue is retried while the network is down. */
        const val FLUSH_RETRY_MILLIS = 20_000L
    }

    private var scope: CoroutineScope? = null
    private var jobs: List<Job> = emptyList()

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** Start every loop. Safe to call twice. */
    fun start(scope: CoroutineScope) {
        if (running) return
        this.scope = scope
        running = true

        jobs = listOf(
            scope.launch { notificationLoop() },
            scope.launch { shareLoop() },
            scope.launch { shareStateLoop() },
            scope.launch { detectionLoop() },
            scope.launch { heartbeatLoop() },
            scope.launch { flushLoop() },
            scope.launch {
                anrReporter.anrEvents.collect { detector.onAnrObserved(it) }
            },
        )

        Log.i(TAG, "monitor runtime started")
    }

    /** Stop every loop. */
    fun stop() {
        running = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        scope = null
        Log.i(TAG, "monitor runtime stopped")
    }

    // ------------------------------------------------------------------
    // Loops
    // ------------------------------------------------------------------

    private suspend fun notificationLoop() {
        bus.events.collect { event ->
            when (event) {
                is NotificationEvent.Posted -> {
                    shareTracker.onPosted(event.snapshot, currentDiscordConfig())
                }

                is NotificationEvent.Removed -> {
                    shareTracker.onRemoved(event.snapshot, event.reason, currentDiscordConfig())
                    // A removed ONGOING notification of a watched app is the strongest
                    // tier-1 signal that the app just died.
                    if (event.snapshot.ongoing) {
                        val settings = prefs.current()
                        if (event.snapshot.packageName in settings.watchedPackages) {
                            detector.onOngoingNotificationRemoved(event.snapshot.packageName)
                        }
                    }
                }
            }
        }
    }

    private suspend fun shareLoop() {
        shareTracker.shareEvents.collect { event ->
            when (event) {
                is ShareEvent.Stopped -> {
                    emitDown(
                        code = ErrorCode.E_SHARE_01,
                        packageName = currentDiscordConfig().packageName,
                        detection = com.cloudmonitor.shared.DetectionTier.TIER_1,
                        confidence = com.cloudmonitor.shared.Confidence.MEDIUM,
                        metadata = mapOf(
                            "reason" to event.reason.toString(),
                            "debounce_ms" to event.debounceMillis.toString(),
                        ),
                    )
                }

                // A death is reported as E-SHARE-02 by the detector, never as E-SHARE-01.
                ShareEvent.EndedByDeath -> Unit
            }
        }
    }

    private suspend fun shareStateLoop() {
        // StateFlow is already de-duplicated, no distinctUntilChanged() needed.
        shareTracker.shareActive.collect { active ->
            if (active) {
                // A new share started: close a previously open E-SHARE-01 incident.
                emitRecovered(
                    code = ErrorCode.E_SHARE_01,
                    packageName = currentDiscordConfig().packageName,
                )
            }
        }
    }

    private suspend fun detectionLoop() {
        while (scope?.isActive == true) {
            delay(DeathDetector.SCAN_INTERVAL_SECONDS * 1000L)
            runCatching { runScan() }.onFailure { Log.w(TAG, "detection scan failed", it) }
        }
    }

    private suspend fun heartbeatLoop() {
        while (scope?.isActive == true) {
            val settings = prefs.current()
            val interval = settings.heartbeatIntervalSec.coerceAtLeast(15) * 1000L
            delay(interval)
            runCatching { sendHeartbeat() }.onFailure { Log.w(TAG, "heartbeat failed", it) }
        }
    }

    private suspend fun flushLoop() {
        while (scope?.isActive == true) {
            delay(FLUSH_RETRY_MILLIS)
            runCatching { events.flush() }.onFailure { Log.w(TAG, "flush failed", it) }
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Run one detection scan and translate the outcomes into events. */
    suspend fun runScan() {
        val outcomes = detector.scan()
        for (outcome in outcomes) {
            when (outcome) {
                is DetectionOutcome.Death -> {
                    val signal = outcome.signal
                    if (signal.code == ErrorCode.E_SHARE_02) {
                        // Discord died while sharing: the share is definitively over.
                        shareTracker.onProcessDeath()
                        emitRecovered(
                            code = ErrorCode.E_SHARE_01,
                            packageName = signal.packageName,
                        )
                    }
                    emitDown(
                        code = signal.code,
                        packageName = signal.packageName,
                        detection = signal.tier,
                        confidence = signal.confidence,
                        metadata = signal.metadata,
                    )
                }

                is DetectionOutcome.Recovery -> {
                    emitRecoveredForPackage(outcome.packageName)
                }
            }
        }
    }

    /** Send one heartbeat. */
    suspend fun sendHeartbeat() {
        val settings = prefs.current()
        if (settings.deviceId.isBlank()) return

        val online = connectivity.refresh()
        val permissions = permissionStatus.current()
        val pairingCode = if (settings.pairingEnabled) pairing.ensureCode() else null

        val heartbeat = HeartbeatDto(
            deviceId = settings.deviceId,
            occurredAt = System.currentTimeMillis(),
            seq = prefs.nextSequence(),
            appVersion = settings.appVersion,
            versionCode = settings.versionCode,
            label = settings.label,
            foregroundServiceActive = running,
            uptimeMillis = SystemClock.elapsedRealtime(),
            networkAvailable = online,
            permissions = PermissionStateDto(
                notificationAccess = permissions.notificationAccess,
                usageAccess = permissions.usageAccess,
                batteryUnrestricted = permissions.batteryUnrestricted,
                notificationsEnabled = permissions.notificationsEnabled,
            ),
            watchedPackages = settings.watchedPackages.toList(),
            pairingCode = pairingCode,
            pairingExpiresAt = if (pairingCode != null) System.currentTimeMillis() + PairingCodeProvider.VALIDITY_MILLIS else null,
        )

        if (!settings.hasApiKey) {
            Log.d(TAG, "heartbeat skipped: no API key configured")
            return
        }

        when (val result = api.sendHeartbeat(settings.apiKey, heartbeat)) {
            is ApiResult.Success -> Log.d(TAG, "heartbeat sent")
            is ApiResult.Unauthorized -> Log.w(TAG, "heartbeat rejected: bad API key")
            is ApiResult.RateLimited -> Log.w(TAG, "heartbeat rate limited")
            is ApiResult.ServerError -> Log.w(TAG, "heartbeat failed: ${result.status}")
            is ApiResult.NetworkError -> Log.w(TAG, "heartbeat failed: ${result.cause.message}")
        }
    }

    // ------------------------------------------------------------------
    // Event emission
    // ------------------------------------------------------------------

    private suspend fun emitDown(
        code: ErrorCode,
        packageName: String?,
        detection: com.cloudmonitor.shared.DetectionTier,
        confidence: com.cloudmonitor.shared.Confidence,
        metadata: Map<String, String> = emptyMap(),
    ) {
        val settings = prefs.current()
        if (settings.deviceId.isBlank()) return

        // Exactly one open incident per (device, package, code): a duplicate DOWN does not
        // produce a second event.
        if (tracker.isOpen(settings.deviceId, packageName, code.wire)) {
            Log.d(TAG, "incident already open for $packageName ${code.wire}, skipping")
            return
        }

        val event = EventDto(
            eventId = UUID.randomUUID().toString(),
            deviceId = settings.deviceId,
            type = EventType.DOWN,
            code = code.wire,
            packageName = packageName,
            detection = detection.wire,
            confidence = confidence.wire,
            occurredAt = System.currentTimeMillis(),
            seq = prefs.nextSequence(),
            appVersion = settings.appVersion,
            versionCode = settings.versionCode,
            metadata = metadata,
        )
        tracker.apply(event)
        events.enqueue(event)
        deliver()
    }

    private suspend fun emitRecovered(code: ErrorCode, packageName: String?) {
        val settings = prefs.current()
        if (settings.deviceId.isBlank()) return
        if (!tracker.isOpen(settings.deviceId, packageName, code.wire)) return

        val event = EventDto(
            eventId = UUID.randomUUID().toString(),
            deviceId = settings.deviceId,
            type = EventType.RECOVERED,
            code = code.wire,
            packageName = packageName,
            detection = com.cloudmonitor.shared.DetectionTier.TIER_1.wire,
            confidence = com.cloudmonitor.shared.Confidence.MEDIUM.wire,
            occurredAt = System.currentTimeMillis(),
            seq = prefs.nextSequence(),
            appVersion = settings.appVersion,
            versionCode = settings.versionCode,
            metadata = mapOf("reason" to "observed_again"),
        )
        tracker.apply(event)
        events.enqueue(event)
        deliver()
    }

    private suspend fun emitRecoveredForPackage(packageName: String) {
        val settings = prefs.current()
        val open = tracker.openIncidentsFor(packageName)
        for (incident in open) {
            val event = EventDto(
                eventId = UUID.randomUUID().toString(),
                deviceId = settings.deviceId,
                type = EventType.RECOVERED,
                code = incident.code,
                packageName = packageName,
                detection = incident.detection,
                confidence = incident.confidence,
                occurredAt = System.currentTimeMillis(),
                seq = prefs.nextSequence(),
                appVersion = settings.appVersion,
                versionCode = settings.versionCode,
                metadata = mapOf("reason" to "observed_again"),
            )
            tracker.apply(event)
            events.enqueue(event)
        }
        if (open.isNotEmpty()) deliver()
    }

    /** Try to deliver the queued events right away. */
    private suspend fun deliver() {
        val result = events.flush()
        if (result is FlushResult.Failed) {
            Log.d(TAG, "delivery deferred: ${result.reason}")
        }
    }

    private suspend fun currentDiscordConfig(): DiscordConfig {
        val settings = prefs.current()
        return DiscordConfig(
            packageName = settings.discordPackage,
            notificationId = settings.discordNotificationId,
            notificationTag = settings.discordNotificationTag,
            channelId = settings.discordChannelId,
        )
    }
}
