package com.cloudmonitor.monitor

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything we could read from a posted notification.
 *
 * This is also the payload of the in-app "Notification Inspector", which is how the user
 * discovers the real Discord package, notification id and ongoing flag instead of us
 * hard-coding guesses.
 */
data class NotificationSnapshot(
    val packageName: String,
    val id: Int,
    val tag: String?,
    val channelId: String?,
    val ongoing: Boolean,
    val title: String,
    val text: String,
    val postTime: Long,
) {
    /** Stable identity of a notification (id + tag). */
    val key: String get() = "$packageName/$id/${tag ?: ""}"
}

/**
 * Events observed by the notification listener.
 */
sealed interface NotificationEvent {
    data class Posted(val snapshot: NotificationSnapshot) : NotificationEvent
    data class Removed(val snapshot: NotificationSnapshot, val reason: Int) : NotificationEvent
}

/**
 * Bridge between the system-created [MonitorNotificationListener] and the rest of the app.
 *
 * The listener cannot be injected (the system instantiates it), so it publishes into this
 * singleton instead.
 */
@Singleton
class NotificationEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<NotificationEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<NotificationEvent> = _events.asSharedFlow()

    private val _active = MutableStateFlow<List<NotificationSnapshot>>(emptyList())

    /** Currently posted notifications, used by the Notification Inspector. */
    val active: StateFlow<List<NotificationSnapshot>> = _active.asStateFlow()

    fun publish(event: NotificationEvent) {
        _events.tryEmit(event)
    }

    fun setActive(snapshots: List<NotificationSnapshot>) {
        _active.value = snapshots
    }

    fun addActive(snapshot: NotificationSnapshot) {
        val current = _active.value
        val index = current.indexOfFirst { it.key == snapshot.key }
        _active.value = if (index >= 0) {
            current.toMutableList().apply { this[index] = snapshot }
        } else {
            current + snapshot
        }
    }

    fun removeActive(key: String) {
        _active.value = _active.value.filterNot { it.key == key }
    }
}
