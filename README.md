# ControlResell Kotlin SDK

Kotlin Multiplatform client for the [ControlResell](https://controlresell.com) public API: JVM, Android (minSdk 23), iOS (arm64, simulator arm64, x64), macOS (arm64, x64), Linux (x64, arm64), Windows (mingwX64), JavaScript and Wasm (browser and Node.js).

## Install

From the 1.0.0 release, on Maven Central:

```kotlin
implementation("com.controlresell:kotlin-sdk:1.0.0")
```


```kotlin
val client = ControlResell(apiKey = "cr_live_...") // or set CONTROLRESELL_API_KEY
val me = client.me.getMe().body()
client.listAllItems(sold = false).collect { println(it.name) }

client.listAllOrders(bucket = "to_send").collect { println(it.id) } // needs the orders:read scope; also client.orders.getOrder(id), listOrderLabels(id)
client.listAllCatalogBrands(q = "nik").collect { println("${it.id} ${it.name}") } // the identifiers an item is written with (items:read): client.catalogs.getCatalogCategory(id), getCatalogCategoryFields(id), listAllCatalogCategories, listAllCatalogBrands, listAllCatalogSizes, listAllCatalogColors, ...Conditions, ...PackageSizes, ...Materials

client.listAllFavorites(itemId = 42).collect { println("${it.buyerId} ${it.item?.name}") } // the buyers who favorited a listing (items:read); client.listAllItemFavorites(42) too
client.listAllConversations(buyerId = "BUYER_ID", offerStatus = "PENDING").collect { println(it.id) } // buyerId, itemId and offerStatus filter the conversations

// Writes (API key with items:write / conversations:write); each takes an Idempotency-Key, kept across the retries.
client.items.createItem(Uuid.random().toString(), ItemCreateRequest(name = "Jacket")) // items.updateItem(42, ItemUpdateRequest(...)) patches
client.items.publishItem(42, Uuid.random().toString(), PublishRequest(accountIds = listOf("ACCOUNT_ID")))
client.conversations.sendMessage("CONVERSATION_ID", Uuid.random().toString(), SendMessageRequest(text = "Hello!"))
// republishItem takes a RepublishRequest(): empty, it republishes on every account the item is listed on.

// Webhooks (webhooks:read / webhooks:write): client.webhooks lists, reads, creates, updates and deletes an endpoint, reads
// or rotates its secret, tests it, lists and retries its deliveries, recovers the failed ones, lists the event types.
val created = client.webhooks.createWebhookEndpoint(Uuid.random().toString(), WebhookEndpointCreateRequest(url = "https://example.com/hooks", eventTypes = listOf("item.sold"))).body()
println(created.secret) // the signing secret, shown once (webhooks.getWebhookSecret(id) reads it again)
client.listAllWebhookEvents(types = listOf("item.sold")).collect { println("${it.type} ${it.data["itemId"]}") } // the last 7 days
```

No Ktor engine to add: the SDK brings the default engine of each platform (OkHttp on Android, Darwin on iOS and macOS, `fetch` on JavaScript and Wasm). In a browser there is no environment variable to read, so `apiKey` is required; an API key must never ship in a web page, so use the JavaScript and Wasm targets from a server (Node.js) and not from front-end code. To use your own, pass `engine = CIO.create()` (or any `HttpClientEngine`).

Retries (rate limits, 502/503/504, network errors), `Idempotency-Key` on every `POST`, cursor pagination as a `Flow` and typed errors (`NotFoundException`, `RateLimitException`…) are built in. The client is generated from the API's OpenAPI document (`scripts/generate.sh`, code in `generated/`).

Licensed under the Apache License 2.0.
