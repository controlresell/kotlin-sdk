package com.controlresell.sdk

import io.ktor.client.HttpClientConfig
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

/** Statuses retried for every method: a rate limit, a conflict on a key still in flight, a gateway or availability error. */
private val RETRIED_STATUSES = setOf(409, 429, 502, 503, 504)

internal fun HttpClientConfig<*>.installRetries(policy: RetryPolicy) {
    if (policy.maxRetries <= 0) return
    install(HttpRequestRetry) {
        maxRetries = policy.maxRetries
        // A 500 is not retried: a POST may have been applied, and its key would replay the same 500.
        retryIf { _, response -> response.status.value in RETRIED_STATUSES }
        retryOnExceptionIf { _, cause -> cause is IOException }
        exponentialDelay(
            base = 2.0,
            baseDelayMs = policy.baseDelayMillis,
            maxDelayMs = policy.maxDelayMillis,
            randomizationMs = policy.baseDelayMillis / 2,
            respectRetryAfterHeader = true,
        )
    }
}
