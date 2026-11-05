package net.blocks168.e2e

import kotlinx.coroutines.runBlocking
import net.blocks168.e2e.agent.AgentException
import net.blocks168.e2e.agent.ProxyAgent
import net.blocks168.e2e.agent.ServerAgent
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The e2e agents (mods/e2e-agent, velocity/e2e-agent) against the running network. */
@Tag("network")
class AgentTest {
    @Test
    fun velocityReportsItsServers() = runBlocking {
        ProxyAgent.fromEnv().use { proxy ->
            proxy.awaitReady(60.seconds)
            assertTrue(proxy.status().version.isNotBlank())
            assertEquals(setOf("lobby", "survival"), proxy.servers().map { it.name }.toSet())
            assertTrue(proxy.players().none { it.name == "nobody" })
            val e = assertFailsWith<AgentException> { proxy.send("nobody", "lobby") }
            assertEquals(404, e.status)
        }
    }

    @ParameterizedTest(name = "{0} reports its worlds and runs commands")
    @ValueSource(strings = ["lobby", "survival"])
    fun serverAgent(name: String) = runBlocking {
        // the Fabric servers only run with the EULA accepted (tools/network.sh)
        assumeTrue(System.getenv("BLOCKS_ACCEPT_EULA") == "true", "Minecraft EULA not accepted (BLOCKS_ACCEPT_EULA)")
        ServerAgent.fromEnv(name).use { server ->
            // the first start downloads the server and generates the world
            server.awaitReady(5.minutes)
            assertEquals(name, server.status().server)
            assertContains(server.worlds().map { it.id }, "minecraft:overworld")
            assertTrue(server.players().none { it.name == "nobody" })

            val list = server.command("list")
            assertTrue(list.success, "list: $list")
            assertContains(list.text, "players online")

            val unknown = server.command("no-such-command")
            assertFalse(unknown.success, "no-such-command: $unknown")
            assertTrue(unknown.output.isNotEmpty(), "no error message for an unknown command")
        }
    }
}
