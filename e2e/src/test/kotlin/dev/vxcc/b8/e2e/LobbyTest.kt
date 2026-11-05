package dev.vxcc.b8.e2e

import kotlinx.coroutines.runBlocking
import dev.vxcc.b8.e2e.agent.ServerAgent
import dev.vxcc.b8.e2e.bot.Bot
import dev.vxcc.b8.e2e.bot.BotException
import dev.vxcc.b8.e2e.bot.Vec3
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The lobby world (deploy/fabric/lobby): a flat grass layer at y=-64 with the spawn on top of it at
 * 0/-63/0, its chunks kept loaded (#26: players spawned inside the ground otherwise). Needs the
 * Minecraft servers (BLOCKS_ACCEPT_EULA=true); skipped otherwise.
 */
@Tag("network")
class LobbyTest {
    private val eula get() = System.getenv("BLOCKS_ACCEPT_EULA") == "true"

    @Test
    fun playersSpawnOnTheFloorAndCanWalk() = runBlocking {
        assumeTrue(eula, "Minecraft EULA not accepted (BLOCKS_ACCEPT_EULA)")
        ServerAgent.fromEnv("lobby").use { lobby ->
            lobby.awaitReady(5.minutes)
            val name = "spawn_" + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36 * 36 * 36), 36)
            join(Bot(name)).use { bot ->
                bot.awaitWorld("minecraft:overworld")
                assertEquals(Vec3(0.5, -63.0, 0.5), bot.position, "spawn position")
                Thread.sleep(2000) // long enough to take suffocation damage
                val p = lobby.player(name) ?: error("$name not on the lobby")
                assertEquals(Triple(0.5, -63.0, 0.5), Triple(p.x, p.y, p.z), "position on the server")
                assertContains(lobby.command("data get entity $name Health").text, "20.0f")
                // the walking area: a 20x20 square around the spawn, without position corrections
                val start = bot.position
                for ((dx, dz) in listOf(10.0 to 0.0, 10.0 to 10.0, -10.0 to 10.0, -10.0 to -10.0, 0.0 to 0.0)) {
                    bot.moveTo(start + Vec3(dx, 0.0, dz), step = 0.2)
                }
            }
        }
    }

    /** Joins through edge 1, retrying while the servers behind Velocity are still starting. */
    private fun join(bot: Bot): Bot {
        val end = System.nanoTime() + 180.seconds.inWholeNanoseconds
        while (true) {
            try {
                return bot.connect(Network.fromEnv().edge(1), 60.seconds)
            } catch (e: Exception) {
                if ((e !is BotException && e !is IOException) || System.nanoTime() > end) throw e
                Thread.sleep(3000)
            }
        }
    }

    @Test
    fun spawnStandsOnAFreeFlatFloor() = runBlocking {
        assumeTrue(eula, "Minecraft EULA not accepted (BLOCKS_ACCEPT_EULA)")
        ServerAgent.fromEnv("lobby").use { lobby ->
            lobby.awaitReady(5.minutes)
            suspend fun check(command: String) = lobby.command(command).also { assertTrue(it.success, "$command: $it") }

            // floor under the spawn and across the walking area around it, two blocks of air above
            for ((x, z) in listOf(0 to 0, 24 to 0, -24 to 0, 0 to 24, 0 to -24, 24 to 24, -24 to -24)) {
                check("execute if block $x -64 $z minecraft:grass_block")
                check("execute if block $x -63 $z minecraft:air")
                check("execute if block $x -62 $z minecraft:air")
            }
            // nothing else generated: the 16 blocks above the floor around the spawn are the same as
            // (empty) air 16 blocks higher, a free walking area
            check("execute if blocks -16 -63 -16 16 -48 16 -16 -47 -16 all")
            check("execute if block 0 -47 0 minecraft:air")
            // the spawn chunk stays loaded (adventure-mode spawns do not load it, #26)
            check("forceload query 0 0")
            assertContains(check("gamerule respawn_radius").text, "0")
            assertContains(check("gamerule advance_time").text, "false")
        }
    }
}
