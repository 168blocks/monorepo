package net.blocks168.e2e.bot

import net.blocks168.e2e.Network
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Protocol-level bots against the running network (tools/network.sh test): edge proxy → Velocity →
 * lobby / survival. Needs the Minecraft servers, which only run with the EULA accepted
 * (BLOCKS_ACCEPT_EULA=true); skipped otherwise.
 */
@Tag("network")
@Timeout(300)
class BotNetworkTest {
    private val network = Network.fromEnv()

    @BeforeEach
    fun needsMinecraftServers() {
        assumeTrue(System.getenv("BLOCKS_ACCEPT_EULA") == "true", "Minecraft servers not running (BLOCKS_ACCEPT_EULA is not true)")
    }

    /** A fresh player name (at most 16 characters), so tests never share player data. */
    private fun name(role: String) = "${role.take(6)}_" + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36 * 36 * 36), 36)

    /** Joins through [edge], retrying while the servers behind Velocity are still starting. */
    private fun join(role: String, edge: Int, wait: Duration = 180.seconds): Bot {
        val end = System.nanoTime() + wait.inWholeNanoseconds
        val bot = Bot(name(role))
        while (true) {
            try {
                return bot.connect(network.edge(edge), 60.seconds)
            } catch (e: Exception) {
                if (e !is BotException && e !is IOException) throw e
                if (System.nanoTime() > end) throw e
                println("[${bot.name}] join failed, retrying: ${e.message}")
                Thread.sleep(3000)
            }
        }
    }

    @Test
    fun botJoinsTheLobbyOverworldThroughEdge1() {
        join("alice", 1).use { alice ->
            alice.awaitWorld("minecraft:overworld")
            assertEquals(GameMode.ADVENTURE, alice.gameMode, "the lobby runs in adventure mode")
            assertEquals(1, alice.joins)
            assertTrue(alice.position.y > -64.0, "position ${alice.position}")
        }
    }

    @Test
    fun twoBotsThroughDifferentEdgesMeetInTheLobby() {
        join("alice", 2).use { alice ->
            join("bob", 3).use { bob ->
                alice.awaitWorld("minecraft:overworld")
                bob.awaitWorld("minecraft:overworld")
                assertEquals(alice.hashedSeed, bob.hashedSeed, "both should be in the same world")
                // same server: each sees the other's player entity, at its position
                bob.awaitPlayerAt(alice.uuid, alice.position, tolerance = 0.01)
                alice.awaitPlayerAt(bob.uuid, bob.position, tolerance = 0.01)
            }
        }
    }

    @Test
    fun movementIsAcceptedAndSeenByOthers() {
        join("alice", 1).use { alice ->
            join("bob", 2).use { bob ->
                val start = alice.position
                bob.awaitPlayerAt(alice.uuid, start, tolerance = 0.01)
                // The bot has no world: a small step up from where the server placed it is always
                // free (spawn positions have two blocks of air, the player is 1.8 high).
                val target = start + Vec3(0.0, 0.15, 0.0)
                alice.moveTo(target, onGround = false)
                assertEquals(target, alice.position)
                val seen = bob.awaitPlayerAt(alice.uuid, target, tolerance = 0.01)
                println("bob sees ${alice.name} at $seen (moved from $start)")
                alice.moveTo(start)
                bob.awaitPlayerAt(alice.uuid, start, tolerance = 0.01)
            }
        }
    }

    @Test
    fun chatReachesOtherPlayers() {
        join("alice", 1).use { alice ->
            join("bob", 3).use { bob ->
                bob.awaitPlayerAt(alice.uuid, alice.position, tolerance = 0.5)
                val text = "hello from ${alice.name}"
                alice.chat(text)
                val m = bob.awaitChat { text in it.text }
                println("bob got $m")
                assertTrue(!m.system, "expected a player message, got $m")
                assertTrue(m.sender == alice.uuid || m.senderName == alice.name, "sender of $m")
            }
        }
    }

    @Test
    fun followsVelocityServerSwitches() {
        join("alice", 1).use { alice ->
            assertEquals(GameMode.ADVENTURE, alice.gameMode)
            switch(alice, "survival", joins = 2)
            assertEquals(GameMode.SURVIVAL, alice.gameMode, "survival runs in survival mode")
            alice.awaitWorld("minecraft:overworld")
            switch(alice, "lobby", joins = 3)
            assertEquals(GameMode.ADVENTURE, alice.gameMode)
            // still alive and answering after two switches
            alice.moveTo(alice.position + Vec3(0.0, 0.1, 0.0), onGround = false)
        }
    }

    /** `/server <name>` (Velocity), retried while the target server is still starting. */
    private fun switch(bot: Bot, server: String, joins: Int, wait: Duration = 180.seconds) {
        val end = System.nanoTime() + wait.inWholeNanoseconds
        while (true) {
            bot.command("server $server")
            try {
                bot.awaitJoins(joins, 20.seconds)
                return
            } catch (e: BotException) {
                if (!bot.connected || System.nanoTime() > end) throw e
                println("[${bot.name}] switch to $server not done yet: ${bot.messages().lastOrNull()?.text}")
            }
        }
    }
}
