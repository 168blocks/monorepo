package dev.vxcc.b8.e2e.client

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.vxcc.b8.e2e.Network
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A real game client against the running network (tools/network.sh test). */
@Tag("network")
class GameClientTest {
    private val network = Network.fromEnv()

    @Test
    fun clientJoinsThroughEdgeAndRendersTheWorld() {
        assumeTrue(System.getenv("BLOCKS_ACCEPT_EULA") == "true", "the Minecraft servers only run with BLOCKS_ACCEPT_EULA=true")
        GameClients.fromEnv().use { clients ->
            val name = "cl_" + Random.nextInt(0, 0xffffff).toString(16)
            val client = clients.start(name)

            // edge 1 -> Velocity -> lobby (which may still be starting: join retries while the proxy
            // turns the client away)
            val lobby = client.join(network.edge(1), "minecraft:overworld")
            assertEquals(name, lobby["player"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            client.renderedScreenshot("lobby")

            // the survival world, without the HUD, looking slightly up: horizon and sky. Wait for the
            // terrain in view to be rendered, not for a fixed time (#89: on a fresh world the chunks
            // around spawn can take longer than that to arrive and compile).
            client.switchServer("survival", "minecraft:overworld")
            client.hud(visible = false).look(yaw = 0.0, pitch = -15.0)
            val first = client.renderedScreenshot("survival", minSections = 10)
            assertEquals(854 to 480, first.width to first.height)
            // a still view renders (nearly) the same frame again
            client.awaitRendered(minSections = 10)
            val second = client.screenshot("survival-again").assertNotBlank()
            val diff = first.difference(second)
            assertTrue(diff < 0.05, "consecutive frames differ by $diff")
        }
    }
}
