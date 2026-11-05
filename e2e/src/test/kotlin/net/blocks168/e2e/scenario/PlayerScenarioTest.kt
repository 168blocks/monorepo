package net.blocks168.e2e.scenario

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import net.blocks168.e2e.bot.GameMode
import kotlin.test.assertEquals
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Players on the network, written as scenarios: protocol-level bots connect through the edges, the
 * server agents check what the servers see. Needs the Minecraft servers (BLOCKS_ACCEPT_EULA=true).
 */
class PlayerScenarioTest {
    @NetworkScenario
    fun `second player sees the first in the lobby`() = scenario {
        val alice = bot("alice").connect(edge(1))
        alice.awaitWorld("minecraft:overworld")
        assertEquals(GameMode.ADVENTURE, alice.gameMode, "the lobby runs in adventure mode")
        val lobby = server("lobby")
        lobby.assertOnline(alice)
        proxy().assertOn(alice, "lobby")

        // the bot does not know the terrain: a small step up from its spawn point is always free
        val target = alice.position.copy(y = alice.position.y + 0.15)
        alice.moveTo(target, onGround = false)
        lobby.assertPosition(alice, target, tolerance = 0.01)

        val bob = bot("bob").connect(edge(2))
        bob.awaitWorld("minecraft:overworld")
        bob.awaitSees(alice, target)
        lobby.assertOnline(bob)
    }

    /** The README example with a real, rendering game client as the second player. */
    @NetworkScenario
    fun `a real game client sees a bot in the lobby`() = scenario(timeout = 10.minutes) {
        val alice = bot("alice").connect(edge(1)).awaitWorld("minecraft:overworld")
        val lobby = server("lobby")
        lobby.assertOnline(alice)
        val target = alice.position.copy(y = alice.position.y + 0.15)
        alice.moveTo(target, onGround = false)
        lobby.assertPosition(alice, target, tolerance = 0.01)

        val bob = client("bob").join(edge(2), "minecraft:overworld")
        lobby.assertOnline(bob.name)
        val seen = bob.awaitSees(alice)
        assertTrue(abs(seen.x - target.x) < 0.5 && abs(seen.y - target.y) < 0.5 && abs(seen.z - target.z) < 0.5, "bob sees alice at $seen, she is at $target")
        bob.hud(visible = false)
        bob.screenshot("lobby-alice-visible").assertNotBlank()
    }

    @NetworkScenario
    fun `players join through every edge at once and chat`() = scenario {
        // three players in parallel, one per edge
        val (alice, bob, carol) = (1..3).map { n ->
            async { bot(listOf("alice", "bob", "carol")[n - 1]).connect(edge(n)).awaitWorld("minecraft:overworld") }
        }.awaitAll()
        val lobby = server("lobby")
        for (p in listOf(alice, bob, carol)) lobby.assertOnline(p)
        bob.awaitSees(alice)
        carol.awaitSees(bob)

        val text = "hello from ${alice.name}"
        alice.chat(text)
        listOf(bob, carol).map { p ->
            async {
                val m = p.awaitChat { text in it.text }
                assertTrue(!m.system && (m.sender == alice.uuid || m.senderName == alice.name), "$p got $m")
            }
        }.awaitAll()
    }

    @NetworkScenario
    fun `a player switches servers and back`() = scenario {
        val alice = bot("alice").connect(edge(1)).awaitWorld("minecraft:overworld")
        val proxy = proxy()

        alice.switchTo("survival")
        assertEquals(GameMode.SURVIVAL, alice.gameMode)
        proxy.assertOn(alice, "survival")
        server("survival").assertOnline(alice)
        server("lobby").assertOffline(alice)

        // back through the proxy agent instead of the player's command
        val result = proxy.send(alice, "lobby")
        assertTrue(result.success, "send: $result")
        alice.awaitJoins(3)
        assertEquals(GameMode.ADVENTURE, alice.gameMode)
        proxy.assertOn(alice, "lobby")
        server("lobby").assertOnline(alice)
        // still playing after two switches
        alice.moveBy(dy = 0.1, onGround = false)
    }
}
