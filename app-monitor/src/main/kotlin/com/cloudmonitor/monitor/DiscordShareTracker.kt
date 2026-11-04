package com.cloudmonitor.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration of the Discord screen-share notification.
 *
 * All four values are discovered by the user through the in-app Notification Inspector; we
 * never hard-code a guess.
 */
data class DiscordConfig(
    val packageName: String? = null,
    val notificationId: Int? = null,
    val notificationTag: String? = null,
    val channelId: String? = null,
) {
    val isConfigured: Boolean get() = !packageName.isNullOrBlank()
}

/**
 * Something happened to the Discord screen share.
 */
sealed interface ShareEvent {
    /** The share ended normally: the notification disappeared and did not come back. */
    data class Stopped(val reason: Int, val debounceMillis: Long) : ShareEvent

    /**
     * The Discord process died while the share was active. This is reported as `E-SHARE-02`,
     * never as `E-SHARE-01`.
     */
    data object EndedByDeath : ShareEvent
}

/**
 * Tracks whether Discord is currently sharing the screen.
 *
 * The share is considered over only after a DEBOUNCE window (3-5 s, we use 4 s) during which
 * the notification was not re-posted. Discord re-posts its ongoing notification frequently
 * (for example when the stream state changes), and without the debounce every re-post would
 * look like "the share ended".
 */
@Singleton
class DiscordShareTracker @Inject constructor(
    private val scope: CoroutineScope,
) {

    companion object {
        /** Debounce window for a removed screen-share notification. */
        const val DEBOUNCE_MILLIS = 4_000L
    }

    private val _shareActive = MutableStateFlow(false)
    val shareActive: StateFlow<Boolean> = _shareActive.asStateFlow()

    private val _shareEvents = MutableSharedFlow<ShareEvent>(extraBufferCapacity = 8)
    val shareEvents: SharedFlow<ShareEvent> = _shareEvents.asSharedFlow()

    private var pendingRemoval: Job? = null
    private var activeNotificationKey: String? = null

    /** A screen-share notification was posted. */
    fun onPosted(snapshot: NotificationSnapshot, config: DiscordConfig) {
        if (!matches(snapshot, config)) return
        pendingRemoval?.cancel()
        pendingRemoval = null
        activeNotificationKey = snapshot.key
        if (!_shareActive.value) {
            _shareActive.value = true
        }
    }

    /**
     * A screen-share notification was removed.
     *
     * The share is only declared over after the debounce window, because Discord re-posts
     * the notification in many situations.
     */
    fun onRemoved(snapshot: NotificationSnapshot, reason: Int, config: DiscordConfig) {
        if (!matches(snapshot, config)) return
        if (activeNotificationKey != null && activeNotificationKey != snapshot.key) return

        pendingRemoval?.cancel()
        pendingRemoval = scope.launch {
            delay(DEBOUNCE_MILLIS)
            activeNotificationKey = null
            if (_shareActive.value) {
                _shareActive.value = false
                _shareEvents.tryEmit(ShareEvent.Stopped(reason, DEBOUNCE_MILLIS))
            }
        }
    }

    /**
     * Called when the Discord process is gone. The share cannot possibly be running any
     * more, so we end it immediately (no debounce).
     */
    fun onProcessDeath() {
        pendingRemoval?.cancel()
        pendingRemoval = null
        activeNotificationKey = null
        if (_shareActive.value) {
            _shareActive.value = false
            _shareEvents.tryEmit(ShareEvent.EndedByDeath)
        }
    }

    /**
     * Reconcile with the notifications that are currently posted, e.g. after the monitor was
     * restarted while a share was already running.
     */
    fun reconcile(active: List<NotificationSnapshot>, config: DiscordConfig) {
        val match = active.firstOrNull { matches(it, config) }
        if (match != null) {
            pendingRemoval?.cancel()
            pendingRemoval = null
            activeNotificationKey = match.key
            _shareActive.value = true
        }
    }

    /**
     * Does [snapshot] belong to the configured Discord screen-share notification?
     */
    fun matches(snapshot: NotificationSnapshot, config: DiscordConfig): Boolean {
        val pkg = config.packageName
        if (pkg.isNullOrBlank() || snapshot.packageName != pkg) return false
        if (config.notificationId != null && snapshot.id != config.notificationId) return false
        if (!config.notificationTag.isNullOrEmpty() &&
            snapshot.tag != null &&
            snapshot.tag != config.notificationTag
        ) {
            return false
        }
        if (config.notificationId == null && !config.channelId.isNullOrEmpty()) {
            if (snapshot.channelId != config.channelId) return false
        }
        return true
    }
}
