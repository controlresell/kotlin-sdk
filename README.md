# ControlResell Kotlin SDK

Kotlin Multiplatform client for the [ControlResell](https://controlresell.com) public API: JVM, macOS (arm64, x64), Linux (x64, arm64), Windows (mingwX64).

```kotlin
val client = ControlResell(apiKey = "cr_live_...") // or set CONTROLRESELL_API_KEY
val me = client.me.getMe().body()
client.listAllItems(sold = false).collect { println(it.name) }

client.listAllOrders(bucket = "to_send").collect { println(it.id) } // needs the orders:read scope; also client.orders.getOrder(id), listOrderLabels(id), listAllLabelsToShip()

// Writes (API key with items:write / conversations:write); each takes an Idempotency-Key, kept across the retries.
client.items.createItem(Uuid.random().toString(), ItemCreateRequest(name = "Jacket")) // items.updateItem(42, ItemUpdateRequest(...)) patches
client.items.publishItem(42, Uuid.random().toString(), PublishRequest(accountIds = listOf("ACCOUNT_ID")))
client.conversations.sendMessage("CONVERSATION_ID", Uuid.random().toString(), SendMessageRequest(text = "Hello!"))
// republishItem takes a RepublishRequest(): empty, it republishes on every account the item is listed on.
```

No Ktor engine to add: the SDK brings the default engine of each platform. To use your own, pass `engine = CIO.create()` (or any `HttpClientEngine`).

Retries (rate limits, 502/503/504, network errors), `Idempotency-Key` on every `POST`, cursor pagination as a `Flow` and typed errors (`NotFoundException`, `RateLimitException`…) are built in. The client is generated from the API's OpenAPI document (`scripts/generate.sh`, code in `generated/`).

Not published yet. Licensed under the Apache License 2.0.
