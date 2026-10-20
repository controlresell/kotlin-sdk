# ControlResell Kotlin SDK

Kotlin Multiplatform client for the [ControlResell](https://controlresell.com) public API: JVM, macOS (arm64, x64), Linux (x64, arm64), Windows (mingwX64).

```kotlin
val client = ControlResell(apiKey = "cr_live_...", engine = CIO.create()) // or set CONTROLRESELL_API_KEY
val me = client.me.getMe().body()
client.listAllItems(sold = false).collect { println(it.name) }
```

Retries (rate limits, 502/503/504, network errors), `Idempotency-Key` on every `POST`, cursor pagination as a `Flow` and typed errors (`NotFoundException`, `RateLimitException`…) are built in. The client is generated from the API's OpenAPI document (`scripts/generate.sh`, code in `generated/`).

Not published yet. Licensed under the Apache License 2.0.
