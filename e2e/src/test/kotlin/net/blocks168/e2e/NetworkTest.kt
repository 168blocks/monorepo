package net.blocks168.e2e

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import net.blocks168.api.ApiJson
import net.blocks168.api.ApiPaths
import net.blocks168.api.Counter
import net.blocks168.api.Health
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests against the running network (tools/network.sh test). */
@Tag("network")
class NetworkTest {
    private val network = Network.fromEnv()

    @ParameterizedTest(name = "edge {0} reaches Velocity with the example plugin")
    @ValueSource(ints = [1, 2, 3])
    fun edgeReachesVelocity(edge: Int) {
        // the edge (HAProxy) accepts connections before Velocity behind it is up: retry for a while
        val end = System.nanoTime() + 60_000_000_000L
        var status: StatusPing.Status? = null
        while (status == null) {
            status = runCatching { StatusPing.ping(network.edge(edge).host, network.edge(edge).port) }
                .onFailure { if (System.nanoTime() > end) throw it else Thread.sleep(1000) }
                .getOrNull()
        }
        // the MOTD is set by velocity/example-plugin (ProxyPingEvent), so this also checks the plugin
        assertTrue(status.description.startsWith("168blocks"), "MOTD was: ${status.description}")
        assertTrue(status.versionName.contains("Velocity"), "version was: ${status.versionName}")
    }

    @Test
    fun backendCounterUsesDatabase() = runBlocking {
        HttpClient { install(ContentNegotiation) { json(ApiJson) } }.use { http ->
            val health = http.get(network.backendUrl + ApiPaths.HEALTH).body<Health>()
            assertEquals("ok", health.status)
            assertTrue(health.database != null, "backend is not connected to PostgreSQL")
            val before = http.get(network.backendUrl + ApiPaths.COUNTER).body<Counter>().value
            assertEquals(before + 1, http.post(network.backendUrl + ApiPaths.COUNTER_INCREMENT).body<Counter>().value)
        }
    }

    @Test
    fun backendServesFrontend() = runBlocking {
        HttpClient().use { http ->
            val page = http.get(network.backendUrl + "/").body<String>()
            assertTrue("frontend.js" in page, "index.html not served")
        }
    }
}
