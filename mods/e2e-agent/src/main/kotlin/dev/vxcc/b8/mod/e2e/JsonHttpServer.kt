package dev.vxcc.b8.mod.e2e

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A tiny JSON-over-HTTP server on the JDK's built-in `com.sun.net.httpserver`. Handlers return any
 * Gson-serializable value; [IllegalArgumentException] becomes 400, [NoSuchElementException] 404,
 * anything else 500, each as `{"error": "..."}`.
 *
 * velocity/e2e-agent has a copy: the two dev-only agents share no library, so production libraries
 * stay free of it.
 */
class JsonHttpServer(port: Int, private val gson: Gson = GsonBuilder().serializeNulls().create()) : AutoCloseable {
    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "e2e-agent-http").apply { isDaemon = true }
    }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0).apply { executor = this@JsonHttpServer.executor }

    val port: Int get() = server.address.port

    fun get(path: String, handler: () -> Any?) = route("GET", path) { handler() }

    /** POST with a JSON object body (an empty body is `{}`) */
    fun post(path: String, handler: (JsonObject) -> Any?) = route("POST", path) { body ->
        val json = if (body.isBlank()) JsonObject() else JsonParser.parseString(body)
        require(json.isJsonObject) { "body must be a JSON object" }
        handler(json.asJsonObject)
    }

    private fun route(method: String, path: String, handler: (String) -> Any?) {
        server.createContext(path) { ex ->
            ex.use {
                val (status, value) = when {
                    ex.requestURI.path != path -> 404 to mapOf("error" to "not found: ${ex.requestURI.path}")
                    ex.requestMethod != method -> 405 to mapOf("error" to "use $method")
                    else -> try {
                        200 to handler(ex.requestBody.readAllBytes().decodeToString())
                    } catch (e: IllegalArgumentException) {
                        400 to mapOf("error" to (e.message ?: e.toString()))
                    } catch (e: NoSuchElementException) {
                        404 to mapOf("error" to (e.message ?: e.toString()))
                    } catch (e: Exception) {
                        500 to mapOf("error" to e.toString())
                    }
                }
                respond(ex, status, gson.toJson(value))
            }
        }
    }

    private fun respond(ex: HttpExchange, status: Int, json: String) {
        val bytes = json.encodeToByteArray()
        ex.responseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    fun start() = server.start()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
