package net.blocks168.mod.e2e

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonHttpServerTest {
    data class Pong(val n: Int)

    private fun withServer(block: (HttpClient, String) -> Unit) = JsonHttpServer(0).use { server ->
        server.get("/ping") { Pong(1) }
        server.post("/echo") { body -> mapOf("got" to (body.get("x")?.asString ?: throw IllegalArgumentException("missing x"))) }
        server.start()
        HttpClient.newHttpClient().use { block(it, "http://127.0.0.1:${server.port}") }
    }

    private fun HttpClient.call(url: String, body: String? = null): Pair<Int, String> {
        val req = HttpRequest.newBuilder(URI(url)).apply { if (body != null) POST(HttpRequest.BodyPublishers.ofString(body)) }.build()
        return send(req, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
    }

    @Test
    fun routes() = withServer { http, base ->
        assertEquals(200 to """{"n":1}""", http.call("$base/ping"))
        assertEquals(200 to """{"got":"y"}""", http.call("$base/echo", """{"x":"y"}"""))
        assertEquals(400 to """{"error":"missing x"}""", http.call("$base/echo", "{}"))
        assertEquals(405, http.call("$base/ping", "{}").first)
        assertEquals(404, http.call("$base/ping/more").first)
        assertEquals(404, http.call("$base/nothing").first)
    }
}
