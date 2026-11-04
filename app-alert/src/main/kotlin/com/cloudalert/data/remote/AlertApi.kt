package com.cloudalert.data.remote

import com.cloudmonitor.shared.dto.ApiErrorDto
import com.cloudmonitor.shared.dto.EventsResponseDto
import com.cloudmonitor.shared.dto.RegisterRequestDto
import com.cloudmonitor.shared.dto.RegisterResponseDto
import com.cloudmonitor.shared.dto.StatusResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AlertApiResult<out T> {
    data class Ok<T>(val value: T) : AlertApiResult<T>
    data object Unauthorized : AlertApiResult<Nothing>
    data class Failed(val status: Int, val message: String) : AlertApiResult<Nothing>
    data class NetworkError(val cause: Throwable) : AlertApiResult<Nothing>
}

/**
 * Client for the Cloudflare backend.
 *
 * The alert key is used for `status`, `events` and `register`.
 */
@Singleton
class AlertApi @Inject constructor(
    private val client: HttpClient,
    private val baseUrl: String,
) {

    suspend fun register(apiKey: String, request: RegisterRequestDto): AlertApiResult<RegisterResponseDto> =
        post("/api/register", apiKey, request) { it.body<RegisterResponseDto>() }

    suspend fun status(apiKey: String): AlertApiResult<StatusResponseDto> =
        get("/api/status", apiKey) { it.body<StatusResponseDto>() }

    suspend fun events(
        apiKey: String,
        deviceId: String? = null,
        code: String? = null,
        since: Long? = null,
        limit: Int = 200,
    ): AlertApiResult<EventsResponseDto> {
        val query = buildString {
            append("/api/events?limit=").append(limit)
            deviceId?.let { append("&deviceId=").append(it) }
            code?.let { append("&code=").append(it) }
            since?.let { append("&since=").append(it) }
        }
        return get(query, apiKey) { it.body<EventsResponseDto>() }
    }

    private suspend inline fun <reified T> get(
        path: String,
        apiKey: String,
        crossinline parse: suspend (HttpResponse) -> T,
    ): AlertApiResult<T> = try {
        val response = client.get(baseUrl + path) { header("x-api-key", apiKey) }
        interpret(response) { parse(response) }
    } catch (e: Throwable) {
        AlertApiResult.NetworkError(e)
    }

    private suspend inline fun <reified T> post(
        path: String,
        apiKey: String,
        body: Any,
        crossinline parse: suspend (HttpResponse) -> T,
    ): AlertApiResult<T> = try {
        val response = client.post(baseUrl + path) {
            header("x-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        interpret(response) { parse(response) }
    } catch (e: Throwable) {
        AlertApiResult.NetworkError(e)
    }

    private inline fun <T> interpret(response: HttpResponse, parse: () -> T): AlertApiResult<T> =
        when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Accepted -> AlertApiResult.Ok(parse())
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> AlertApiResult.Unauthorized
            else -> AlertApiResult.Failed(response.status.value, response.status.description)
        }
}
