package com.cloudmonitor.data.remote

import com.cloudmonitor.shared.CancellationGuard
import com.cloudmonitor.shared.dto.EventBatchDto
import com.cloudmonitor.shared.dto.HeartbeatDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a backend call. */
sealed interface ApiResult {
    data object Success : ApiResult
    data object Unauthorized : ApiResult
    data object RateLimited : ApiResult
    data class ServerError(val status: Int, val body: String) : ApiResult
    data class NetworkError(val cause: Throwable) : ApiResult
}

/**
 * Thin HTTP client for the Cloudflare Pages Functions API.
 *
 * The API key is sent in the `x-api-key` header. There is exactly one key for the monitor
 * side (`POST /api/event`, `POST /api/heartbeat`).
 */
@Singleton
class MonitorApi @Inject constructor(
    private val client: HttpClient,
    private val baseUrl: String,
) {

    suspend fun sendEvents(apiKey: String, batch: EventBatchDto): ApiResult =
        call(apiKey, "/api/event", batch)

    suspend fun sendHeartbeat(apiKey: String, heartbeat: HeartbeatDto): ApiResult =
        call(apiKey, "/api/heartbeat", heartbeat)

    private suspend fun call(apiKey: String, path: String, payload: Any): ApiResult {
        return try {
            val response: HttpResponse = client.post(baseUrl + path) {
                header("x-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            when (response.status) {
                HttpStatusCode.OK,
                HttpStatusCode.Accepted,
                HttpStatusCode.NoContent,
                -> ApiResult.Success

                HttpStatusCode.Unauthorized,
                HttpStatusCode.Forbidden,
                -> ApiResult.Unauthorized

                HttpStatusCode.TooManyRequests -> ApiResult.RateLimited

                else -> ApiResult.ServerError(response.status.value, response.bodyAsText())
            }
        } catch (e: Throwable) {
            // A cancelled heartbeat/event upload must stay cancelled. Turning it into a
            // NetworkError would make the caller believe delivery failed and retry forever.
            CancellationGuard.rethrowCancellation(e)
            ApiResult.NetworkError(e)
        }
    }

    private suspend fun HttpResponse.bodyAsText(): String = try {
        body<String>()
    } catch (e: Throwable) {
        CancellationGuard.rethrowCancellation(e)
        ""
    }
}
