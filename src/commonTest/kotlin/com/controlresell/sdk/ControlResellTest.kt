package com.controlresell.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import com.controlresell.sdk.generated.models.ItemCreateRequest
import com.controlresell.sdk.generated.models.ItemUpdateRequest
import com.controlresell.sdk.generated.models.Money
import com.controlresell.sdk.generated.models.PriceDropRequest
import com.controlresell.sdk.generated.models.PublishRequest
import com.controlresell.sdk.generated.models.RepublishRequest
import com.controlresell.sdk.generated.models.SendMessageRequest
import com.controlresell.sdk.generated.models.SendOfferRequest
import com.controlresell.sdk.generated.models.WebhookEndpointCreateRequest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

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
    fun conflict_isRetriedOnlyWhenIdempotencyInProgress() = runTest {
        var calls = 0
        assertFailsWith<ConflictException> {
            client { calls++; error(HttpStatusCode.Conflict, "idempotency_key_reused") }.me.getMe()
        }
        assertEquals(1, calls, "another 409 is not retried")

        calls = 0
        val e = runCatching {
            client {
                calls++
                if (calls == 1) error(HttpStatusCode.Conflict, "idempotency_in_progress")
                else respond("""{"id":1,"username":"u","currency":"EUR"}""", HttpStatusCode.OK, jsonHeaders)
            }.me.getMe()
        }.exceptionOrNull()
        assertNull(e)
        assertEquals(2, calls)
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

    @Test
    fun publishItem_postsTheAccounts_withTheCallersKey() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val action = client { request ->
            requests += request
            respond(ACTION, HttpStatusCode.Accepted, jsonHeaders)
        }.items.publishItem(7, "my-key", PublishRequest(accountIds = listOf(ACCOUNT))).body()

        assertEquals(7L, action.itemId)
        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/api/public/v1/items/7/publish", requests.single().url.encodedPath)
        assertEquals("my-key", requests.single().headers[IDEMPOTENCY_KEY_HEADER])
    }

    @Test
    fun publishItem_keepsTheSameKeyAcrossRetries() = runTest {
        val keys = mutableListOf<String?>()
        client { request ->
            keys += request.headers[IDEMPOTENCY_KEY_HEADER]
            if (keys.size < 2) error(HttpStatusCode.ServiceUnavailable, "idempotency_unavailable")
            else respond(ACTION, HttpStatusCode.Accepted, jsonHeaders)
        }.items.publishItem(7, "k", PublishRequest(accountIds = listOf(ACCOUNT)))

        assertEquals(listOf<String?>("k", "k"), keys)
    }

    @Test
    fun priceDropAndRepublish_postToTheirRoutes() = runTest {
        val paths = mutableListOf<String>()
        val keys = mutableListOf<String?>()
        val bodies = mutableListOf<String>()
        val sdk = client { request ->
            paths += request.url.encodedPath
            keys += request.headers[IDEMPOTENCY_KEY_HEADER]
            bodies += (request.body as io.ktor.http.content.TextContent).text.filterNot { it.isWhitespace() }
            respond(ACTION, HttpStatusCode.Accepted, jsonHeaders)
        }
        sdk.items.dropItemPrice(7, "k1", PriceDropRequest(type = "PERCENT", value = "10"))
        sdk.items.republishItem(7, "k2", RepublishRequest())

        assertEquals(listOf("/api/public/v1/items/7/price-drop", "/api/public/v1/items/7/republish"), paths)
        assertEquals(listOf<String?>("k1", "k2"), keys)
        assertEquals(listOf("""{"type":"PERCENT","value":"10"}""", "{}"), bodies)
    }

    @Test
    fun deleteItem_resolvesOn204_andIsTypedOn409() = runTest {
        var method: HttpMethod? = null
        client { request -> method = request.method; respond("", HttpStatusCode.NoContent) }.items.deleteItem(7)
        assertEquals(HttpMethod.Delete, method)

        val e = assertFailsWith<ConflictException> { client { error(HttpStatusCode.Conflict, "item_still_listed") }.items.deleteItem(7) }
        assertEquals("item_still_listed", e.code)
    }

    @Test
    fun sendMessageAndSendOffer_postToTheConversation() = runTest {
        val paths = mutableListOf<String>()
        val sdk = client { request -> paths += request.url.encodedPath; respond(MESSAGE, HttpStatusCode.Created, jsonHeaders) }
        sdk.conversations.sendMessage(ACCOUNT, "k1", SendMessageRequest(text = "hi"))
        sdk.conversations.sendOffer(ACCOUNT, "k2", SendOfferRequest(price = Money(amount = "12.50", currency = "EUR")))

        assertEquals(listOf("/api/public/v1/conversations/$ACCOUNT/messages", "/api/public/v1/conversations/$ACCOUNT/offers"), paths)
    }

    @Test
    fun createItem_postsTheItemWithTheCallersKey_andUpdateItemPatchesItWithoutOne() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val sdk = client { request ->
            requests += request
            respond(item(7), if (request.method == HttpMethod.Post) HttpStatusCode.Created else HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals(7L, sdk.items.createItem("k1", ItemCreateRequest(name = "n")).body().id)
        assertEquals(7L, sdk.items.updateItem(7, ItemUpdateRequest(name = "m")).body().id)

        assertEquals(HttpMethod.Post, requests[0].method)
        assertEquals("/api/public/v1/items", requests[0].url.encodedPath)
        assertEquals("k1", requests[0].headers[IDEMPOTENCY_KEY_HEADER])
        assertEquals(HttpMethod.Patch, requests[1].method)
        assertEquals("/api/public/v1/items/7", requests[1].url.encodedPath)
        assertNull(requests[1].headers[IDEMPOTENCY_KEY_HEADER])
        assertEquals("""{"name":"m"}""", (requests[1].body as OutgoingContent.ByteArrayContent).bytes().decodeToString().filterNot { it.isWhitespace() })
    }

    @Test
    fun getOrderAndListOrderLabels_readTheOrderAndItsLabels() = runTest {
        val paths = mutableListOf<String>()
        val sdk = client { request ->
            paths += request.url.encodedPath
            respond(if (request.url.encodedPath.endsWith("/labels")) """{"data":[],"nextCursor":null}""" else order("p1"), HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals("p1", sdk.orders.getOrder(ACCOUNT).body().platformOrderId)
        assertTrue(sdk.orders.listOrderLabels(ACCOUNT).body().`data`.isEmpty())
        assertEquals(listOf("/api/public/v1/orders/$ACCOUNT", "/api/public/v1/orders/$ACCOUNT/labels"), paths)
    }

    @Test
    fun listAllOrders_readsEveryPage_withTheSameFilters() = runTest {
        val seen = mutableListOf<Map<String, String?>>()
        val orders = client { request ->
            val p = request.url.parameters
            seen += mapOf("cursor" to p["cursor"], "bucket" to p["bucket"], "platform" to p["platform"], "from" to p["from"])
            val (id, next) = if (p["cursor"] == null) "a" to "\"c2\"" else "b" to "null"
            respond("""{"data":[${order(id)}],"nextCursor":$next}""", HttpStatusCode.OK, jsonHeaders)
        }.listAllOrders(bucket = "to_send", platform = "VINTED", from = Instant.parse("2026-10-01T00:00:00Z")).toList()

        assertEquals(listOf("a", "b"), orders.map { it.platformOrderId })
        assertEquals(listOf(null, "c2"), seen.map { it["cursor"] })
        assertTrue(seen.all { it["bucket"] == "to_send" && it["platform"] == "VINTED" && it["from"]?.startsWith("2026-10-01") == true }, seen.toString())
    }

    @Test
    fun listAllLabelsToShip_readsEveryPage_onOrdersLabels() = runTest {
        val seen = mutableListOf<Pair<String, String?>>()
        val labels = client { request ->
            seen += request.url.encodedPath to request.url.parameters["cursor"]
            val (id, next) = if (request.url.parameters["cursor"] == null) "a" to "\"c2\"" else "b" to "null"
            respond("""{"data":[{"id":"$ACCOUNT","orderId":"$ACCOUNT","carrier":"$id"}],"nextCursor":$next}""", HttpStatusCode.OK, jsonHeaders)
        }.listAllLabelsToShip().toList()

        assertEquals(listOf("a", "b"), labels.map { it.carrier })
        assertEquals(listOf("/api/public/v1/orders/labels" to null, "/api/public/v1/orders/labels" to "c2"), seen)
    }

    @Test
    fun listAllConversationMessages_sendsTheFromAndToWindow() = runTest {
        var query = ""
        client { request ->
            query = request.url.parameters.entries().joinToString("&") { "${it.key}=${it.value.first()}" }
            respond("""{"data":[],"nextCursor":null}""", HttpStatusCode.OK, jsonHeaders)
        }.listAllConversationMessages(ACCOUNT, from = Instant.parse("2026-10-01T00:00:00Z"), to = Instant.parse("2026-10-02T00:00:00Z")).toList()

        assertTrue(query.contains("from=2026-10-01") && query.contains("to=2026-10-02"), query)
    }

    @Test
    fun listAllConversations_sendsTheBuyerItemAndOfferStatusFilters() = runTest {
        var query = ""
        client { request ->
            query = request.url.parameters.entries().joinToString("&") { "${it.key}=${it.value.first()}" }
            respond("""{"data":[],"nextCursor":null}""", HttpStatusCode.OK, jsonHeaders)
        }.listAllConversations(buyerId = "b-1", itemId = 42, offerStatus = "PENDING").toList()

        assertTrue(query.contains("buyerId=b-1") && query.contains("itemId=42") && query.contains("offerStatus=PENDING"), query)
    }

    @Test
    fun listAllFavorites_readsEveryPage_withTheSameFilters() = runTest {
        val seen = mutableListOf<Triple<String, String?, String?>>()
        val favorites = client { request ->
            val p = request.url.parameters
            seen += Triple(request.url.encodedPath, p["cursor"], p["itemId"])
            val (buyer, next) = if (p["cursor"] == null) "b1" to "\"c2\"" else "b2" to "null"
            respond("""{"data":[${favorite(buyer)}],"nextCursor":$next}""", HttpStatusCode.OK, jsonHeaders)
        }.listAllFavorites(itemId = 42, from = Instant.parse("2026-10-01T00:00:00Z")).toList()

        assertEquals(listOf("b1", "b2"), favorites.map { it.buyerId })
        assertEquals("Jacket", favorites.first().item?.name)
        assertEquals(
            listOf<Triple<String, String?, String?>>(Triple("/api/public/v1/favorites", null, "42"), Triple("/api/public/v1/favorites", "c2", "42")),
            seen,
        )
    }

    @Test
    fun listAllItemFavorites_readsTheFavoritesOfOneItem_andAnUnfollowedListingHasNoItem() = runTest {
        var path = ""
        val favorites = client { request ->
            path = request.url.encodedPath
            respond("""{"data":[${favorite("b1", item = "null")}],"nextCursor":null}""", HttpStatusCode.OK, jsonHeaders)
        }.listAllItemFavorites(42).toList()

        assertEquals("/api/public/v1/items/42/favorites", path)
        assertEquals(null, favorites.single().item)
    }

    @Test
    fun listAllCustomers_readsEveryPage_withTheSameFilters() = runTest {
        val seen = mutableListOf<Triple<String, String?, String?>>()
        val customers = client { request ->
            val p = request.url.parameters
            seen += Triple(request.url.encodedPath, p["cursor"], p["accountId"])
            val (name, next) = if (p["cursor"] == null) "Ann" to "\"c2\"" else "Bob" to "null"
            respond("""{"data":[${customer(name)}],"nextCursor":$next}""", HttpStatusCode.OK, jsonHeaders)
        }.listAllCustomers(accountId = ACCOUNT, from = Instant.parse("2026-10-01T00:00:00Z")).toList()

        assertEquals(listOf("Ann", "Bob"), customers.map { it.firstName })
        assertEquals(
            listOf<Triple<String, String?, String?>>(Triple("/api/public/v1/customers", null, ACCOUNT), Triple("/api/public/v1/customers", "c2", ACCOUNT)),
            seen,
        )
    }

    @Test
    fun listAllCatalogBrands_readsEveryPage_withTheSameQuery() = runTest {
        val seen = mutableListOf<Triple<String, String?, String?>>()
        val brands = client { request ->
            val p = request.url.parameters
            seen += Triple(request.url.encodedPath, p["cursor"], p["q"])
            val (id, name, next) = if (p["cursor"] == null) Triple(1, "Nike", "\"c2\"") else Triple(2, "Nikon", "null")
            respond("""{"data":[{"id":$id,"name":"$name","custom":false}],"nextCursor":$next}""", HttpStatusCode.OK, jsonHeaders)
        }.listAllCatalogBrands(q = "nik").toList()

        assertEquals(listOf("Nike", "Nikon"), brands.map { it.name })
        assertEquals(
            listOf<Triple<String, String?, String?>>(
                Triple("/api/public/v1/catalogs/brands", null, "nik"), Triple("/api/public/v1/catalogs/brands", "c2", "nik"),
            ),
            seen,
        )
    }

    @Test
    fun catalogColors_sendTheCategory_andReadTheValues() = runTest {
        var query = ""
        val page = client { request ->
            query = request.url.encodedPath + "?" + request.url.parameters.entries().joinToString("&") { (k, v) -> "$k=${v.first()}" }
            respond("""{"data":[{"id":3,"key":"red","title":"Red"}],"nextCursor":null}""", HttpStatusCode.OK, jsonHeaders)
        }.catalogs.listCatalogColors(categoryId = 12).body()

        assertEquals("Red", page.`data`.single().title)
        assertTrue(query.startsWith("/api/public/v1/catalogs/colors?") && query.contains("categoryId=12"), query)
    }

    @Test
    fun createWebhookEndpoint_postsWithTheCallersKey_andReturnsTheSecret() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val created = client { request ->
            requests += request
            respond("""{"endpoint":$ENDPOINT,"secret":"whsec_abc"}""", HttpStatusCode.Created, jsonHeaders)
        }.webhooks.createWebhookEndpoint("k1", WebhookEndpointCreateRequest(url = "https://example.com/hook", eventTypes = listOf("item.sold"))).body()

        assertEquals("whsec_abc", created.secret)
        assertEquals("enabled", created.endpoint.status)
        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/api/public/v1/webhook-endpoints", requests.single().url.encodedPath)
        assertEquals("k1", requests.single().headers[IDEMPOTENCY_KEY_HEADER])
    }

    @Test
    fun listAllWebhookEvents_readsEveryPage_withTheSameTypes_andAnyData() = runTest {
        val seen = mutableListOf<Pair<String?, String?>>()
        val events = client { request ->
            val p = request.url.parameters
            seen += p["cursor"] to p["type"]
            respond(
                if (p["cursor"] == null) {
                    """{"data":[{"id":"e1","type":"item.sold","createdAt":"2026-10-24T00:00:00Z","apiVersion":"v1","sellerId":7,"data":{"itemId":42,"source":"order","object":null}}],"nextCursor":"c2"}"""
                } else {
                    """{"data":[{"id":"e2","type":"order.created","createdAt":"2026-10-24T00:01:00Z","apiVersion":"v1","sellerId":7,"data":{"orderId":"o1","items":[1,2]}}],"nextCursor":null}"""
                },
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }.listAllWebhookEvents(types = listOf("item.sold", "order.created")).toList()

        assertEquals(listOf("item.sold", "order.created"), events.map { it.type })
        assertEquals(42, events[0].`data`.getValue("itemId").jsonPrimitive.int)
        assertEquals(listOf<Pair<String?, String?>>(null to "item.sold,order.created", "c2" to "item.sold,order.created"), seen)
    }

    @Test
    fun webhookDeliveries_sendTheirFilters_retryPostsToTheDelivery_andDeleteResolvesOn204() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val sdk = client { request ->
            requests += request
            when (request.method) {
                HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                HttpMethod.Get -> respond("""{"data":[$DELIVERY],"nextCursor":null}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond(DELIVERY, HttpStatusCode.Accepted, jsonHeaders)
            }
        }
        assertEquals(3L, sdk.listAllWebhookDeliveries("e-1", status = "failed").toList().single().attempt)
        sdk.webhooks.retryWebhookDelivery("e-1", "d1", "k2")
        sdk.webhooks.deleteWebhookEndpoint("e-1")

        assertEquals("failed", requests[0].url.parameters["status"])
        assertEquals("/api/public/v1/webhook-endpoints/e-1/deliveries/d1/retry", requests[1].url.encodedPath)
        assertEquals("k2", requests[1].headers[IDEMPOTENCY_KEY_HEADER])
        assertEquals(HttpMethod.Delete, requests[2].method)
    }

    @Test
    fun testWebhookEndpoint_getsAKey_andReadsTheResult() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val result = client { request ->
            requests += request
            respond("""{"success":true,"statusCode":200,"durationMs":12,"error":null}""", HttpStatusCode.OK, jsonHeaders)
        }.webhooks.testWebhookEndpoint("e-1").body()

        assertTrue(result.success)
        assertEquals("/api/public/v1/webhook-endpoints/e-1/test", requests.single().url.encodedPath)
        assertTrue(!requests.single().headers[IDEMPOTENCY_KEY_HEADER].isNullOrEmpty())
    }

    @Test
    fun testWebhookEndpoint_isNeverReplayedAfterA5xxOrANetworkError_butIsAfterA429() = runTest {
        for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable, HttpStatusCode.GatewayTimeout)) {
            var calls = 0
            assertFailsWith<Throwable> {
                client { calls++; error(status, "unavailable") }.webhooks.testWebhookEndpoint("e-1").body()
            }
            assertEquals(1, calls, "status ${status.value} must not be replayed")
        }
        var calls = 0
        assertFailsWith<Throwable> {
            client { calls++; error(HttpStatusCode.Conflict, "idempotency_in_progress") }.webhooks.testWebhookEndpoint("e-1").body()
        }
        assertEquals(1, calls, "a 409 in progress must not be replayed")
        calls = 0
        assertFailsWith<Throwable> {
            client { calls++; throw kotlinx.io.IOException("connection lost") }.webhooks.testWebhookEndpoint("e-1").body()
        }
        assertEquals(1, calls, "a network error must not be replayed")

        calls = 0
        val result = client {
            if (++calls == 1) error(HttpStatusCode.TooManyRequests, "webhook_endpoint_busy", mapOf(HttpHeaders.RetryAfter to "0"))
            else respond("""{"success":true,"statusCode":200,"durationMs":12,"error":null}""", HttpStatusCode.OK, jsonHeaders)
        }.webhooks.testWebhookEndpoint("e-1").body()
        assertTrue(result.success)
        assertEquals(2, calls, "a 429 is answered before the event leaves")

        calls = 0
        client {
            if (++calls == 1) error(HttpStatusCode.ServiceUnavailable, "unavailable")
            else respond("""{"id":1,"username":"seller","currency":"EUR"}""", HttpStatusCode.OK, jsonHeaders)
        }.me.getMe()
        assertEquals(2, calls, "other calls keep the retry on 503")
    }

    private val ENDPOINT = """{"id":"e-1","url":"https://example.com/hook","description":null,"eventTypes":["item.sold"],"payloadMode":"light","status":"enabled","apiVersion":"v1","createdAt":"2026-10-24T00:00:00Z","updatedAt":"2026-10-24T00:00:00Z"}"""
    private val DELIVERY = """{"id":"d1","eventId":"e1","eventType":"item.sold","status":"failed","attempt":3,"createdAt":"2026-10-24T00:00:00Z","updatedAt":"2026-10-24T00:00:00Z"}"""

    private fun favorite(buyerId: String, item: String = """{"id":42,"name":"Jacket","price":{"amount":"12.50","currency":"EUR"},"sold":false}""") =
        """{"id":"$ACCOUNT","accountId":"$ACCOUNT","platform":"VINTED","buyerId":"$buyerId","createdAt":"2026-10-24T00:00:00Z","messageSentAt":null,"listingId":"L1","item":$item}"""

    private fun customer(firstName: String) = """{"id":"$ACCOUNT","firstName":"$firstName","lastName":"Doe","email":null,
        "address":{"country":"FR"},"createdAt":"2026-10-21T10:00:00Z"}"""

    private fun order(platformOrderId: String) = """{"id":"$ACCOUNT","accountId":"$ACCOUNT","type":"SALE","status":"PAID",
        "platformOrderId":"$platformOrderId","price":{"amount":"12.50","currency":"EUR"},"prepared":false,
        "date":"2026-10-21T10:00:00Z","items":[]}"""

    private fun item(id: Long, sex: String = "WOMEN") = """{"id":$id,"name":"n","sku":null,"description":null,"photos":[],
        "categoryId":null,"brandId":null,"sizeId":null,"colorIds":[],"state":null,"sex":"$sex","labels":[],"sold":false,"materialIds":[],"enumValues":[],"attributes":[],"customFields":[],
        "estimatedPrice":{"amount":"12.50","currency":"EUR"},"purchasePrice":null,"purchaseDate":null,"sellingPrice":null,
        "sellingDate":null,"createdAt":"2026-10-20T10:00:00Z","updatedAt":"2026-10-20T10:00:00Z","unknownField":1}"""

    private companion object {
        const val KEY = "cr_test_secret"
        const val ACCOUNT = "3f0c2a52-0b0c-4d65-9f40-2d2d3b1f4a10"
        const val ACTION = """{"itemId":7,"action":"PUBLISH","accountIds":[],"scheduledAt":"2026-10-21T10:00:00Z"}"""
        const val MESSAGE = """{"id":"$ACCOUNT","conversationId":"$ACCOUNT","author":"SELLER","type":"TEXT","status":"SENT","body":"hi","createdAt":"2026-10-21T10:00:00Z"}"""
    }
}
