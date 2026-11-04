package com.cloudmonitor.data.repo

import android.util.Log
import com.cloudmonitor.data.local.EventLogDao
import com.cloudmonitor.data.local.EventLogEntity
import com.cloudmonitor.data.local.QueuedEventDao
import com.cloudmonitor.data.local.QueuedEventEntity
import com.cloudmonitor.data.prefs.MonitorPreferences
import com.cloudmonitor.data.remote.ApiResult
import com.cloudmonitor.data.remote.MonitorApi
import com.cloudmonitor.shared.dto.EventBatchDto
import com.cloudmonitor.shared.dto.EventDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

/**
 * Result of a flush attempt.
 */
sealed interface FlushResult {
    data object NothingToDo : FlushResult
    data class Sent(val count: Int) : FlushResult
    data class Failed(val reason: String, val retryInMillis: Long) : FlushResult
}

/**
 * Offline outbox for events.
 *
 * Every event is written to Room first and delivered later. That is what makes the system
 * survive the exact scenario it is built for: the cloud phone loses connectivity at the
 * moment the app dies, and we still want Cloud Alert to learn about it — with the ORIGINAL
 * device timestamp, not the time the connection came back.
 */
@Singleton
class EventRepository @Inject constructor(
    private val queueDao: QueuedEventDao,
    private val eventLogDao: EventLogDao,
    private val api: MonitorApi,
    private val prefs: MonitorPreferences,
    private val json: Json,
) {

    companion object {
        private const val TAG = "EventRepository"

        /** Maximum number of events sent in one request. */
        const val MAX_BATCH = 100

        /** Maximum number of queued events. Older entries are dropped beyond this. */
        const val MAX_QUEUE_SIZE = 2_000

        /** First retry delay. */
        const val BASE_BACKOFF_MILLIS = 5_000L

        /** Upper bound of the exponential backoff. */
        const val MAX_BACKOFF_MILLIS = 30 * 60 * 1000L

        /** Delay used when the API key is rejected: retrying immediately cannot help. */
        const val REJECTED_BACKOFF_MILLIS = 10 * 60 * 1000L
    }

    private val flushMutex = Mutex()

    /**
     * Queue an event and persist it in the local history.
     */
    suspend fun enqueue(event: EventDto): Boolean {
        val payload = json.encodeToString(EventDto.serializer(), event)
        val inserted = queueDao.insert(
            QueuedEventEntity(
                eventId = event.eventId,
                deviceId = event.deviceId,
                payload = payload,
                occurredAt = event.occurredAt,
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                nextAttemptAt = 0L,
            ),
        )
        if (inserted == -1L) {
            // Already queued (idempotency): keep the original entry untouched.
            return false
        }

        eventLogDao.insert(
            EventLogEntity(
                eventId = event.eventId,
                deviceId = event.deviceId,
                type = event.type.name,
                code = event.code,
                packageName = event.packageName,
                occurredAt = event.occurredAt,
                seq = event.seq,
                metadataJson = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        event.metadata.forEach { (key, value) ->
                            put(key, kotlinx.serialization.json.JsonPrimitive(value))
                        }
                    },
                ),
                appVersion = event.appVersion,
                versionCode = event.versionCode,
                confidence = event.confidence,
                detection = event.detection,
            ),
        )

        enforceQueueLimit()
        return true
    }

    /** Drop the oldest events when the queue grows beyond [MAX_QUEUE_SIZE]. */
    private suspend fun enforceQueueLimit() {
        val count = queueDao.count()
        if (count <= MAX_QUEUE_SIZE) return
        val excess = count - MAX_QUEUE_SIZE
        Log.w(TAG, "offline queue overflow ($count > $MAX_QUEUE_SIZE), dropping $excess oldest events")
        queueDao.deleteOldest(excess)
    }

    /**
     * Try to deliver every event that is due.
     */
    suspend fun flush(): FlushResult = flushMutex.withLock {
        val settings = prefs.current()
        if (!settings.hasApiKey) {
            return@withLock FlushResult.Failed("no API key configured", MAX_BACKOFF_MILLIS)
        }
        if (settings.deviceId.isBlank()) {
            return@withLock FlushResult.Failed("no device id", MAX_BACKOFF_MILLIS)
        }

        val now = System.currentTimeMillis()
        val due = queueDao.due(now, MAX_BATCH)
        if (due.isEmpty()) return@withLock FlushResult.NothingToDo

        val events = ArrayList<EventDto>(due.size)
        val corrupt = ArrayList<String>()
        for (row in due) {
            val decoded = runCatching { json.decodeFromString(EventDto.serializer(), row.payload) }
                .getOrNull()
            if (decoded == null) corrupt += row.eventId else events += decoded
        }
        if (corrupt.isNotEmpty()) {
            Log.w(TAG, "dropping ${corrupt.size} undecodable queued events")
            queueDao.deleteByIds(corrupt)
        }
        if (events.isEmpty()) return@withLock FlushResult.NothingToDo

        val batch = EventBatchDto(
            deviceId = settings.deviceId,
            appVersion = settings.appVersion,
            versionCode = settings.versionCode,
            events = events,
        )

        return@withLock when (val result = api.sendEvents(settings.apiKey, batch)) {
            is ApiResult.Success -> {
                queueDao.deleteByIds(events.map { it.eventId })
                FlushResult.Sent(events.size)
            }

            is ApiResult.Unauthorized -> {
                Log.w(TAG, "backend rejected the API key")
                scheduleRetry(due, REJECTED_BACKOFF_MILLIS)
                FlushResult.Failed("unauthorized", REJECTED_BACKOFF_MILLIS)
            }

            is ApiResult.RateLimited -> {
                scheduleRetry(due, BASE_BACKOFF_MILLIS * 4)
                FlushResult.Failed("rate limited", BASE_BACKOFF_MILLIS * 4)
            }

            is ApiResult.ServerError -> {
                val backoff = backoffFor(due.maxOf { it.attempts })
                scheduleRetry(due, backoff)
                FlushResult.Failed("server error ${result.status}", backoff)
            }

            is ApiResult.NetworkError -> {
                val backoff = backoffFor(due.maxOf { it.attempts })
                scheduleRetry(due, backoff)
                FlushResult.Failed(result.cause.message ?: "network error", backoff)
            }
        }
    }

    /**
     * Flush, retrying a couple of times for transient failures.
     */
    suspend fun flushWithRetries(attempts: Int = 3): FlushResult {
        var result = flush()
        var attempt = 0
        while (result is FlushResult.Failed && attempt < attempts) {
            attempt++
            val wait = (result as FlushResult.Failed).retryInMillis
            if (wait > 60_000) break
            delay(wait)
            result = flush()
        }
        return result
    }

    private suspend fun scheduleRetry(rows: List<QueuedEventEntity>, delayMillis: Long) {
        val now = System.currentTimeMillis()
        for (row in rows) {
            queueDao.update(
                row.copy(
                    attempts = row.attempts + 1,
                    nextAttemptAt = now + delayMillis,
                ),
            )
        }
    }

    /**
     * Exponential backoff with jitter, capped at [MAX_BACKOFF_MILLIS].
     */
    private fun backoffFor(attempts: Int): Long {
        val exponent = attempts.coerceIn(0, 16)
        val raw = BASE_BACKOFF_MILLIS shl exponent
        val capped = min(raw, MAX_BACKOFF_MILLIS)
        val jitter = (capped * 0.2 * Random.nextDouble()).toLong()
        return capped - jitter
    }
}
