package com.controlresell.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlResellTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val fast = RetryPolicy(maxRetries = 2, baseDelayMillis = 1, maxDelayMillis = 5)

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        ControlResell(apiKey = KEY, baseUrl = "https://api.test", engine = MockEngine(handler), retry = fast)

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String, extra: Map<String, String> = emptyMap()) =
        respond(
            """{"error":{"code":"$code","message":"m","params":{},"requestId":"req-1"}}""",
            status,
            headersOf(*(extra + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()),
        )

    @Test
    fun call_sendsTheKeyAndTheUserAgent() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        client { request ->
            requests += request
            respond("""{"id":1,"username":"seller","currency":"EUR","unknownField":true}""", HttpStatusCode.OK, jsonHeaders)
        }.me.getMe()

        assertEquals("Bearer $KEY", requests.single().headers[HttpHeaders.Authorization])
        assertEquals(ControlResell.USER_AGENT, requests.single().headers[HttpHeaders.UserAgent])
    }

    @Test
    fun error_isTypedByStatus_withItsCodeAndRequestId() = runTest {
        val e = assertFailsWith<NotFoundException> { client { error(HttpStatusCode.NotFound, "item_not_found") }.items.getItem(1) }

        assertEquals("item_not_found", e.code)
        assertEquals("req-1", e.requestId)
        assertEquals(404, e.status)
        assertFalse(KEY in (e.message ?: ""), "the key never appears in an error")
    }

    @Test
    fun rateLimit_isRetriedAfterRetryAfter_thenSucceeds() = runTest {
        var calls = 0
        val e = runCatching {
            client {
                calls++
                if (calls == 1) error(HttpStatusCode.TooManyRequests, "rate_limited", mapOf(HttpHeaders.RetryAfter to "0"))
                else respond("""{"data":[],"nextCursor":null}""", HttpStatusCode.OK, jsonHeaders)
            }.accounts.listAccounts()
        }.exceptionOrNull()

        assertNull(e)
        assertEquals(2, calls)
    }

    @Test
    fun rateLimit_afterTheRetries_isARateLimitException() = runTest {
        var calls = 0
        val e = assertFailsWith<RateLimitException> {
            client { calls++; error(HttpStatusCode.TooManyRequests, "rate_limited", mapOf(HttpHeaders.RetryAfter to "0")) }
                .accounts.listAccounts()
        }

        assertEquals(3, calls, "the first attempt and two retries")
        assertEquals(0L, e.retryAfterSeconds)
    }

    @Test
    fun post_keepsTheSameIdempotencyKeyAcrossRetries() = runTest {
        val keys = mutableListOf<String?>()
        val response = client { request ->
            keys += request.headers[IDEMPOTENCY_KEY_HEADER]
            if (keys.size < 3) error(HttpStatusCode.ServiceUnavailable, "idempotency_unavailable")
            else respond("{}", HttpStatusCode.Created, jsonHeaders)
        }.let { it.httpClient.post("https://api.test/api/public/v1/anything") }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(3, keys.size)
        assertTrue(keys.all { it != null && it == keys.first() }, "$keys")
    }

    @Test
    fun post_aKeyOfTheCaller_isKept() = runTest {
        var key: String? = null
        client { request -> key = request.headers[IDEMPOTENCY_KEY_HEADER]; respond("{}", HttpStatusCode.OK, jsonHeaders) }
            .httpClient.post("https://api.test/x") { headers.append(IDEMPOTENCY_KEY_HEADER, "mine") }

        assertEquals("mine", key)
    }

    @Test
    fun serverError_ofAPost_isNotRetried() = runTest {
        var calls = 0
        assertFailsWith<ServerException> {
            client { calls++; error(HttpStatusCode.InternalServerError, "internal_error") }
                .httpClient.post("https://api.test/x").bodyAsText()
        }

        assertEquals(1, calls)
    }

    @Test
    fun listAllItems_readsEveryPage_withTheSameFilters() = runTest {
        val seen = mutableListOf<Pair<String?, String?>>()
        val items = client { request ->
            val cursor = request.url.parameters["cursor"]
            seen += cursor to request.url.parameters["sold"]
            val (id, next) = if (cursor == null) 1L to "\"c2\"" else 2L to "null"
            respond("""{"data":[${item(id)}],"nextCursor":$next}""", HttpStatusCode.OK, jsonHeaders)
        }.listAllItems(sold = false).toList()

        assertEquals(listOf(1L, 2L), items.map { it.id })
        assertEquals(listOf<Pair<String?, String?>>(null to "false", "c2" to "false"), seen)
    }

    @Test
    fun item_keepsItsAmountAsADecimalString_andToleratesUnknownValues() = runTest {
        val item = client {
            respond(item(9007199254740993, sex = "A_NEW_VALUE"), HttpStatusCode.OK, jsonHeaders)
        }.items.getItem(9007199254740993).body()

        assertEquals(9007199254740993, item.id)
        assertEquals("12.50", item.estimatedPrice?.amount)
        assertEquals("A_NEW_VALUE", item.sex)
        assertNull(item.purchasePrice)
    }

    private fun item(id: Long, sex: String = "WOMEN") = """{"id":$id,"name":"n","sku":null,"description":null,"photos":[],
        "categoryId":null,"brandId":null,"sizeId":null,"colorIds":[],"state":null,"sex":"$sex","labels":[],"sold":false,
        "estimatedPrice":{"amount":"12.50","currency":"EUR"},"purchasePrice":null,"purchaseDate":null,"sellingPrice":null,
        "sellingDate":null,"createdAt":"2026-10-20T10:00:00Z","updatedAt":"2026-10-20T10:00:00Z","unknownField":1}"""

    private companion object {
        const val KEY = "cr_test_secret"
    }
}
