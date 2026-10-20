package com.controlresell.sdk

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultEngineTest {

    @Test
    fun defaultClient_withoutEngine_callsALocalServer() {
        val seen = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen += exchange.requestHeaders.getFirst("Authorization")
            val body = """{"id":1,"username":"seller","currency":"EUR"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            ControlResell(apiKey = "cr_test_key", baseUrl = "http://127.0.0.1:${server.address.port}").use { client ->
                val me = runBlocking { client.me.getMe().body() }
                assertEquals("seller", me.username)
            }
            assertEquals(listOf<String?>("Bearer cr_test_key"), seen)
        } finally {
            server.stop(0)
        }
    }
}
