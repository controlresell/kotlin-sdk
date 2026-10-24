package com.controlresell.sdk

import com.controlresell.sdk.generated.infrastructure.ApiClient
import com.controlresell.sdk.generated.models.ErrorResponse
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.HttpRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlin.random.Random
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.io.IOException

/**
 * How a call is retried.
 *
 * @param maxRetries Retries after the first attempt (2 by default); 0 turns them off.
 * @param baseDelayMillis The first delay, doubled at each retry, with a random part; a `Retry-After` takes precedence.
 * @param maxDelayMillis The longest delay between two attempts.
 */
data class RetryPolicy(
    val maxRetries: Int = 2,
    val baseDelayMillis: Long = 500,
    val maxDelayMillis: Long = 30_000,
)

/** Statuses retried for every method: a rate limit, a gateway or availability error. */
private val RETRIED_STATUSES = setOf(429, 502, 503, 504)

/** The code of the 409 answered while the first request with the same `Idempotency-Key` is still running. */
private const val IDEMPOTENCY_IN_PROGRESS = "idempotency_in_progress"

/**
 * Retries a 409 `idempotency_in_progress` (the first request with the same key is still running), with the same
 * delays as [installRetries]; any other 409 is returned as is. The key is the one of the first attempt.
 */
internal fun retryInProgress(policy: RetryPolicy) = createClientPlugin("ControlResellRetryInProgress") {
    on(Send) { request ->
        var call = proceed(request)
        var attempt = 0
        while (attempt < policy.maxRetries && call.response.status.value == 409 && call.response.isInProgress()) {
            val retryAfter = call.response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1000)
            val backoff = policy.baseDelayMillis * (1L shl attempt) + Random.nextLong(policy.baseDelayMillis / 2 + 1)
            delay((retryAfter ?: backoff).coerceAtMost(policy.maxDelayMillis))
            attempt++
            call = proceed(request)
        }
        call
    }
}

private suspend fun HttpResponse.isInProgress(): Boolean =
    runCatching { ApiClient.JSON_DEFAULT.decodeFromString(ErrorResponse.serializer(), bodyAsText()).error.code }
        .getOrNull() == IDEMPOTENCY_IN_PROGRESS

private val WEBHOOK_TEST_PATH = Regex("/webhook-endpoints/[^/]+/test/?$")

/**
 * `POST /webhook-endpoints/{id}/test` sends a real event and the API ignores its `Idempotency-Key`: a replay would send
 * a second event. Only a 429 (answered before the event leaves) is retried; a 5xx or a network error may come after it,
 * and a common engine cannot tell a refused connection from a request that reached the server.
 */
internal fun HttpRequest.isWebhookTest(): Boolean = isWebhookTest(method, url.encodedPath)

private fun HttpRequestBuilder.isWebhookTest(): Boolean = isWebhookTest(method, url.build().encodedPath)

private fun isWebhookTest(method: HttpMethod, path: String): Boolean =
    method == HttpMethod.Post && WEBHOOK_TEST_PATH.containsMatchIn(path)

internal fun HttpClientConfig<*>.installRetries(policy: RetryPolicy) {
    if (policy.maxRetries <= 0) return
    install(retryInProgress(policy))
    install(HttpRequestRetry) {
        maxRetries = policy.maxRetries
        // A 500 is not retried: a POST may have been applied, and its key would replay the same 500.
        retryIf { request, response ->
            response.status.value in RETRIED_STATUSES && (response.status.value == 429 || !request.isWebhookTest())
        }
        retryOnExceptionIf { request, cause -> cause is IOException && !request.isWebhookTest() }
        exponentialDelay(
            base = 2.0,
            baseDelayMs = policy.baseDelayMillis,
            maxDelayMs = policy.maxDelayMillis,
            randomizationMs = policy.baseDelayMillis / 2,
            respectRetryAfterHeader = true,
        )
    }
}
