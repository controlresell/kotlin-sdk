package com.controlresell.sdk

import com.controlresell.sdk.generated.apis.AccountsApi
import com.controlresell.sdk.generated.apis.CatalogsApi
import com.controlresell.sdk.generated.apis.ConversationsApi
import com.controlresell.sdk.generated.apis.CustomersApi
import com.controlresell.sdk.generated.apis.FavoritesApi
import com.controlresell.sdk.generated.apis.ItemsApi
import com.controlresell.sdk.generated.apis.MeApi
import com.controlresell.sdk.generated.apis.OrdersApi
import com.controlresell.sdk.generated.apis.WebhooksApi
import com.controlresell.sdk.generated.infrastructure.ApiClient
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json

/**
 * A client of the ControlResell public API, for one API key.
 *
 * ```kotlin
 * val client = ControlResell(apiKey = "cr_live_...")
 * val me = client.me.getMe().body()
 * client.listAllItems(sold = false).collect { println(it.name) }
 * ```
 *
 * Calls are retried on a rate limit (after `Retry-After`), on a gateway or availability error (502, 503, 504) and on a
 * network error; a `POST` always carries the same `Idempotency-Key` across its retries. Any other error is thrown as a
 * [ControlResellError]. The client holds no global state: create one per API key.
 *
 * @param apiKey The API key; `null` reads the `CONTROLRESELL_API_KEY` environment variable.
 * @param baseUrl The API's address.
 * @param engine Your own Ktor engine (CIO, OkHttp, MockEngine…); `null` uses the platform's default engine, which the SDK
 * brings with it (`ktor-client-engine-defaults`), so no engine dependency is needed.
 * @param retry How calls are retried.
 */
class ControlResell(
    apiKey: String? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    engine: HttpClientEngine? = null,
    retry: RetryPolicy = RetryPolicy(),
) : AutoCloseable {

    private val key: String = apiKey ?: environmentVariable(API_KEY_VARIABLE)
        ?: throw IllegalArgumentException("No API key: pass apiKey or set $API_KEY_VARIABLE.")

    /** The HTTP client every API of this client uses, configured with the retries, the key and the error mapping. */
    val httpClient: HttpClient = if (engine != null) HttpClient(engine) { configure(retry) } else HttpClient { configure(retry) }

    /** The seller the API key acts for. */
    val me: MeApi = MeApi(baseUrl, httpClient).authenticated()

    /** The seller's marketplace accounts. */
    val accounts: AccountsApi = AccountsApi(baseUrl, httpClient).authenticated()

    /** The items of the seller's stock. */
    val items: ItemsApi = ItemsApi(baseUrl, httpClient).authenticated()

    /** The conversations with buyers. */
    val conversations: ConversationsApi = ConversationsApi(baseUrl, httpClient).authenticated()

    /** The seller's sales and purchases. */
    val orders: OrdersApi = OrdersApi(baseUrl, httpClient).authenticated()

    /** The other parties of the seller's sales and purchases (needs the `customers:read` scope). */
    val customers: CustomersApi = CustomersApi(baseUrl, httpClient).authenticated()

    /** The buyers who put the seller's listings in their favorites (needs the `items:read` scope). */
    val favorites: FavoritesApi = FavoritesApi(baseUrl, httpClient).authenticated()

    /** The catalog an item is written with: categories, brands, sizes, colors... (needs the `items:read` scope). */
    val catalogs: CatalogsApi = CatalogsApi(baseUrl, httpClient).authenticated()

    /**
     * The seller's webhook endpoints, their secrets and deliveries, and the events of the last 7 days (the
     * `webhooks:read` and `webhooks:write` scopes). An event's `data` is a free-form JSON object.
     */
    val webhooks: WebhooksApi = WebhooksApi(baseUrl, httpClient).authenticated()

    private fun <T : ApiClient> T.authenticated(): T = apply { setBearerToken(key) }

    private fun HttpClientConfig<*>.configure(retry: RetryPolicy) {
        expectSuccess = false
        install(ContentNegotiation) { json(ApiClient.JSON_DEFAULT) }
        defaultRequest { headers.append(HttpHeaders.UserAgent, USER_AGENT) }
        install(IdempotencyKeys)
        installErrors()
        installRetries(retry)
    }

    override fun close() = httpClient.close()

    companion object {
        /** The production address of the API. */
        const val DEFAULT_BASE_URL: String = "https://api.controlresell.com"

        /** The environment variable read when no API key is given. */
        const val API_KEY_VARIABLE: String = "CONTROLRESELL_API_KEY"

        /** The version of this SDK. */
        const val VERSION: String = "0.1.0"

        /** The `User-Agent` of every call. */
        const val USER_AGENT: String = "controlresell-kotlin/$VERSION"
    }
}
