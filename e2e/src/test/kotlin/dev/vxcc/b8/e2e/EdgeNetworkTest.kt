package dev.vxcc.b8.e2e

import kotlinx.coroutines.runBlocking
import dev.vxcc.b8.e2e.agent.ProxyAgent
import dev.vxcc.b8.e2e.bot.Bot
import dev.vxcc.b8.e2e.bot.BotException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The network plugin (velocity/network) across the three edges: one online-players registry, the
 * network-wide count in pings, the newest login winning across edges, drain by transfer.
 */
@Tag("network")
class EdgeNetworkTest {
    private val network = Network.fromEnv()

    @BeforeEach
    fun needsMinecraftServers() {
        assumeTrue(System.getenv("BLOCKS_ACCEPT_EULA") == "true", "Minecraft servers not running (BLOCKS_ACCEPT_EULA is not true)")
    }

    private fun name(role: String) = "${role.take(6)}_" + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36 * 36 * 36), 36)

    /** Joins through [edge], retrying while the network is still starting (servers, schema) */
    private fun join(bot: Bot, edge: Int, wait: Duration = 180.seconds): Bot {
        val end = System.nanoTime() + wait.inWholeNanoseconds
        while (true) {
            try {
                return bot.connect(network.edge(edge), 60.seconds)
            } catch (e: Exception) {
                if (e !is BotException && e !is IOException) throw e
                if (System.nanoTime() > end) throw e
                println("[${bot.name}] join via edge $edge failed, retrying: ${e.message}")
                Thread.sleep(3000)
            }
        }
    }

    private fun ping(edge: Int) = StatusPing.ping(network.edge(edge).host, network.edge(edge).port)

    private fun eventually(what: String, timeout: Duration = 20.seconds, check: () -> Boolean) {
        val end = System.nanoTime() + timeout.inWholeNanoseconds
        while (!check()) {
            if (System.nanoTime() > end) throw AssertionError("timeout after $timeout: $what")
            Thread.sleep(500)
        }
    }

    @Test
    fun aBotLogsInThroughEachEdge() {
        for (edge in 1..3) join(Bot(name("each$edge")), edge).use { bot -> bot.awaitWorld("minecraft:overworld") }
    }

    /** The count edge [edge] reports once it stopped changing (the edges refresh it every 2 s) */
    private fun settledCount(edge: Int): Int {
        var last = ping(edge).onlinePlayers
        while (true) {
            Thread.sleep(3000)
            val now = ping(edge).onlinePlayers
            if (now == last) return now
            last = now
        }
    }

    @Test
    fun pingsShowTheNetworkWidePlayerCount() {
        // warm up: the network is up and the plugin out of maintenance
        join(Bot(name("warm")), 1).close()
        val base = settledCount(2)
        Bot(name("count")).use { bot ->
            join(bot, 1)
            eventually("edges 2 and 3 count the player on edge 1 (${base + 1})") {
                ping(2).onlinePlayers == base + 1 && ping(3).onlinePlayers == base + 1
            }
        }
        eventually("count back to $base once the player left") { ping(2).onlinePlayers == base && ping(3).onlinePlayers == base }
    }

    @Test
    fun theNewestLoginWinsAcrossEdges() {
        val name = name("dup")
        join(Bot(name), 1).use { first ->
            join(Bot(name), 2).use { second ->
                first.await("kick of the older session", 15.seconds, allowDisconnected = true) { !connected }
                assertContains(first.disconnectReason ?: "", "another location")
                Thread.sleep(2000)
                assertTrue(second.connected, "the newer session was dropped too: ${second.disconnectReason}")
            }
        }
    }

    @Test
    fun drainTransfersPlayersToAnotherEdge() = runBlocking {
        ProxyAgent.fromEnv().use { edge1 ->
            val bot = join(Bot(name("drain")), 1)
            try {
                assertTrue(edge1.command("network drain").success)
                bot.followTransfer(30.seconds)
                val to = bot.lastTransfer
                assertNotEquals(network.edge(1).toString(), to, "transferred back to edge 1")
                assertContains(network.edges.map { it.toString() }, to)
                bot.awaitWorld("minecraft:overworld")
                // edge 1 refuses new logins while draining
                val refused = runCatching { Bot(name("late")).use { it.connect(network.edge(1), 20.seconds) } }
                assertTrue(refused.isFailure, "edge 1 accepted a login while draining")
                assertContains(refused.exceptionOrNull()?.message ?: "", "restarted")
            } finally {
                bot.close()
                edge1.command("network undrain")
            }
            join(Bot(name("undrn")), 1, 30.seconds).close()
        }
    }

    @Test
    fun sameNameTwiceOnOneEdgeKeepsTheNewest() {
        val name = name("same")
        join(Bot(name), 3).use { first ->
            join(Bot(name), 3).use { second ->
                first.await("kick of the older session", 15.seconds, allowDisconnected = true) { !connected }
                assertTrue(second.connected)
                assertEquals(false, first.connected)
            }
        }
    }
}
