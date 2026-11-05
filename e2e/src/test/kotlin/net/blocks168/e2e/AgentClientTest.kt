package net.blocks168.e2e

import net.blocks168.e2e.agent.AgentClient
import net.blocks168.e2e.agent.ProxyAgent
import net.blocks168.e2e.agent.ServerAgent
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Unit tests of the agent clients (no network). */
@Tag("unit")
class AgentClientTest {
    @Test
    fun urlsFromEnv() {
        val env = mapOf("BLOCKS_E2E_AGENT_LOBBY" to "http://127.0.0.1:28511", "BLOCKS_E2E_AGENT_VELOCITY" to "http://127.0.0.1:28510")
        ServerAgent.fromEnv("lobby", env).use { assertEquals("http://127.0.0.1:28511", it.baseUrl) }
        ServerAgent.fromEnv("survival", env).use { assertEquals("http://127.0.0.1:28502", it.baseUrl) }
        ProxyAgent.fromEnv(env).use { assertEquals("http://127.0.0.1:28510", it.baseUrl) }
        assertFailsWith<IllegalStateException> { ServerAgent.fromEnv("creative", env) }
    }

    @Test
    fun decodesAgentJson() {
        val players = AgentClient.AgentJson.decodeFromString<List<ServerAgent.Player>>(
            """[{"uuid":"u","name":"alice","dimension":"minecraft:overworld","x":1.5,"y":64.0,"z":-2.5,"yaw":90.0,"pitch":0.0,"gameMode":"adventure","new":1}]""",
        )
        assertEquals("alice", players.single().name)
        assertEquals(null, AgentClient.AgentJson.decodeFromString<ProxyAgent.Player>("""{"uuid":"u","name":"bob","server":null}""").server)
    }
}
