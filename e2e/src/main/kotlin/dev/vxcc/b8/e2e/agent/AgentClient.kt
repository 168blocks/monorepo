package dev.vxcc.b8.e2e.agent

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** An agent answered with an error status (`{"error": "..."}`). */
class AgentException(val status: Int, message: String) : RuntimeException(message)

/**
 * HTTP client for the dev-only e2e agents (mods/e2e-agent, velocity/e2e-agent): JSON over HTTP, see
 * [ServerAgent] and [ProxyAgent].
 */
abstract class AgentClient(val baseUrl: String) : AutoCloseable {
    protected val http = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) { json(AgentJson) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    }

    protected suspend inline fun <reified T> get(path: String): T = http.get(baseUrl + path).checked().body()

    protected suspend inline fun <reified T> post(path: String, body: JsonObject): T =
        http.post(baseUrl + path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.checked().body()

    protected suspend fun HttpResponse.checked(): HttpResponse {
        if (status.isSuccess()) return this
        val text = bodyAsText()
        val error = runCatching { AgentJson.parseToJsonElement(text) as JsonObject }.getOrNull()?.get("error")?.jsonPrimitive?.content
        throw AgentException(status.value, "${request.url}: ${status.value} ${error ?: text}")
    }

    /** Waits until the agent answers `/status`, e.g. while its server is still starting. */
    suspend fun awaitReady(timeout: Duration) {
        await(timeout, "agent at $baseUrl") { runCatching { http.get("$baseUrl/status").status.isSuccess() }.getOrDefault(false) }
    }

    override fun close() = http.close()

    companion object {
        val AgentJson = Json { ignoreUnknownKeys = true }

        internal fun url(env: Map<String, String>, name: String, default: String?): String =
            env["BLOCKS_E2E_AGENT_${name.uppercase()}"] ?: default
                ?: error("no e2e agent URL for '$name': set BLOCKS_E2E_AGENT_${name.uppercase()}")
    }
}

/** Polls [condition] until it holds, failing after [timeout] with [what] in the message. */
suspend fun await(timeout: Duration, what: String, poll: Duration = 250.milliseconds, condition: suspend () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!condition()) {
        if (deadline.hasPassedNow()) throw AssertionError("timed out after $timeout waiting for $what")
        delay(poll)
    }
}
