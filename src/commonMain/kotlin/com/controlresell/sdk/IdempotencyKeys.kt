package com.controlresell.sdk

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpMethod
import kotlin.uuid.Uuid

/** The header that makes a `POST` safe to retry: the API replays the first answer to the same key. */
const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"

/**
 * Gives each `POST` an `Idempotency-Key` (a random UUID) when the caller sets none. It runs once per call, before the
 * retries, so every retry of the call sends the same key.
 */
internal val IdempotencyKeys = createClientPlugin("ControlResellIdempotencyKeys") {
    onRequest { request, _ ->
        if (request.method == HttpMethod.Post && !request.headers.contains(IDEMPOTENCY_KEY_HEADER)) {
            request.headers.append(IDEMPOTENCY_KEY_HEADER, Uuid.random().toString())
        }
    }
}
