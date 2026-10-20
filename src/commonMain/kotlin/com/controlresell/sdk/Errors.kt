package com.controlresell.sdk

import com.controlresell.sdk.generated.infrastructure.ApiClient
import com.controlresell.sdk.generated.models.ErrorResponse
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.io.IOException

/**
 * An error answered by the API, read from its envelope `{"error": {code, message, params, requestId}}`.
 *
 * `code` is an open string: match the codes you handle and treat any other one by its [status].
 *
 * @property status The HTTP status.
 * @property code The error's code (`item_not_found`, `rate_limited`…).
 * @property params The error's parameters.
 * @property requestId The identifier to give the support.
 */
open class ControlResellError(
    val status: Int,
    val code: String,
    message: String,
    val params: Map<String, String>,
    val requestId: String?,
) : Exception(message)

/** 400: the request is malformed. */
class BadRequestException(code: String, message: String, params: Map<String, String>, requestId: String?) :
    ControlResellError(400, code, message, params, requestId)

/** 401: no credential, or an unknown, revoked or expired one. */
class AuthenticationException(code: String, message: String, params: Map<String, String>, requestId: String?) :
    ControlResellError(401, code, message, params, requestId)

/** 402: the seller's plan does not include this. */
class PaymentRequiredException(code: String, message: String, params: Map<String, String>, requestId: String?) :
    ControlResellError(402, code, message, params, requestId)

/** 403: the API key does not grant the scope the route requires. */
class PermissionDeniedException(code: String, message: String, params: Map<String, String>, requestId: String?) :
    ControlResellError(403, code, message, params, requestId)

/** 404: no such resource for this seller. */
class NotFoundException(code: String, message: String, params: Map<String, String>, requestId: String?) :
    ControlResellError(404, code, message, params, requestId)

/** 409: an `Idempotency-Key` used with another request, or still in flight. */
class ConflictException(code: String, message: String, params: Map<String, String>, requestId: String?) :
    ControlResellError(409, code, message, params, requestId)

/** 422: the request is well formed but refused. */
class UnprocessableEntityException(code: String, message: String, params: Map<String, String>, requestId: String?) :
    ControlResellError(422, code, message, params, requestId)

/**
 * 429: the rate limit of the API key is reached.
 *
 * @property retryAfterSeconds The `Retry-After` the API answered, when it did.
 */
class RateLimitException(
    code: String,
    message: String,
    params: Map<String, String>,
    requestId: String?,
    val retryAfterSeconds: Long?,
) : ControlResellError(429, code, message, params, requestId)

/** 5xx: an error of the API; retry later. */
class ServerException(status: Int, code: String, message: String, params: Map<String, String>, requestId: String?) :
    ControlResellError(status, code, message, params, requestId)

/** The API could not be reached (network, DNS, TLS), after the retries. */
class ControlResellConnectionException(cause: Throwable) :
    Exception("Could not reach the ControlResell API: ${cause.message}", cause)

internal fun HttpClientConfig<*>.installErrors() {
    HttpResponseValidator {
        validateResponse { response -> if (response.status.value >= 400) throw response.toException() }
        handleResponseExceptionWithRequest { cause, _ ->
            if (cause is IOException) throw ControlResellConnectionException(cause)
        }
    }
}

internal suspend fun HttpResponse.toException(): ControlResellError {
    val status = status.value
    val error = runCatching { ApiClient.JSON_DEFAULT.decodeFromString(ErrorResponse.serializer(), bodyAsText()).error }
        .getOrNull()
    val code = error?.code ?: "http_$status"
    val message = error?.message ?: "HTTP $status"
    val params = error?.params.orEmpty()
    val requestId = error?.requestId
    return when (status) {
        400 -> BadRequestException(code, message, params, requestId)
        401 -> AuthenticationException(code, message, params, requestId)
        402 -> PaymentRequiredException(code, message, params, requestId)
        403 -> PermissionDeniedException(code, message, params, requestId)
        404 -> NotFoundException(code, message, params, requestId)
        409 -> ConflictException(code, message, params, requestId)
        422 -> UnprocessableEntityException(code, message, params, requestId)
        429 -> RateLimitException(code, message, params, requestId, headers[HttpHeaders.RetryAfter]?.toLongOrNull())
        in 500..599 -> ServerException(status, code, message, params, requestId)
        else -> ControlResellError(status, code, message, params, requestId)
    }
}
